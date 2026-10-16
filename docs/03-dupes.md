---
title: "Finding Duplicate Files"
nav_order: 3
lede: "A duplicate finder that starts by comparing everything with everything and ends by hashing."
---

Disks fill up with copies. A photo gets downloaded twice, a report is
saved under two names, a whole directory is backed up into itself. A tool
that finds files with identical contents is small enough to write in an
afternoon, and writing it well teaches three things that come up in
almost every program: how to tell whether an algorithm will still be
usable when the input is a hundred times bigger, how hashing turns an
expensive question ("are these the same?") into a cheap one ("do these
have the same label?"), and how to test code that reads the filesystem
without leaving a mess behind.

We build the tool three times. The first version compares every file with
every other file. It is obviously correct and obviously slow, and we
measure how slow. The second version sorts files into a handful of
buckets using a hash function so weak that it is almost a joke, which
shows both why hashing works and what a bad hash costs. The third version
uses SHA-256, a hash good enough that we can treat two files with the
same hash as the same file, and the comparisons disappear entirely.

## Finding the files

Every version of the tool needs the same two things: a list of the files
under a directory, and the bytes in each file. They go in a namespace of
their own so that each stage can concentrate on its own idea:

```clojure
(ns sdx.dupes.files
  "Finding the files to compare and reading what is in them.

   Every stage of the duplicate finder needs the same two things: the
   list of regular files under a directory, and the bytes in a file.
   They live here so that each stage can concentrate on its own idea."
  (:require [clojure.java.io :as io]))

(defn list-files
  "The paths of every regular file under `root`, as strings, sorted."
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       sort
       vec))

(defn read-bytes
  "The contents of the file at `path` as a byte array."
  ^bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
```

Two pieces of Clojure deserve a word here. The first is `file-seq`, which
walks a directory tree and returns a **lazy sequence** of
`java.io.File` objects: a sequence whose elements are computed only as
something asks for them. Laziness does not matter much here, since we
walk the whole tree anyway, but it is the reason `file-seq` can be
written as "the sequence of everything under this directory" without
worrying about how large that is.

The second is **Java interop**. Clojure runs on the JVM, and anything the
Java standard library can do, Clojure can do by calling it directly.
`(.isFile f)` calls the `isFile` method on `f`; `(.getPath f)` asks for
its path as a string; `java.nio.file.Files/readAllBytes` is a static
method that reads a whole file into a byte array. The `^java.io.File`
and `^bytes` annotations are **type hints**. They do not change what the
code does; they tell the compiler which class to look up the method on so
that it does not have to search at run time. Leaving them out is legal,
just slower, and the compiler will tell you so if you ask it to with
`(set! *warn-on-reflection* true)`.

We sort the paths and return a vector rather than the lazy sequence so
that every later stage sees the files in the same order. Deterministic
order is not something the operating system promises, and a tool whose
output comes out differently on each run is miserable to test.

## Comparing every pair

If we have two files, the only way to be sure they are identical is to
compare their contents. If we have a directory full of files, the obvious
extension is to compare every file with every other file:

```clojure
(defn pairs
  "Every unordered pair of distinct items from `items`, as two-element
   vectors. There are n(n-1)/2 of them for n items."
  [items]
  (let [v (vec items)
        n (count v)]
    (for [i (range n)
          j (range (inc i) n)]
      [(v i) (v j)])))
```

```clojure
(pairs [:a :b :c])   ;; => ([:a :b] [:a :c] [:b :c])
```

`for` is Clojure's list comprehension: it produces one value for every
combination of its bindings, lazily. Starting `j` at `(inc i)` means we
produce `[:a :b]` but not `[:b :a]` or `[:a :a]`, which is what
"unordered pairs of distinct items" means. Separating "which pairs" from
"what to do with each pair" is a small decision with a large payoff: we
can count the pairs without doing any work on them.

```clojure
(count (pairs (range 10)))      ;; => 45
(count (pairs (range 100)))     ;; => 4950
(count (pairs (range 1000)))    ;; => 499500
(count (pairs (range 10000)))   ;; => 49995000
```

Each time the number of files goes up by a factor of ten, the number of
pairs goes up by a factor of about a hundred. The exact count is
n(n-1)/2, and the usual way to describe this is to say that the algorithm
is **O(n²)**: for large n, the work grows like the square of the input
size, and the constants in front do not matter. Ten thousand files is a
modest photo collection; fifty million comparisons, each of which reads
two files, is not a modest amount of work.

Comparing the contents of two files is a one-liner, because the Java
standard library already knows how to compare byte arrays:

```clojure
(defn same-bytes?
  "True if two byte arrays have the same length and the same contents."
  [^bytes a ^bytes b]
  (java.util.Arrays/equals a b))
```

We need this because `=` on two byte arrays asks whether they are the
same *array*, not whether they have the same contents. That is a common
trap when mixing Clojure's value-based equality with Java's objects:
Clojure's own collections compare by value, but arrays are Java's, and
Java compares them by identity.

Finding the duplicate pairs is then a filter over the pairs. We read each
file once up front and keep its bytes in a map, so that the quadratic
part of the work is comparing arrays in memory rather than reading files
from disk:

```clojure
(defn duplicate-pairs
  "Every pair of paths whose files have identical contents. Each file is
   read once; each pair of files is compared once."
  [paths]
  (let [contents (into {} (map (juxt identity files/read-bytes) paths))]
    (filter (fn [[a b]] (same-bytes? (contents a) (contents b)))
            (pairs paths))))
```

`juxt` takes several functions and returns a function that calls all of
them on its argument and collects the results in a vector, so
`(juxt identity files/read-bytes)` turns a path into
`[path bytes]`, which is exactly the shape `into {}` needs to build a map.
Reading everything into memory is fine for a chapter and wrong for a
tool: a directory can easily hold more than fits in RAM. We will fix
that by the end.

A tool that reports pairs is annoying to use. If three files are
identical we get three pairs, and the person reading the output has to
work out that they describe one group. Since "has the same contents as"
is transitive, we can merge the pairs into groups by collecting, for each
path, everything it was paired with:

```clojure
(defn pairs->groups
  "Merge pairs that share a member into groups. Each group is a sorted
   vector of paths, and the groups are sorted by their first member.
   This relies on the relation being transitive: if a is paired with b
   and b with c, then a must be paired with c as well."
  [pairs]
  (let [partners (reduce (fn [m [a b]]
                           (-> m
                               (update a (fnil conj #{a}) b)
                               (update b (fnil conj #{b}) a)))
                         {}
                         pairs)]
    (->> (vals partners)
         distinct
         (map (comp vec sort))
         (sort-by first))))
```

```clojure
(pairs->groups [[:a :b] [:b :c] [:a :c] [:x :y]])
;; => ([:a :b :c] [:x :y])
```

`fnil` wraps a function so that a `nil` first argument is replaced with a
default: `(fnil conj #{a})` behaves like `conj`, except that when the map
has no entry for `a` yet it starts from the set `#{a}` instead of from
`nil`. Because every member of a group ends up with the same set of
partners, `distinct` collapses the three copies of `#{:a :b :c}` into
one.

The last line hides a small lesson. The first draft of this function
ended with plain `sort`, and the tests failed because
`[:d :e]` sorted before `[:a :b :c]`. Clojure compares vectors by length
first and only then by contents, which is sensible for some purposes and
surprising here. `(sort-by first)` says what we actually mean: order the
groups by their first path.

Wrapping it all up gives the first complete version of the tool:

```clojure
(defn duplicate-groups
  "Groups of files under `root` with identical contents, found by
   comparing every pair of files."
  [root]
  (-> (files/list-files root)
      duplicate-pairs
      pairs->groups))
```

It is short, it is clearly right, and on a directory of ten thousand
files it does fifty million array comparisons. We can do better.

## Hashing: sorting into buckets

Here is the idea that makes the second version faster. Suppose we had a
function that turned a file's contents into a small number, with one
guarantee: files with the same contents always get the same number. Then
two files with *different* numbers cannot be duplicates, and we only need
to compare files that share a number. Such a function is a **hash
function**, and the number it produces is a **hash**.

To see what the guarantee buys us on its own, we start with the weakest
hash function we can think of that still has it:

```clojure
(def bucket-count
  "How many buckets the naive hash sorts files into."
  13)

(defn naive-hash
  "A hash of a byte array: the sum of its bytes, modulo `bucket-count`.
   Each byte is treated as an unsigned number from 0 to 255."
  [^bytes bs]
  (mod (reduce (fn [sum b] (+ sum (bit-and b 0xff))) 0 bs)
       bucket-count))
```

```clojure
(naive-hash (.getBytes "hello"))   ;; => 12
(naive-hash (.getBytes ""))        ;; => 0
```

The `bit-and` with `0xff` is there because of a quirk of the JVM. Java's
`byte` type is signed: it runs from -128 to 127, so a byte that a file
format would call 200 shows up in Clojure as -56. Masking with `0xff`
keeps the low eight bits and throws away the sign, giving back 200.
Forgetting this does not break the guarantee (equal files still get equal
sums), but it makes the hash depend on a detail of the platform rather
than on the data, which is the kind of thing that bites later.

The function is honest about its weakness. Any two files whose bytes are
rearrangements of each other get the same sum:

```clojure
(naive-hash (.getBytes "listen"))   ;; => 5
(naive-hash (.getBytes "silent"))   ;; => 5
```

Two different inputs with the same hash are a **collision**. Collisions
are not a bug; with a hash that has only thirteen possible values, the
pigeonhole principle says that any fourteen files must contain one. What
they mean is that sharing a hash is *evidence* of being identical, not
proof, and the tool has to follow up with a real comparison.

Sorting the files into buckets is one call to `group-by`, which runs a
function on each item and returns a map from each result to the vector
of items that produced it:

```clojure
(defn buckets
  "A map from hash value to the paths of the files with that hash."
  [paths]
  (group-by #(naive-hash (files/read-bytes %)) paths))
```

Inside each bucket we fall back on the brute-force comparison from the
previous stage, which is why that stage split its work into reusable
pieces:

```clojure
(defn duplicate-groups
  "Groups of files under `root` with identical contents: put the files
   in buckets by hash, then compare pairs only within each bucket."
  [root]
  (->> (files/list-files root)
       buckets
       vals
       (filter #(> (count %) 1))
       (mapcat (comp brute/pairs->groups brute/duplicate-pairs))
       (sort-by first)))
```

`mapcat` maps a function over a collection and concatenates the results,
which is what we want when each bucket produces a list of groups and we
want one flat list.

How much did this buy us? With a thousand files and thirteen buckets of
roughly equal size, each bucket holds about 77 files, so each bucket
needs about 2,900 comparisons and the whole job needs about 38,000
instead of 499,500: thirteen times fewer. In general, a hash with k
evenly used values cuts the quadratic term by a factor of k. That tells
us exactly what a good hash function looks like:

- It must be **deterministic**: the same contents always give the same
  hash, or the whole idea collapses.
- It should have **many possible values**, so that k is large.
- It should **spread inputs evenly** across those values, so that no
  bucket is much bigger than the others. Our byte sum does reasonably
  well on this count; hashing the strings `"0"` through `"999"` gives
  buckets of between 70 and 82 files.
- It should make **similar inputs land far apart**. This is where the
  byte sum fails badly. Files that differ by a swapped pair of bytes
  collide every time, and files that differ by one byte land in
  neighbouring buckets.
- It should be **fast**, since we compute it once per file.

Real-world files are not random. Backups, versions of the same document,
and files that share a header are exactly the kind of near-duplicates
that a weak hash piles into the same bucket, so the "thirteen times
fewer" figure is optimistic. We want a hash whose values are so numerous
and so well spread that collisions never happen in practice.

## A hash good enough to trust

Cryptographic hash functions are designed to have all of the properties
above in the extreme. SHA-256 produces a 256-bit value, so k is about
10⁷⁷, and it is built so that changing a single bit of the input changes
about half the bits of the output in a way nobody has found a shortcut
for. The JDK ships an implementation, and Clojure can call it:

```clojure
(defn hex
  "A byte array as a lowercase hexadecimal string, two characters per byte."
  [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn sha256
  "The SHA-256 digest of a byte array, as a 64-character hex string."
  [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (hex (.digest md bs))))
```

```clojure
(hex (byte-array [0 15 -1]))   ;; => "000fff"
(sha256 (.getBytes "hello"))
;; => "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
(sha256 (.getBytes "hellp"))
;; => "fdd7585e08c4e2afd71dcabdb4636c89d557a3f42db9e2040c8bbd1708aa4ce7"
```

`MessageDigest/getInstance` looks up an algorithm by name and returns an
object that computes it; `.digest` feeds it a byte array and returns the
32-byte result. We convert that to hex because a string is easier to
print, compare, and put in a test than an array (recall that arrays
compare by identity). The `bit-and` is the same sign fix as before, and
`%02x` tells `format` to print each byte as two hex digits with a leading
zero if needed.

Changing one letter of "hello" changed 63 of the 64 hex digits. That is
the property we were missing.

With a hash this good we can afford to fix the memory problem from the
first stage. Instead of reading a whole file and then hashing it, we feed
the digest a chunk at a time:

```clojure
(def ^:private buffer-size 8192)

(defn file-sha256
  "The SHA-256 digest of the file at `path`, read in chunks so that a
   file larger than memory can still be hashed."
  [path]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array buffer-size)]
    (with-open [in (io/input-stream path)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (hex (.digest md))))
```

This is the first place in the book where we have had to use mutable
state, and it is worth being clear about why. `MessageDigest` is a Java
object that accumulates input: each `.update` changes it, and `.digest`
reads out the result. The byte array `buf` is overwritten by every
`.read`. Neither of these is a Clojure value, and we do not try to
pretend otherwise; we keep both inside one function, where nothing else
can see them, and return an immutable string. State at the edges, values
everywhere else, is a pattern that will recur throughout the book.

`with-open` opens a resource, binds it to a name, and guarantees that its
`.close` method is called when the body finishes, whether normally or by
throwing. `loop`/`recur` is Clojure's way of writing a loop without
growing the stack: `recur` jumps back to the `loop` with new bindings, or
in this case with none. The loop reads until `.read` returns -1 at the
end of the file.

The final version of the tool is now almost nothing but `group-by`:

```clojure
(defn duplicate-groups
  "Groups of files under `root` with identical contents, found by
   hashing each file once and grouping on the hash."
  [root]
  (->> (files/list-files root)
       (group-by file-sha256)
       vals
       (filter #(> (count %) 1))
       (map (comp vec sort))
       (sort-by first)))
```

Each file is read once and hashed once, and files with the same hash are
reported as duplicates without being compared. The work is proportional
to the total size of the files, which is the least any program that has
to look at every byte could do, and it is **O(n)** in the number of
files: ten times as many files means ten times as much work, not a
hundred.

> **Are we sure?**
> We have replaced "the files are identical" with "the files have the
> same SHA-256 hash", and those are not the same statement. They are,
> however, the same statement for every practical purpose. The chance of
> two different files colliding by accident is about one in 10⁷⁷, which
> is many orders of magnitude less likely than the computer running the
> comparison being hit by a meteorite. Constructing a collision on
> purpose is a research problem nobody has solved for SHA-256. If that
> still bothers you, the first exercise asks you to put the comparison
> back; with a good hash it costs almost nothing, because the only files
> that ever get compared are real duplicates.

## A command-line tool

A duplicate finder is naturally something you run from a shell, so the
last namespace adds a `-main` function. `clojure -M -m sdx.dupes.main`
loads the namespace and calls `-main` with the remaining command-line
arguments as strings:

```clojure
(defn report
  "A printable report of the duplicate groups under `root`: one path per
   line, groups separated by blank lines, or a note that there are none."
  [root]
  (let [groups (hash/duplicate-groups root)]
    (if (empty? groups)
      "No duplicates found."
      (str/join "\n\n" (map #(str/join "\n" %) groups)))))

(defn -main
  "Print the duplicate groups under the directory named on the command line."
  [& args]
  (if (= 1 (count args))
    (println (report (first args)))
    (println "usage: clojure -M -m sdx.dupes.main DIRECTORY")))
```

Splitting the work into `report`, which returns a string, and `-main`,
which prints it, is deliberate. A function that returns a string can be
tested by comparing strings; a function that prints has to be tested by
capturing output, which is clumsier. The only thing `-main` does that
`report` cannot is talk to the outside world, and we keep that part as
thin as possible.

Given a directory `demo` with three copies of one file and two of
another, the tool prints:

```
$ clojure -M -m sdx.dupes.main demo
demo/a.txt
demo/b.txt
demo/notes/c.txt

demo/d.txt
demo/e.txt
```

## Testing with real files

All three stages read the filesystem, and the filesystem is the enemy of
good tests: it persists between runs, it differs between machines, and a
test that leaves files behind can make the next test pass or fail for the
wrong reasons. The tests for this chapter, in
`test/sdx/dupes/`, deal with this through one helper in
`sdx.dupes.fixture`:

```clojure
(defn with-files
  "Create a temporary directory holding `files` (a map from relative path
   to string contents), call `f` with the directory's path, and delete
   the directory afterwards even if `f` throws."
  [files f]
  (let [dir (.toFile (Files/createTempDirectory "dupes" (make-array FileAttribute 0)))]
    (try
      (doseq [[name contents] files]
        (let [target (io/file dir name)]
          (io/make-parents target)
          (spit target contents)))
      (f (.getPath dir))
      (finally
        (delete-tree dir)))))
```

`with-files` takes a map describing the files a test wants and a function
to run once they exist. It creates the files in a fresh temporary
directory that the operating system guarantees nobody else is using,
calls the function, and deletes everything in a `finally` block so that
the clean-up happens even when the test throws. The test itself never
sees a path it did not ask for:

```clojure
(def sample
  "A small tree with three sets of duplicates and one unique file."
  {"a.txt"     "hello"
   "b.txt"     "hello"
   "sub/c.txt" "hello"
   "d.txt"     "world"
   "e.txt"     "world"
   "f.txt"     "unique"
   "empty1"    ""
   "empty2"    ""})
```

```clojure
(deftest finding-duplicates-by-hash
  (fx/with-files fx/sample
    (fn [dir]
      (is (= fx/sample-groups
             (fx/relative dir (hash/duplicate-groups dir))))
      (testing "all three stages agree"
        (is (= (brute/duplicate-groups dir)
               (naive/duplicate-groups dir)
               (hash/duplicate-groups dir)))))))
```

The second assertion is the most valuable test in the chapter. The
brute-force version is slow but transparently correct; the hash version
is fast but relies on an argument about probabilities. Checking that they
agree on the same input turns the slow version into a specification for
the fast one. Whenever you replace a simple algorithm with a clever one,
keep the simple one around as a test oracle.

The hash itself is tested against published values rather than against
our own output, because a test that only checks that a function agrees
with itself cannot catch a function that is consistently wrong:

```clojure
(deftest known-digests
  (testing "published test vectors for SHA-256"
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (hash/sha256 (.getBytes ""))))
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (hash/sha256 (.getBytes "abc"))))))
```

The remaining tests cover the small pieces on their own: that `pairs`
produces 4,950 pairs from 100 items, that the naive hash treats a byte of
200 as 200 rather than -56, that `file-sha256` agrees with `sha256` on a
file larger than its buffer, and that `-main` prints a usage line when
given the wrong number of arguments. Small pieces with small tests are
what made the stages easy to assemble from one another.

## Summary

- The cost of an algorithm is best described by how it grows with the
  input. Comparing every pair is O(n²): ten times the files means a
  hundred times the work.
- A hash function maps data to a small label such that equal data gets
  equal labels. Different labels prove difference; equal labels only
  suggest sameness, so a weak hash needs a follow-up comparison.
- A good hash is deterministic, has many possible values, spreads inputs
  evenly, sends similar inputs far apart, and is fast. SHA-256 has these
  properties so strongly that we can treat equal hashes as equal files.
- With a trustworthy hash, finding duplicates is `group-by`: O(n) in the
  number of files, and each file is read exactly once.
- Keep mutable Java objects such as `MessageDigest` and byte buffers
  inside one function and return immutable values from it.
- Tests that touch the filesystem should build what they need in a
  temporary directory and delete it in a `finally` block.
- When replacing a simple algorithm with a faster one, keep the simple
  one as a test oracle and check that they agree.

## Exercises

1. **Belt and braces.** Change `sdx.dupes.hash/duplicate-groups` so that
   files sharing a hash are also compared byte for byte before being
   reported, reusing `sdx.dupes.brute`. Measure how many comparisons this
   adds on a directory with a few hundred files and a handful of
   duplicates.

2. **Size first.** Two files of different sizes cannot be duplicates, and
   asking the operating system for a file's size is far cheaper than
   reading it. Add a stage that groups files by size and hashes only
   those that share a size with another file. How many files does this
   save hashing in a typical directory of your own?

3. **Read once.** `sdx.dupes.naive/duplicate-groups` reads every file
   twice: once to hash it and once inside `brute/duplicate-pairs`.
   Restructure it so that each file is read once without giving up the
   separation between bucketing and comparing.

4. **A better naive hash.** Replace the byte sum with a hash that
   multiplies the running total by a prime before adding each byte, still
   reducing modulo 13. Does `"listen"` still collide with `"silent"`?
   Write a test that generates a few thousand short strings and reports
   the largest bucket for both hashes.

5. **Streaming equality.** `same-bytes?` needs both files in memory.
   Write a version that compares two files chunk by chunk through input
   streams and stops at the first difference. When is it faster than
   hashing both files, and when is it slower?

6. **Relative paths.** The report prints paths exactly as `file-seq`
   produced them. Change `report` to print paths relative to the
   directory given on the command line, and decide what should happen
   when the user passes an absolute path.

7. **Counting the cost.** Add an optional counter to `duplicate-pairs`
   so that a test can check exactly how many comparisons were made. You
   will need an atom; explain in a comment why a pure function cannot
   report this information on its own, and whether the counter should be
   part of the return value instead.

8. **Which files to skip.** Real duplicate finders ignore things like
   `.git` directories and files the user cannot read. Add a predicate
   argument to `list-files` that decides which files to include, and
   write tests that show it being used to skip hidden files.

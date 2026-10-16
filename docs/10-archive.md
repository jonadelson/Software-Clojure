---
title: "A File Archiver"
nav_order: 10
lede: "A backup tool that stores each distinct file once, names it by its contents, and can put everything back."
---

Everyone who has lost work to a failed disk or a careless `rm` knows why
backups matter, and most of us have also discovered that a backup is only
useful if it was made recently, does not take up more space than the
original, and can actually be restored. This chapter builds a small tool
that meets those three requirements. It walks a directory, copies every
file it has not seen before into a backup folder, and writes a dated list
of what was there. A second command reads one of those lists and
recreates the files.

Along the way we meet three ideas that show up in version control
systems, package managers, and container images. **Content-addressed
storage** names a piece of data by a hash of its contents rather than by
where it came from, which makes duplicates vanish and corruption
detectable. **Idempotency** is the property that doing something twice
has the same effect as doing it once, which is what lets us run a backup
every hour without a second thought. And **designing for testability**
means noticing which parts of a program depend on the outside world, in
this case the clock and the file system, and arranging for tests to
control them.

## Naming files by what is in them

If two files have the same contents, we only want to store one copy.
Comparing every file against every other would work but is slow, as we
saw in [the chapter on duplicate files](../03-dupes/). The better
approach is the same one we used there: compute a **hash** of each file,
a fixed-size number derived from its bytes, and treat files with equal
hashes as equal. We use SHA-256, which produces 256 bits and is designed
so that finding two different inputs with the same output is, for all
practical purposes, impossible.

The JDK provides SHA-256 through `java.security.MessageDigest`. We feed
it the file in chunks so that a large file does not have to fit in
memory, then render the 32 result bytes as 64 hexadecimal characters:

```clojure
(defn hex
  "Render a byte array as a lower-case hexadecimal string."
  [^bytes bs]
  (apply str (map #(format "%02x" %) bs)))

(defn sha256
  "The SHA-256 digest of everything readable from `in`, as 64 hex characters."
  [^InputStream in]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (loop []
      (let [n (.read in buffer)]
        (when-not (neg? n)
          (.update digest buffer 0 n)
          (recur))))
    (hex (.digest digest))))

(defn hash-file
  "The SHA-256 digest of a file's contents."
  [file]
  (with-open [in (io/input-stream file)]
    (sha256 in)))
```

The `loop` in `sha256` is the standard shape for reading a Java stream:
`.read` fills the buffer and returns how many bytes it put there, or -1
at the end. The `^bytes` and `^InputStream` annotations are **type
hints**. They do not change what the code does; they tell the compiler
which Java class to expect so that it can call `.read` and `.update`
directly instead of looking them up by reflection at run time, which
matters in a loop that runs once per eight kilobytes. `hash-file` uses
`with-open` so that the stream is closed whether or not hashing
succeeds.

Why hexadecimal? Because the hash is going to be a file name. Raw bytes
would include characters that file systems reject, and base-64 includes
`/`. Sixty-four lower-case hex characters are safe everywhere.

The first thing to test is that our SHA-256 agrees with everyone else's.
The digest of the five bytes `hello` is published in enough places that
we can hard-code it, and the digest of nothing at all is a useful second
check because it catches an implementation that forgets to handle an
empty stream:

```clojure
(def hello-hash "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
(def empty-hash "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(deftest hashing-matches-the-standard
  (is (= hello-hash (b/sha256 (io/input-stream (.getBytes "hello")))))
  (is (= empty-hash (b/sha256 (io/input-stream (byte-array 0))))))
```

`sha256` takes a stream rather than a file precisely so that this test
can feed it bytes from memory. A function that insisted on a file would
need the test to create one first, and the thing being tested, the
arithmetic of hashing, has nothing to do with files.

## Finding what to back up

The backup covers everything under a source directory. For each file we
need two things: its path *relative to that directory*, because that is
what we will have to recreate on restore, and the `java.io.File` itself,
so that we can read it.

```clojure
(defn relative-path
  "The path of `file` relative to `root`, using / as the separator
   whatever the operating system prefers."
  [^File root ^File file]
  (-> (.relativize (.toPath root) (.toPath file))
      str
      (str/replace File/separator "/")))

(defn files-under
  "Every regular file below `root`, as maps {:path relative-path :file File},
   sorted by path so that the result does not depend on directory order."
  [root]
  (let [root (io/file root)]
    (->> (file-seq root)
         (filter #(.isFile ^File %))
         (map (fn [f] {:path (relative-path root f) :file f}))
         (sort-by :path))))

(defn hash-tree
  "Hash every file below `root`: a seq of {:path :file :hash}."
  [root]
  (map #(assoc % :hash (hash-file (:file %))) (files-under root)))
```

`file-seq` walks a directory tree and returns every file and directory
in it; we keep only the regular files. The `relativize` method of
`java.nio.file.Path` does the path arithmetic for us, and we normalize
the separator to `/` so that a manifest written on Windows can be read on
Linux.

The `sort-by` deserves a word. The order in which a file system lists a
directory is not specified and can differ between runs, let alone between
machines. Sorting costs almost nothing and means that `files-under`
returns the same vector every time for the same tree, which in turn means
that two manifests of the same tree are byte-for-byte identical. We will
rely on that when we test.

```clojure
(deftest listing-files-with-relative-paths
  (with-temp-dir [dir]
    (write! dir "b.txt" "2")
    (write! dir "sub/a.txt" "1")
    (write! dir "a.txt" "1")
    (is (= ["a.txt" "b.txt" "sub/a.txt"] (map :path (b/files-under dir))))))
```

This test, like all the others in `test/sdx/archive/backup_test.clj`,
creates its files in a temporary directory using the `with-temp-dir`
macro from [the previous chapter](../09-protocols/), which deletes the
directory and everything in it when the test is done. The `write!`
helper makes a file and any parent directories it needs. Creating a
fresh directory per test, rather than sharing fixture files, means tests
cannot interfere with one another and can be run in any order.

## What time is it?

Each backup writes a manifest, and we want manifests to be named so that
they sort in the order they were made. A timestamp does that, and a
timestamp means asking the clock.

```clojure
(defn now
  "The current instant according to the system clock."
  []
  (Instant/now))

(def ^:private stamp-format
  (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") ZoneOffset/UTC))

(defn timestamp
  "Format an instant as a string that sorts chronologically and is safe
   in a file name: 2026-01-16T09:30:00Z becomes 20260116-093000."
  [^Instant instant]
  (.format stamp-format instant))
```

Notice the split. `now` is the only function that touches the real
clock, and it is as small as a function can be. `timestamp` is a pure
function from an instant to a string, so it can be tested with a fixed
instant and no trickery at all:

```clojure
(deftest timestamps-sort-and-fit-in-file-names
  (is (= "20240102-030405" (b/timestamp (Instant/parse "2024-01-02T03:04:05Z")))))
```

We format in UTC on purpose. A backup directory may be read on a machine
in a different time zone, or after daylight-saving time has changed, and
a name that depends on local time would then sort wrongly. Colons are
left out because Windows does not allow them in file names.

The question is how `backup`, which needs the current time, should get
it. There are two reasonable answers, and the code supports both.

The first is to make the clock a **parameter**: `backup` accepts an
options map that may contain a `:clock`, a function of no arguments that
returns an instant, and uses `now` when none is given. A test passes
`(constantly some-instant)` and the code under test never knows the
difference. This is the approach we take for the backup directory too:
rather than reading a configured location from somewhere, `backup` is
told where to write. The dependency is visible in the function's
signature, which makes it obvious to a reader that time is an input.

The second is to leave `backup` calling `now` and have the test replace
`now` with `with-redefs`, as described in [the previous
chapter](../09-protocols/). This needs no extra parameter and works for
any function, including ones we did not write. The cost is that the
dependency is hidden: nothing in `backup`'s signature says that it
depends on the clock, and a reader has to find the call to `now` to know
that a test might want to control it.

Parameters are the better default for dependencies you own and expect to
vary, and `with-redefs` is the tool for dependencies that are baked in.
Since we own this one, `backup` takes the clock as a parameter, and the
test file shows both techniques so that you can compare them side by
side:

```clojure
(def fixed-clock
  (constantly (Instant/parse "2024-01-02T03:04:05Z")))

(deftest backing-up-with-the-clock-redefined
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")]
      (write! src "a.txt" "hello")
      (with-redefs [b/now fixed-clock]
        (is (= "20240102-030405.csv" (.getName (:manifest (b/backup src dst)))))))))
```

## Storing each content once

Now the backup itself. The backup directory holds two kinds of file:
content files, named by their hash, and manifests, named by their
timestamp. A content file is written only if no file with that name
already exists:

```clojure
(defn stored-file
  "Where the content with digest `hash` lives inside `backup-dir`."
  [backup-dir hash]
  (io/file backup-dir hash))

(defn store!
  "Copy the file in `entry` into `backup-dir` under its hash, unless a
   file with that hash is already there. Returns true if a copy was made."
  [backup-dir {:keys [file hash]}]
  (let [target (stored-file backup-dir hash)]
    (if (.exists ^File target)
      false
      (do (io/copy file target)
          true))))
```

This is content-addressed storage in a dozen lines. Because the name of
a stored file is determined entirely by its contents, two source files
with the same contents map to the same target, and the second one finds
the target already present. A source file that has not changed since the
last backup maps to the same target it did last time, and is not copied
again. The `.exists` check is what makes `store!` idempotent: calling it
a hundred times does what calling it once does, and the ninety-nine extra
calls are cheap because they copy nothing.

> **Where else this idea lives.**
> Git stores every version of every file under the hash of its contents,
> which is why a repository with ten thousand commits that each touch
> one file is not ten thousand times the size of the project. Package
> managers like Nix and container systems like Docker use the same trick
> to share layers between images. Whenever you see a long hexadecimal
> name in a cache directory, this is probably what is going on.

The manifest is the part that remembers *where* each content came from.
Its format is as simple as we could make it, one line per file with the
path and the hash separated by a comma, because a format that a person
can read with `cat` and repair with a text editor is worth a great deal
in a backup tool:

```clojure
(defn manifest-text
  "Render hashed entries as manifest text: one `path,hash` line per file."
  [entries]
  (apply str (map (fn [{:keys [path hash]}] (str path "," hash "\n")) entries)))
```

With those pieces, `backup` is mostly plumbing:

```clojure
(defn backup
  "Back up every file below `source-dir` into `backup-dir`.

   Each distinct file content is copied once, under its hash. A manifest
   named after the current time is written listing every file's path and
   hash. Returns {:manifest File :entries [...] :copied n}.

   The optional map may supply a :clock, a function of no arguments
   returning a java.time.Instant, so that callers (and tests) can decide
   what time it is."
  ([source-dir backup-dir]
   (backup source-dir backup-dir {}))
  ([source-dir backup-dir {:keys [clock] :or {clock now}}]
   (let [backup-dir (io/file backup-dir)
         entries (vec (hash-tree source-dir))
         manifest (io/file backup-dir (str (timestamp (clock)) ".csv"))]
     (.mkdirs backup-dir)
     (let [copied (reduce (fn [n entry] (if (store! backup-dir entry) (inc n) n))
                          0 entries)]
       (spit manifest (manifest-text entries))
       {:manifest manifest :entries entries :copied copied}))))
```

The `:or {clock now}` in the destructuring form supplies the default: if
the options map has no `:clock`, the real one is used. The `reduce`
counts how many files were actually copied, which costs nothing to
compute and turns out to be the most useful thing the function can
return, because it is how a test (or a person) tells the difference
between a first backup and a repeat. The return value also includes the
manifest's `File` and the hashed entries, so that a caller never has to
go looking for what `backup` just did.

Here is the central test. Three source files, two of which have the same
contents, and a fixed clock:

```clojure
(deftest backing-up-with-the-clock-as-a-parameter
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")]
      (write! src "a.txt" "hello")
      (write! src "docs/b.txt" "hello")
      (write! src "docs/c.txt" "")
      (let [{:keys [manifest entries copied]} (b/backup src dst {:clock fixed-clock})]
        (testing "the manifest is named after the clock"
          (is (= "20240102-030405.csv" (.getName manifest))))
        (testing "every file is listed, but identical contents are stored once"
          (is (= 3 (count entries)))
          (is (= 2 copied))
          (is (= #{"a.txt" "docs/b.txt" "docs/c.txt"} (set (map :path entries))))
          (is (.exists (io/file dst hello-hash)))
          (is (.exists (io/file dst empty-hash))))
        (testing "the manifest text records path and hash"
          (is (= (str "a.txt," hello-hash "\n"
                      "docs/b.txt," hello-hash "\n"
                      "docs/c.txt," empty-hash "\n")
                 (slurp manifest))))))))
```

The last assertion compares the whole manifest against an exact string.
That is only possible because every input to the manifest is under the
test's control: the file contents (so the hashes are known), the file
order (sorted), and the time (fixed). Pinning down the inputs is what
"designing for testability" means in practice.

A second test runs the backup twice and checks that the second run
copies nothing but still leaves its own manifest, and that a changed
file is stored under its new hash:

```clojure
(deftest backing-up-twice-copies-nothing-new
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")
          clock-1 (constantly (Instant/parse "2024-01-01T00:00:00Z"))
          clock-2 (constantly (Instant/parse "2024-01-02T00:00:00Z"))]
      (write! src "a.txt" "hello")
      (is (= 1 (:copied (b/backup src dst {:clock clock-1}))))
      (is (= 0 (:copied (b/backup src dst {:clock clock-2}))))
      (testing "but each run leaves its own manifest"
        (is (= #{"20240101-000000.csv" "20240102-000000.csv" hello-hash}
               (set (map #(.getName %) (.listFiles dst))))))
      (testing "a changed file is stored under its new hash"
        (write! src "a.txt" "goodbye")
        (is (= 1 (:copied (b/backup src dst {:clock clock-2}))))
        (is (str/includes? (slurp (io/file dst "20240102-000000.csv"))
                           (b/hash-file (io/file src "a.txt"))))))))
```

Two different clocks are needed because a manifest is named by its
timestamp alone, so two backups in the same second would overwrite one
another's manifest. That is a real limitation of the design, and one of
the exercises asks you to fix it.

## Restoring

A backup that cannot be restored is a waste of disk. The restore side
lives in its own namespace, `sdx.archive.restore`, and starts by reading
a manifest back into the same shape that `hash-tree` produced:

```clojure
(defn parse-manifest
  "Turn manifest text back into a seq of {:path :hash}. The hash is
   everything after the last comma, so paths may contain commas."
  [text]
  (->> (str/split-lines text)
       (remove str/blank?)
       (map (fn [line]
              (let [i (str/last-index-of line ",")]
                {:path (subs line 0 i)
                 :hash (subs line (inc i))})))))
```

A comma is a legal character in a file name, so splitting each line at
the *first* comma would break on a file called `notes, draft.txt`. We
know the hash never contains a comma and always comes last, so we split
at the *last* comma instead. The test checks exactly that case:

```clojure
(deftest parsing-a-manifest
  (is (= [{:path "a.txt" :hash "aaa"} {:path "x,y.txt" :hash "bbb"}]
         (r/parse-manifest "a.txt,aaa\nx,y.txt,bbb\n")))
  (is (= [] (r/parse-manifest ""))))
```

Because manifest names sort chronologically, finding the newest one is
a matter of sorting the names:

```clojure
(defn manifests
  "Every manifest in `backup-dir`, oldest first."
  [backup-dir]
  (->> (.listFiles (io/file backup-dir))
       (filter #(str/ends-with? (.getName ^File %) ".csv"))
       (sort-by #(.getName ^File %))))

(defn latest-manifest
  "The most recent manifest in `backup-dir`, or nil if there is none."
  [backup-dir]
  (last (manifests backup-dir)))
```

Before copying a stored file back, we check that it is really there and
that it is still what its name says it is:

```clojure
(defn check-stored
  "The stored file for `hash`, after making sure it exists and that its
   contents still match its name. Throws ex-info otherwise."
  [backup-dir hash]
  (let [stored (b/stored-file backup-dir hash)]
    (cond
      (not (.exists ^File stored))
      (throw (ex-info "missing from backup" {:hash hash}))

      (not= hash (b/hash-file stored))
      (throw (ex-info "stored file is corrupt" {:hash hash :actual (b/hash-file stored)}))

      :else stored)))
```

This is the second payoff of content addressing. A stored file carries
its own checksum in its name, so a disk error, a truncated copy, or a
stray edit can be detected without any extra bookkeeping. Rehashing
every file on restore costs as much as the original backup did, which is
a price worth paying in a tool whose whole purpose is to be right when
it counts. `ex-info` lets us attach the expected and actual hashes to
the exception so that whoever catches it can say something useful.

The restore itself walks the manifest and copies each file into place:

```clojure
(defn restore
  "Recreate every file listed in `manifest` under `target-dir`, taking
   the contents from `backup-dir`. Returns {:restored n}."
  [backup-dir manifest target-dir]
  (let [entries (parse-manifest (slurp manifest))]
    (doseq [{:keys [path hash]} entries]
      (let [stored (check-stored backup-dir hash)
            target (io/file target-dir path)]
        (io/make-parents target)
        (io/copy stored target)))
    {:restored (count entries)}))
```

`restore` takes the target directory as a parameter for the same reason
`backup` does. Restoring over the original location is the common case
in real life but the worst possible case in a test, so the test restores
into a fresh directory and compares:

```clojure
(deftest round-trip
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")
          out (io/file root "restored")]
      (write! src "a.txt" "hello")
      (write! src "docs/b.txt" "hello")
      (write! src "docs/c.txt" "other")
      (let [{:keys [manifest]} (b/backup src dst {:clock (clock-at "2024-01-01T00:00:00Z")})]
        (is (= {:restored 3} (r/restore dst manifest out)))
        (is (= "hello" (slurp (io/file out "a.txt"))))
        (is (= "hello" (slurp (io/file out "docs/b.txt"))))
        (is (= "other" (slurp (io/file out "docs/c.txt"))))
        (testing "the restored tree has exactly the files that were backed up"
          (is (= (map :path (b/files-under src))
                 (map :path (b/files-under out)))))))))
```

Another test in `test/sdx/archive/restore_test.clj` makes two backups
with different contents and checks that restoring from the older manifest
gives the older file, which is the point of keeping every manifest: the
backup directory is not just the latest snapshot but the whole history,
at the cost of one small text file per run. And a third test deliberately
damages the store to make sure `check-stored` notices:

```clojure
(deftest a-damaged-store-is-noticed
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")
          out (io/file root "restored")]
      (write! src "a.txt" "hello")
      (let [{:keys [manifest entries]} (b/backup src dst {:clock (clock-at "2024-01-01T00:00:00Z")})
            stored (b/stored-file dst (:hash (first entries)))]
        (spit stored "tampered")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"corrupt"
                              (r/restore dst manifest out)))
        (.delete stored)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing"
                              (r/restore dst manifest out)))))))
```

Testing the failure paths is at least as important as testing the happy
one. A backup tool that silently restores garbage is worse than one that
does not work at all, because it will not be found out until the day it
is needed.

## A command-line tool

The two operations are tied together by a small `-main` in
`sdx.archive.main`:

```clojure
(defn -main
  "Dispatch on the first argument and print a one-line summary."
  [& [command & args]]
  (case command
    "backup"
    (let [[source backup-dir] args
          {:keys [manifest entries copied]} (b/backup source backup-dir)]
      (println (format "%d files, %d new, manifest %s"
                       (count entries) copied (.getName manifest))))

    "restore"
    (let [[backup-dir target manifest] args
          manifest (if manifest
                     (java.io.File. manifest)
                     (r/latest-manifest backup-dir))
          {:keys [restored]} (r/restore backup-dir manifest target)]
      (println (format "restored %d files from %s" restored (.getName manifest))))

    (usage)))
```

Here it is run on a directory holding three files, two of them
identical:

```
$ clojure -M -m sdx.archive.main backup notes backups
3 files, 2 new, manifest 20261016-005223.csv
$ ls backups
20261016-005223.csv
5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03
7e4fa2eb8c7ac089739d5defc4489fad68a100d92082ca35c6b40a4524821f87
$ cat backups/20261016-005223.csv
a.txt,5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03
b.txt,7e4fa2eb8c7ac089739d5defc4489fad68a100d92082ca35c6b40a4524821f87
drafts/copy.txt,5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03
$ clojure -M -m sdx.archive.main restore backups restored
restored 3 files from 20261016-005223.csv
```

Everything the command-line layer does is print. The functions it calls
return data, and the tests work on that data; `-main` is the one place
where the program meets the outside world and it is deliberately too
thin to need testing of its own. That is the same split we made between
`now` and `timestamp`, applied to the whole program.

## Summary

- Content-addressed storage names a piece of data by the hash of its
  contents. Identical files collapse into one, unchanged files are never
  copied twice, and every stored file carries its own checksum.
- Hashing with SHA-256 is a few lines of Java interop; reading the
  stream in chunks keeps memory use constant however large the file is.
- An operation is idempotent when doing it again changes nothing. A
  backup built from "copy if absent" steps can be run as often as you
  like, which is what a backup tool needs.
- Keep the part that touches the outside world as small as possible
  (`now`) and make everything else a pure function of its inputs
  (`timestamp`, `manifest-text`, `parse-manifest`).
- Pass the things a test needs to control, such as the clock and the
  directories, as parameters. Reach for `with-redefs` when a dependency
  cannot be made a parameter.
- Return data from the functions that do the work and leave printing to
  the command-line layer, so that tests can check results precisely.
- Test the failure paths. A tool that fails loudly is worth more than
  one that fails quietly, especially when it is the last line of defense.

## Exercises

1. **Same-second backups.** Two backups in the same second overwrite
   each other's manifest. Change the naming scheme so that this cannot
   happen, without giving up the property that manifest names sort
   chronologically.

2. **Verify.** Write `(verify backup-dir)` that rehashes every content
   file in the backup directory and reports those whose contents no
   longer match their names. Should it also report content files that no
   manifest refers to?

3. **Pruning.** After many backups, the store will contain content that
   only old manifests mention. Write a function that deletes a manifest
   and then removes any content file no remaining manifest refers to.
   What could go wrong if a backup were running at the same time?

4. **Incremental listing.** `hash-tree` rehashes every file on every
   run. Use each file's size and modification time to skip the hashing
   when neither has changed since the last manifest. What do you have to
   store to make that work, and what is the risk?

5. **Excluding files.** Add an option to `backup` that takes a predicate
   on relative paths and skips any file for which it returns true. Use it
   to skip `.git` directories and editor backup files.

6. **Comparing manifests.** Write `(diff manifest-a manifest-b)` that
   reports which files were added, removed, or changed between two
   backups. The hashes make "changed" easy to detect; make sure a file
   that was renamed shows up sensibly.

7. **Clock as a protocol.** Replace the `:clock` function with a
   `Clock` protocol that has a `now` method, and write a `FixedClock`
   record for tests. Is the extra structure worth it compared to passing
   a function?

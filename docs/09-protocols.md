---
title: "Protocols"
nav_order: 9
lede: "Three ways to hook into the language: swapping functions out, teaching new types to be sequences, and writing macros that clean up after themselves."
---

Most of the tools in this book are built on top of the language. This
chapter is about reaching down into it. Every language has a few seams
where a program can change how the language itself behaves: what a name
refers to, what counts as a collection, what happens before and after a
block of code runs. Languages that expose those seams let us write tools
that would otherwise need a modified compiler.

Clojure exposes three that we will use again and again. **Vars** are the
boxes that hold global definitions, and because they are ordinary objects
we can look inside them and change what they hold, which is how tests
replace a slow or dangerous dependency with a stand-in. The **seq
abstraction** is a single small interface that every collection function
is written against, so a type of our own that implements it gets the
whole library for free. And **macros** let us write code that runs at
compile time and rewrites the code around it, which is how `with-open`
guarantees a file is closed no matter how the block that uses it exits.
We will build a small example of each and, along the way, see why the
pieces fit together the way they do.

## Replacing a function while a test runs

Here is a function that converts money. It depends on an exchange rate
that, in a real program, would come from a web service:

```clojure
(defn exchange-rate
  "The exchange rate from currency `from` to currency `to`.

   In a real program this would call a web service. Here it refuses to
   run at all, which makes the point that tests must never reach it."
  [from to]
  (throw (ex-info "no network access in this chapter" {:from from :to to})))

(defn convert
  "Convert `amount` of currency `from` into currency `to` at today's rate."
  [amount from to]
  (* amount (exchange-rate from to)))
```

We cannot test `convert` as it stands, because every call ends up in
`exchange-rate` and `exchange-rate` ends up throwing. Even if it worked,
a test whose answer depended on today's rate would never pass twice in a
row. What we want is for `convert` to call *something else* while the
test is running, without changing `convert`.

Clojure's `with-redefs` does exactly that:

```clojure
(with-redefs [exchange-rate (constantly 2)]
  (convert 10 "USD" "EUR"))
;; => 20
```

Inside the block, `exchange-rate` is a function that returns 2 no matter
what it is asked. Outside the block, it is the real thing again. The
test file for this section, `test/sdx/protocols/mock_test.clj`, checks
both halves of that promise:

```clojure
(deftest with-redefs-replaces-a-dependency
  (with-redefs [m/exchange-rate (constantly 2)]
    (is (= 20 (m/convert 10 "USD" "EUR"))))
  (testing "the replacement is gone once the block ends"
    (is (thrown? clojure.lang.ExceptionInfo (m/convert 10 "USD" "EUR")))))
```

A replacement like this is usually called a **mock**: a stand-in for a
real component that behaves predictably so that the code around it can be
tested in isolation. Mocking the clock is just as common as mocking the
network. `receipt` below reads the time, and the test pins both the rate
and the time so that it can compare against an exact string:

```clojure
(defn current-time-ms
  "Milliseconds since the Unix epoch, read from the system clock."
  []
  (System/currentTimeMillis))

(defn receipt
  "A one-line receipt showing the converted amount and when it was computed."
  [amount from to]
  (format "%.2f %s at %d" (double (convert amount from to)) to (current-time-ms)))
```

```clojure
(deftest a-fixed-clock-makes-output-predictable
  (with-redefs [m/exchange-rate (fn [_ _] 1.5)
                m/current-time-ms (constantly 1700000000000)]
    (is (= "15.00 EUR at 1700000000000" (m/receipt 10 "USD" "EUR")))))
```

### What a var is

How can `with-redefs` change what `convert` calls without touching
`convert`? The answer is that `convert` never held a reference to the
function `exchange-rate`. It holds a reference to a **var**, and looks
inside the var every time it makes the call.

When we write `(defn convert ...)`, Clojure creates a var named
`sdx.protocols.mock/convert`, stores the function in it, and records the
var in the namespace. A symbol in code that is not a local resolves to a
var, and calling it means dereferencing the var to get the current
function. The var itself is an ordinary value that we can get hold of
with `#'` (or the longer `(var ...)`) and look inside with `deref`:

```clojure
#'convert
;; => #'sdx.protocols.mock/convert
(deref #'convert)
;; => #object[sdx.protocols.mock$convert ...]
(meta #'convert)
;; => {:ns #object[clojure.lang.Namespace ...], :name convert,
;;     :arglists ([amount from to]), :line 25, :doc "Convert `amount` ..."}
```

The function is the var's **root binding**. `with-redefs` saves the root
binding, replaces it, runs the body, and puts the old one back. We can
build that ourselves out of two operations: `deref`, which reads the root,
and `alter-var-root`, which applies a function to it and stores the
result.

```clojure
(defn with-replacement
  "Point var `v` at `value` while calling `thunk` (a function of no
   arguments), then restore the var's old value even if `thunk` throws.
   Returns whatever `thunk` returned."
  [v value thunk]
  (let [old (deref v)]
    (alter-var-root v (constantly value))
    (try
      (thunk)
      (finally
        (alter-var-root v (constantly old))))))
```

```clojure
(with-replacement #'exchange-rate (constantly 3)
  (fn [] (convert 10 "USD" "EUR")))
;; => 30
```

Two details matter. First, `alter-var-root` takes a function, not a
value, so we wrap the value in `constantly` to get a function that ignores
the old root and returns the new one. Second, the restore is in a
`finally` clause, so it runs whether the thunk returns or throws. A
replacement that leaked out of a failing test would make every later
test wrong in a way that would be very hard to track down, so the test
checks the failure path explicitly:

```clojure
(deftest home-made-replacement
  (is (= 30 (m/with-replacement #'m/exchange-rate (constantly 3)
              (fn [] (m/convert 10 "USD" "EUR")))))
  (testing "the old value comes back even when the body throws"
    (is (thrown? ArithmeticException
                 (m/with-replacement #'m/exchange-rate (constantly 3)
                   (fn [] (/ 1 0)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no network"
                          (m/convert 10 "USD" "EUR")))))
```

The one awkward thing about `with-replacement` is that the caller has to
wrap the body in `(fn [] ...)`. The real `with-redefs` does not require
that. We will fix it at the end of the chapter, once we have macros.

> **Direct linking.**
> Looking a function up through its var on every call costs a little
> time, and Clojure can be told to skip it: with *direct linking* turned
> on, a call to a function in another namespace compiles to a direct
> reference to the function, and `with-redefs` of that var has no effect
> on the compiled caller. Direct linking is off by default, and the
> dynamic lookup is the price of being able to redefine things at the
> REPL and in tests.

### A replacement that keeps notes

Sometimes a test needs to know not just what the code under test
returned but what it *asked for*. A replacement that records its
arguments is often called a spy. Ours wraps any function and keeps a
vector of argument lists:

```clojure
(defn recording
  "Wrap `f` so that every call is remembered. Returns a map with the
   wrapper under :fn and an atom of argument vectors under :calls."
  [f]
  (let [calls (atom [])]
    {:calls calls
     :fn (fn [& args]
           (swap! calls conj (vec args))
           (apply f args))}))
```

This is the first place in the chapter that uses an **atom**, so it is
worth saying what one is and why it is needed. An atom is a mutable
reference to an immutable value. `swap!` applies a function to the
current value and stores the result; `deref` (or `@`) reads it. Almost
everything in this book is a pure function, but a record of "every call
that has happened so far" is state by definition: it changes each time
the wrapper is called and must survive between calls. The atom is the
smallest possible container for that state, and it is private to the
closure that `recording` returns.

```clojure
(deftest recording-what-was-asked
  (let [rate (m/recording (constantly 2))]
    (with-redefs [m/exchange-rate (:fn rate)]
      (m/convert 10 "USD" "EUR")
      (m/convert 5 "GBP" "JPY"))
    (is (= [["USD" "EUR"] ["GBP" "JPY"]] @(:calls rate)))))
```

## Teaching a new type to be a sequence

The second seam is the one that makes Clojure's collection library so
small. `map`, `filter`, `reduce`, `count`, `into`, destructuring, and
nearly a hundred other functions do not know anything about vectors or
lists or strings. Each of them starts by calling `seq` on its argument
and then works with whatever comes back. `seq` returns a sequence view of
its argument, or `nil` if there is nothing to see:

```clojure
(seq "abc")
;; => (\a \b \c)
(seq {:a 1 :b 2})
;; => ([:a 1] [:b 2])
(seq (java.util.ArrayList. [1 2]))
;; => (1 2)
(seq [])
;; => nil
```

That means there are two ways to join the party. We can produce a seq
directly, or we can make a type of our own that knows how to produce one
when asked. We will do both.

### Lazy sequences by hand

A **lazy sequence** is one whose elements are computed only when
something asks for them. `lazy-seq` takes a body of code and returns a
seq that will run the body the first time anyone looks at it, and then
remember the result. Here is a countdown written that way:

```clojure
(defn countdown
  "The numbers from `n` down to 1, produced one at a time as they are asked for."
  [n]
  (lazy-seq
    (when (pos? n)
      (cons n (countdown (dec n))))))
```

```clojure
(countdown 3)
;; => (3 2 1)
(reduce + (countdown 3))
;; => 6
```

The recursive call to `countdown` inside the body is not a problem,
because it is inside a `lazy-seq` and so does not run until the rest of
the sequence is needed. The test file `test/sdx/protocols/seqs_test.clj`
shows that nothing happens until something asks:

```clojure
(deftest laziness-means-nothing-happens-until-asked
  (let [s (s/countdown 3)]
    (is (not (realized? s)))
    (is (= 3 (first s)))
    (is (realized? s)))
  (testing "an endless sequence is fine as long as we only take part of it"
    (is (= [7 14 21] (take 3 (s/multiples 7))))
    (is (= 35 (first (filter #(> % 30) (s/multiples 7)))))))
```

The second half of that test uses a sequence that never ends:

```clojure
(defn multiples
  "Every positive multiple of `k`, in order. This sequence never ends."
  ([k] (multiples k k))
  ([k current]
   (lazy-seq
     (cons current (multiples k (+ current k))))))
```

There is no `when` to stop the recursion, and there does not need to be,
because the recursion only goes as far as a consumer pulls it.
`(take 3 (multiples 7))` computes three elements and stops. Asking for
`(count (multiples 7))` would never return, which is the one thing to
keep in mind when working with infinite sequences: any function that has
to see the whole thing is off limits.

### Iterators: the same idea with state

Most languages offer sequences through an **iterator**: an object with a
"is there more?" question and a "give me the next one" command. An
iterator is a cursor, and a cursor has to remember its position, so an
iterator is stateful by nature. Here is one built on an atom:

```clojure
(defn iterator
  "A java.util.Iterator over `coll`: a cursor that remembers how far
   through the collection it has got."
  [coll]
  (let [remaining (atom (seq coll))]
    (reify java.util.Iterator
      (hasNext [_]
        (boolean @remaining))
      (next [_]
        (if-let [s @remaining]
          (do (reset! remaining (next s))
              (first s))
          (throw (java.util.NoSuchElementException.)))))))
```

`reify` is new. It creates an anonymous object that implements one or
more Java interfaces or Clojure protocols, with the method bodies given
inline. Each method's first parameter is the object itself, which we
ignore here with `_`. Because the atom `remaining` is in scope when
`reify` runs, the methods close over it, which is how the object gets its
private state.

```clojure
(let [it (iterator [1 2 3])]
  [(.hasNext it) (.next it) (.next it) (.next it) (.hasNext it)])
;; => [true 1 2 3 false]
```

Compare this with `countdown`. The lazy seq is a value: we can hand it
to two consumers and each sees the whole thing. The iterator is a
process: once a value has been pulled through `.next` it is gone, and
two consumers sharing one iterator would each see half of the elements.
That is why Clojure's collection functions are built on seqs, and why
the usual thing to do with an iterator from a Java library is to turn it
into a seq as quickly as possible:

```clojure
(defn iterator->seq
  "A lazy seq that pulls elements from iterator `it` as they are needed."
  [^java.util.Iterator it]
  (lazy-seq
    (when (.hasNext it)
      (cons (.next it) (iterator->seq it)))))
```

```clojure
(iterator->seq (iterator "abc"))
;; => (\a \b \c)
```

Because `lazy-seq` caches each element the first time it is computed, the
resulting seq can be walked as many times as we like, even though the
iterator underneath can only be walked once. (Clojure ships with this
function under the name `iterator-seq`; ours exists to show that there
is nothing to it.)

### A type of our own

Now for the other direction. Suppose we have a binary search tree: each
node holds a value, everything smaller in the left subtree, everything
larger in the right. We would like `(map inc tree)` and `(reduce + tree)`
to work on it. They will, as soon as the tree can answer `seq`.

A **deftype** defines a new Java class with named fields and whatever
interfaces we choose to implement. It is the lower-level cousin of
`defrecord`: a record is already a map, and so `seq` of a record gives
its key-value pairs, which is not what we want here. A deftype promises
nothing and so lets us decide what everything means:

```clojure
(declare in-order)

(deftype Tree [value left right]
  clojure.lang.Seqable
  (seq [this] (in-order this))

  clojure.lang.Counted
  (count [this] (count (in-order this)))

  clojure.lang.Sequential)
```

`clojure.lang.Seqable` is the interface with the single method `seq`. It
is what every collection function checks for, so implementing it is the
whole price of admission. The other two interfaces are refinements.
`Counted` says that `count` may call our `count` method rather than
giving up, and `Sequential` is a marker interface with no methods at all
that tells `nth`, and therefore vector-style destructuring, that it is
safe to walk the seq looking for the nth element. We found out we needed
them by leaving them out: without `Counted`, `(count tree)` throws
`count not supported on this type`; without `Sequential`, destructuring
throws `nth not supported on this type`. Each interface is one promise,
and we make only the promises we can keep.

The traversal itself is a lazy seq, which lets us write it as the
textbook recursion without building the whole list up front:

```clojure
(defn in-order
  "The values stored in `tree` from smallest to largest, as a lazy seq.
   An empty tree is nil, and its traversal is nil too."
  [^Tree tree]
  (when tree
    (lazy-seq
      (concat (in-order (.-left tree))
              (cons (.-value tree) (in-order (.-right tree)))))))
```

`(.-left tree)` reads the field `left` of the deftype instance; the dot
and dash are Clojure's syntax for Java field access. The `declare` above
the deftype lets the `seq` method refer to `in-order` before it is
defined, since the two depend on each other.

Insertion builds a new tree rather than modifying the old one. It copies
the nodes on the path from the root to the insertion point and shares
every other subtree with the original:

```clojure
(defn insert
  "A new tree holding everything in `tree` plus `v`. The old tree is
   not changed; the new one shares every subtree it did not need to rebuild."
  [^Tree tree v]
  (cond
    (nil? tree)          (Tree. v nil nil)
    (< v (.-value tree)) (Tree. (.-value tree) (insert (.-left tree) v) (.-right tree))
    (> v (.-value tree)) (Tree. (.-value tree) (.-left tree) (insert (.-right tree) v))
    :else                tree))

(defn tree
  "A search tree holding the given values."
  [& values]
  (reduce insert nil values))
```

With that, the whole library works on trees without having heard of them:

```clojure
(deftest a-tree-is-a-collection
  (let [t (s/tree 5 3 8 1 4 8)]
    (is (= [1 3 4 5 8] (seq t)))
    (is (= 5 (count t)))
    (is (= [2 4 5 6 9] (map inc t)))
    (is (= [4 8] (filter even? t)))
    (is (= 21 (reduce + t)))
    (is (= #{1 3 4 5 8} (into #{} t)))
    (let [[smallest second-smallest] t]
      (is (= [1 3] [smallest second-smallest])))))
```

Notice that `(seq t)` yields the values in sorted order even though they
were inserted in a jumble. The seq view of a type is a design decision:
we chose in-order traversal because that is the useful order for a
search tree, but a type representing a grid might choose row-major
order, and a type representing a queue might choose oldest-first. Once
the choice is made, every consumer gets it for free.

> **What a bare deftype does not do.**
> Our tree prints as `#object[sdx.protocols.seqs.Tree ...]`, and two
> trees holding the same values are not `=` to each other, because we
> did not implement `toString` or `equiv`. A record would have given us
> both, along with `assoc` and the rest of the map interface, which is
> why `defrecord` is the right default and `deftype` is for the cases,
> like this one, where the map behavior would be wrong.

## Macros: code that runs before your code

The third seam is the deepest. Consider the problem of timing a
computation. We want to write something like `(with-timer "sorting"
(sort big-list))` and have it print how long the sort took. A function
cannot do this. By the time a function is called, its arguments have
already been evaluated, so `(timer-fn "sorting" (sort big-list))` would
receive the sorted list and have nothing left to time.

A **macro** solves this because it is a function that runs at compile
time and receives its arguments *as code*, unevaluated. It returns new
code, which the compiler then compiles in place of the original call.
Here is one that times its body and returns both the value and the
elapsed time:

```clojure
(defmacro timed
  "Evaluate `body` and return {:value v :millis ms}, where ms is how
   long the evaluation took."
  [& body]
  `(let [start# (System/nanoTime)
         value# (do ~@body)]
     {:value value#
      :millis (/ (- (System/nanoTime) start#) 1e6)}))
```

Three pieces of syntax carry the whole mechanism:

- The backquote, called **syntax-quote**, builds a piece of code as data
  instead of running it. Symbols inside it are qualified with their
  namespace (`let` becomes `clojure.core/let`), so the expansion means
  the same thing wherever it lands.
- `~@body` is **unquote-splicing**: it pastes the forms the caller passed
  in, one after another, into the template. A plain `~x` (unquote) would
  paste a single form.
- `start#` and `value#` are **auto-gensyms**: syntax-quote replaces them
  with generated names that cannot collide with anything in the caller's
  code. If we had written plain `start` and the caller's body also used a
  variable called `start`, the two would silently clash.

We can see what the compiler sees by asking for the expansion:

```clojure
(macroexpand-1 '(timed (+ 1 2)))
;; => (clojure.core/let [start__168__auto__ (java.lang.System/nanoTime)
;;                       value__169__auto__ (do (+ 1 2))]
;;      {:value value__169__auto__,
;;       :millis (clojure.core// (clojure.core/- (java.lang.System/nanoTime)
;;                                               start__168__auto__)
;;                               1000000.0)})
```

The expansion is ordinary Clojure that the caller could have written
by hand. The macro just makes sure nobody has to, and that everyone who
times something does it the same way. With `timed` in hand, `with-timer`
is a small addition:

```clojure
(defmacro with-timer
  "Evaluate `body`, print how long it took under `label`, and return
   the value of `body`."
  [label & body]
  `(let [result# (timed ~@body)]
     (println (format "%s: %.1f ms" ~label (:millis result#)))
     (:value result#)))
```

```clojure
(with-timer "sleeping" (Thread/sleep 20) :done)
;; sleeping: 20.3 ms
;; => :done
```

The split between `timed` and `with-timer` is deliberate. `timed` is
easy to test because it returns data; `with-timer` is a thin layer of
presentation over it. We will see the same split again shortly.

### Cleaning up no matter what

Clojure's `with-open` opens a resource, runs some code, and closes the
resource afterwards, even if the code throws. Its expansion is a
`let` wrapped around a `try` with a `finally`. We can write the same
kind of thing for temporary files, and we will do it in two steps that
show where the `try` really belongs.

The first step is a plain function that takes the body as a function:

```clojure
(defn call-with-temp-file
  "Create an empty temporary file, call `f` with it, and delete the
   file afterwards whether or not `f` returned normally."
  [f]
  (let [file (File/createTempFile "sdx" ".tmp")]
    (try
      (f file)
      (finally
        (.delete file)))))
```

This already does the whole job. The `try`/`finally` is here, in one
place, rather than copied into every piece of code that needs a scratch
file, and that is the real point: cleanup code that is written in many
places will eventually be written wrong in one of them. The function
version is also directly testable and can be called from anywhere, which
is why the macro that follows is only a convenience:

```clojure
(defmacro with-temp-file
  "Bind `name` to a fresh temporary java.io.File while `body` runs.
   The file is deleted when `body` finishes, even if it throws."
  [[name] & body]
  `(call-with-temp-file (fn [~name] ~@body)))
```

```clojure
(macroexpand-1 '(with-temp-file [f] (slurp f)))
;; => (sdx.protocols.macros/call-with-temp-file (clojure.core/fn [f] (slurp f)))
```

The macro's only job is to wrap the body in `(fn [f] ...)` so that the
caller can write `(with-temp-file [f] ...)` instead of
`(call-with-temp-file (fn [f] ...))`. This pattern, a function called
`call-with-something` plus a macro called `with-something` that expands
into it, is the standard way to write a context manager in Clojure. The
function holds the logic and the macro holds the syntax. Keep the macro
as thin as possible, because macros are harder to test, cannot be passed
as arguments, and are expanded at every call site, so a bug in one is
copied everywhere it is used.

Why does `try`/`finally` belong in a macro (or in a function the macro
calls) rather than in the caller's code? Because the guarantee it
provides is only as good as the discipline of everyone who writes a
caller. A macro turns a convention ("always delete the file in a
finally") into a fact. The tests in `test/sdx/protocols/macros_test.clj`
check that fact from both directions:

```clojure
(deftest temp-files-are-cleaned-up-after-an-exception
  (let [kept (atom nil)]
    (is (thrown? ArithmeticException
                 (m/with-temp-file [f]
                   (reset! kept f)
                   (/ 1 0))))
    (is (not (.exists ^java.io.File @kept)))))
```

Here the atom is needed for a different reason than before: the body of
`with-temp-file` is the only place that can see the file, and the test
needs to inspect it *after* the block has finished, so the body smuggles
the reference out through an atom. A directory version follows the same
pattern, with a helper that deletes a tree from the leaves up:

```clojure
(defn delete-tree!
  "Delete `root`, which may be a file or a directory with contents."
  [^File root]
  (doseq [^File f (reverse (file-seq root))]
    (.delete f)))

(defn call-with-temp-dir
  "Create an empty temporary directory, call `f` with it, and delete
   it and everything inside it afterwards."
  [f]
  (let [dir (.toFile (Files/createTempDirectory "sdx" (make-array FileAttribute 0)))]
    (try
      (f dir)
      (finally
        (delete-tree! dir)))))

(defmacro with-temp-dir
  "Bind `name` to a fresh temporary directory (a java.io.File) while
   `body` runs. The directory and its contents are deleted afterwards."
  [[name] & body]
  `(call-with-temp-dir (fn [~name] ~@body)))
```

`file-seq` walks a directory tree parents-first, so reversing it gives
an order in which every directory comes after its contents, which is the
order deletion needs. The `(make-array FileAttribute 0)` is the empty
varargs array that `Files/createTempDirectory` requires; Java interop
sometimes shows through like this. The next chapter,
[the file archiver](../10-archive/), uses `with-temp-dir` throughout
its tests.

### Capturing output

Our last context manager redirects everything a block prints into a
string, and brings the chapter back to vars. `*out*` is the var that
`println` writes to. It is a **dynamic var**, marked at its definition
with `^:dynamic` and by convention with "earmuffs", and dynamic vars have
one ability that ordinary vars lack: `binding` can give them a new value
*for the current thread only*, for the duration of a block. The
replacement is visible to every function called inside the block, however
deep, and invisible to every other thread.

```clojure
(defmacro with-captured-output
  "Evaluate `body` with everything printed to *out* collected in a
   string. Returns {:value v :output s}."
  [& body]
  `(let [writer# (StringWriter.)
         value# (binding [*out* writer#] ~@body)]
     {:value value#
      :output (str writer#)}))
```

```clojure
(with-captured-output (println "hi") 42)
;; => {:value 42, :output "hi\n"}
```

Clojure's own `with-out-str` does nearly the same thing but returns only
the string, throwing the body's value away. Returning both in a map
means a test can check what a function printed *and* what it returned in
one go, which is exactly what the `with-timer` test needs:

```clojure
(deftest with-timer-prints-and-returns
  (let [{:keys [value output]} (m/with-captured-output
                                 (m/with-timer "adding" (+ 1 2)))]
    (is (= 3 value))
    (is (re-matches #"adding: \d+\.\d ms\n" output))))
```

So `with-redefs` and `binding` are two different tools that happen to
look alike. `with-redefs` changes a var's root, which every thread sees,
and works on any var; it is for tests. `binding` pushes a thread-local
value onto a dynamic var and is a legitimate technique in production code
for passing context (the current output stream, the current database
connection) down through calls that do not mention it.

### Closing the loop

We now have what we need to give `with-replacement`, the function from
the start of the chapter, the shape of `with-redefs`:

```clojure
(defmacro with-replaced
  "Point the var named by symbol `v` at `value` while `body` runs,
   then restore it. A one-var with-redefs built on with-replacement."
  [[v value] & body]
  `(mock/with-replacement (var ~v) ~value (fn [] ~@body)))
```

```clojure
(macroexpand-1 '(with-replaced [mock/exchange-rate (constantly 2)]
                  (mock/convert 10 "USD" "EUR")))
;; => (sdx.protocols.mock/with-replacement (var mock/exchange-rate) (constantly 2)
;;      (clojure.core/fn [] (mock/convert 10 "USD" "EUR")))
```

The caller writes a symbol, and the macro wraps it in `(var ...)` so
that the expansion refers to the var rather than to its current value.
That small transformation, from a name to the box the name lives in, is
something a function could never do, because by the time a function sees
its argument the name has already been looked up. It is the clearest
possible illustration of what macros are for: they can see the code, and
functions can only see its value.

## Summary

- A var is a box that holds a global definition. Calls go through the
  box, so replacing its contents with `alter-var-root` or `with-redefs`
  changes what callers do without changing the callers.
- Mocking means replacing a dependency, such as a clock or a network
  service, with a predictable stand-in so that the code around it can be
  tested in isolation. Always restore the original in a `finally`.
- Every collection function calls `seq` first. A type that implements
  `clojure.lang.Seqable` therefore works with the whole library; `Counted`
  and `Sequential` are further, separate promises.
- `lazy-seq` builds sequences whose elements are computed on demand and
  then cached, which makes infinite sequences safe and makes a stateful
  iterator into a reusable value.
- `deftype` makes a class with named fields and no built-in behavior;
  `reify` makes an anonymous instance of an interface. Use `defrecord`
  unless map behavior would be wrong.
- A macro receives its arguments as unevaluated code and returns code,
  so it can wrap a body in `let`, `try`/`finally`, or `binding`. Keep the
  logic in a `call-with-...` function and let the macro supply the
  syntax.
- `binding` gives a dynamic var a thread-local value; `with-redefs`
  changes a var's root for everyone. The first is for passing context,
  the second is for tests.

## Exercises

1. **Counting calls.** Change `recording` so that the returned map also
   has a `:count` function that returns how many times the wrapper has
   been called. Can you do it without a second atom?

2. **Several vars at once.** Extend `with-replaced` so that it accepts
   any number of var/value pairs, like `with-redefs`. Make sure the vars
   are restored in reverse order and that all of them are restored even
   if restoring one of them throws.

3. **A seqable range.** Write a deftype `Interval` with fields `lo`,
   `hi`, and `step` that implements `Seqable`, `Counted`, and
   `Sequential`, and whose `count` is computed arithmetically rather than
   by walking the seq. What goes wrong if `step` is zero, and where is
   the right place to catch it?

4. **Equality for trees.** Implement `clojure.lang.IPersistentCollection`
   on `Tree` so that two trees holding the same values are `=`. (Look at
   what methods the interface requires, and decide what `cons` and
   `empty` should mean for a search tree.)

5. **Chunking.** Clojure's `range` produces its elements in chunks of 32
   for speed. Write a version of `multiples` that uses `chunk-buffer`,
   `chunk-append`, and `chunk-cons`, and use `realized?` to show how many
   elements are computed when you ask for the first one.

6. **A timeout macro.** Write `(with-timeout ms & body)` that runs the
   body in a `future` and throws if it has not finished within `ms`
   milliseconds. Why does this one *have* to be a macro, and what should
   happen to the future if the timeout fires?

7. **Capturing errors too.** `with-captured-output` only captures
   `*out*`. Extend it to capture `*err*` as well, and decide whether the
   two streams should be returned separately or interleaved.

8. **Macro hygiene.** Rewrite `timed` using a plain `start` symbol
   instead of `start#`, then call it with a body that also uses a local
   named `start`. Explain what you see from the macroexpansion.

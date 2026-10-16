---
title: "Object Persistence"
nav_order: 16
lede: "A line-oriented save format that round-trips nested data, preserves sharing, and lets new types join in."
---

Sooner or later every program needs to put a value somewhere it will
outlive the process: a file, a socket, a database column. The value has
to be turned into bytes, and later the bytes have to be turned back into
the same value, or at least one that is equal to it. This is
**persistence**, and although every language has a library for it, the
libraries hide the three questions that make it interesting. How do you
write out a nested structure so that it can be read back without
ambiguity? What does "the same value" mean when one piece of data is
referred to from several places? And how can a format that was designed
for the built-in types be taught about types that did not exist when it
was written?

This chapter answers the three questions in three stages. The first
stage writes Clojure values as lines of text and reads them back, with
`save` and `load` as a pair of mutually recursive functions that mirror
each other exactly. The second stage notices when the same collection
appears more than once and writes it only once, which means threading a
table of what has been seen through the whole recursion. The third
stage replaces the fixed list of types with two extension points, a
protocol for writing and a multimethod for reading, so that a record,
or a Java class we do not own, can say for itself how it is saved.

## A format you can read

Our format is text, one line at a time. Every value begins with a
**header** line of the form `tag:argument`, and some values are
followed by more lines. The tags are:

| value | lines |
|-------|-------|
| `nil` | `nil` |
| boolean | `bool:true` or `bool:false` |
| integer | `int:42` |
| float | `float:2.5` |
| keyword | `kw:name` or `kw:ns/name` |
| string | `str:N` followed by the string's N lines |
| vector | `vec:N` followed by N values |
| set | `set:N` followed by N values |
| map | `map:N` followed by N keys and values, alternating |

A map with one entry whose value is a vector therefore looks like this:

```clojure
(save {:a [1 {:b nil}]})
;; => ["map:1" "kw:a" "vec:2" "int:1" "map:1" "kw:b" "nil"]
```

Two decisions are hiding in that table. The first is that strings are
stored by counting their lines rather than by escaping the characters
that would otherwise confuse the reader. The reader is told "the next
three lines are text", so a line of text that happens to look like
`int:5` is never mistaken for a header. Counting is simpler than
escaping and there is nothing to get wrong. The second is that
collections announce how many elements follow instead of using a
closing marker like `]`. That lets the reader know when to stop without
looking ahead, and it is what makes the mutual recursion below so
regular.

Why text at all, when bytes are smaller and faster?
([Chapter 17](../17-binary/) deals with bytes.) Because a format you can
open in an editor is a format you can debug, diff, and fix by hand when
something goes wrong, and something always goes wrong.

## Stage 1: save and load

The first stage lives in `sdx.persist.basic`. Its namespace
declaration has one line worth explaining:

```clojure
(ns sdx.persist.basic
  "Stage 1: save Clojure values as lines of text and load them back.

   Every value becomes a header line of the form `tag:argument`, possibly
   followed by more lines: the text of a string, or the elements of a
   collection. `save` turns a value into a vector of lines and `load`
   turns the front of a sequence of lines back into a value. Neither
   touches a file; two small functions at the end do that."
  (:refer-clojure :exclude [load])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))
```

`load` is already the name of a function in `clojure.core`, and
defining our own would work but would print a warning every time the
namespace was compiled. `:refer-clojure :exclude` tells Clojure not to
bring that one name in, so that ours can have it.

### Saving is a function from a value to lines

The most important decision in the chapter is that `save` does not
write to a file, a stream, or anything else. It returns a vector of
strings. That makes it a pure function, which means it can be tested by
comparing vectors, composed by concatenating them, and reasoned about
without thinking about I/O at all.

```clojure
(declare save)

(defn- save-all
  "The lines for each of `values`, one after another."
  [values]
  (into [] (mapcat save) values))
```

`declare` creates the var `save` before it is defined, so that
`save-all` can refer to it. The two functions are **mutually
recursive**: `save` calls `save-all` for the elements of a collection,
and `save-all` calls `save` for each element. Clojure compiles a file
top to bottom and will not let a function mention a name it has not
seen, so one of the pair has to be announced ahead of time.

```clojure
(defn save
  "The lines that represent `value`."
  [value]
  (cond
    (nil? value) ["nil"]
    (boolean? value) [(str "bool:" value)]
    (integer? value) [(str "int:" value)]
    (float? value) [(str "float:" value)]
    (keyword? value) [(str "kw:" (subs (str value) 1))]
    (string? value) (let [lines (str/split value #"\n" -1)]
                      (into [(str "str:" (count lines))] lines))
    (vector? value) (into [(str "vec:" (count value))] (save-all value))
    (set? value) (into [(str "set:" (count value))] (save-all value))
    (map? value) (into [(str "map:" (count value))] (save-all (apply concat value)))
    :else (throw (ex-info (str "don't know how to save " (type value))
                          {:value value}))))
```

Each branch of the `cond` is one row of the table. A few details repay
attention. `(str :ns/name)` is `":ns/name"`, so `subs` drops the colon
and `keyword` will put it back. `(apply concat value)` on a map gives
its keys and values in alternation, `(:a 1 :b 2)`, which is the order
the format wants. And the string branch splits on newlines with a limit
of `-1`:

```clojure
(str/split "a\nb\n" #"\n" -1)   ;; => ["a" "b" ""]
(str/split-lines "a\nb\n")      ;; => ["a" "b"]
```

The more obvious `str/split-lines` throws away a trailing empty line,
which means a string that ends in a newline would come back without
one. The `-1` keeps every piece. Details like this are why round-trip
tests exist.

### Loading is a function from lines to a value and the rest

`load` is the mirror image. It reads one value from the front of a
sequence of lines and returns both the value and the lines it did not
consume, because the caller (usually `load-n`, reading the elements of
a collection) needs to know where to continue from.

```clojure
(declare load)

(defn- load-n
  "Load `n` values from the front of `lines`.
   Returns [values remaining-lines]."
  [n lines]
  (loop [i 0, values [], lines lines]
    (if (= i n)
      [values lines]
      (let [[value lines] (load lines)]
        (recur (inc i) (conj values value) lines)))))

(defn- take-text
  "The first `n` of `lines` joined back into one string, and the rest."
  [n lines]
  (let [text (take n lines)]
    (when (< (count text) n)
      (throw (ex-info "string is shorter than its header says" {:expected n})))
    [(str/join "\n" text) (drop n lines)]))
```

Returning `[value remaining]` is a common Clojure pattern for a parser
of any kind: a function that consumes some input and must hand the
remainder to whoever consumes next. It is the pure-functional
alternative to a reader object with a position inside it, and it makes
the tests easy, because the result says exactly what was and was not
consumed:

```clojure
(load ["vec:2" "int:1" "int:2" "int:3"])
;; => [[1 2] ("int:3")]
```

```clojure
(defn load
  "Read one value from the front of `lines`. Returns [value remaining-lines]."
  [lines]
  (when (empty? lines)
    (throw (ex-info "ran out of lines while loading" {})))
  (let [[line & more] lines
        [tag arg] (str/split line #":" 2)]
    (case tag
      "nil" [nil more]
      "bool" [(= "true" arg) more]
      "int" [(parse-long arg) more]
      "float" [(parse-double arg) more]
      "kw" [(keyword arg) more]
      "str" (take-text (parse-long arg) more)
      "vec" (load-n (parse-long arg) more)
      "set" (let [[values more] (load-n (parse-long arg) more)]
              [(set values) more])
      "map" (let [[values more] (load-n (* 2 (parse-long arg)) more)]
              [(apply hash-map values) more])
      (throw (ex-info (str "unknown tag in line: " line) {:line line})))))
```

`(str/split line #":" 2)` splits at the *first* colon only, so a
keyword with a namespace or a string header is not cut into too many
pieces, and a header with no colon at all, like `nil`, yields a tag and
no argument. `case` then does for loading what `cond` did for saving:
one branch per row of the table, in the same order, each one the exact
inverse of the branch opposite it. When a format is designed so that
its writer and reader have the same shape, a bug in either is usually a
visible asymmetry between the two.

### Strings and files

Three small functions connect the pure core to the outside world. A
saved value becomes text by joining its lines, each ended by a newline;
text becomes a value by splitting it back into lines. A file is just
text.

```clojure
(defn from-lines
  "The one value that `lines` represents. Anything after it must be blank."
  [lines]
  (let [[value rest] (load lines)]
    (when-not (every? str/blank? rest)
      (throw (ex-info "extra lines after the value" {:extra (take 3 rest)})))
    value))

(defn to-string
  "`value` as text: its lines, each ended by a newline."
  [value]
  (apply str (map #(str % "\n") (save value))))

(defn from-string
  "The value that `text` (as produced by `to-string`) represents."
  [text]
  (from-lines (str/split text #"\n" -1)))

(defn save-to
  "Write `value` to the file at `path`."
  [path value]
  (spit path (to-string value)))

(defn load-from
  "Read back the value saved in the file at `path`."
  [path]
  (with-open [r (io/reader path)]
    (from-lines (line-seq r))))
```

`line-seq` returns a lazy sequence of the lines of a reader, and
`with-open` closes the reader when the body finishes. Those two
together carry a trap: if `from-lines` returned something lazy, the
reader would be closed before anyone read from it. It does not, because
`load` builds every vector eagerly with `conj`, but it is the kind of
thing to check whenever a lazy sequence and a resource meet.

### Round trips

The test file `test/sdx/persist/basic_test.clj` has two kinds of
test. The first pins down the exact lines for particular values, which
is what tells us the format is what we think it is:

```clojure
(deftest strings-are-counted-in-lines
  (is (= ["str:1" "hello"] (p/save "hello")))
  (is (= ["str:1" ""] (p/save "")))
  (is (= ["str:3" "one" "two" "three"] (p/save "one\ntwo\nthree")))
  (testing "a trailing newline is an extra, empty line"
    (is (= ["str:2" "one" ""] (p/save "one\n"))))
  (testing "a line that looks like a header is not mistaken for one"
    (is (= "int:5" (p/from-lines (p/save "int:5"))))))
```

The second kind is a list of awkward values and a loop that checks
each one survives the trip. The list is public so that the later
stages can reuse it; whatever we add, the old guarantees must still
hold.

```clojure
(def samples
  "Values that must survive a round trip unchanged."
  [nil true false 0 42 -17 3.5 -0.25 1.0e10
   "" "hi" "two\nlines" "ends with newline\n" "   padded   "
   :k :ns/k
   [] [1 2 3] [[[]]]
   {} {:a 1 :b "two" :c [3]} {"string key" 1 7 :seven}
   #{} #{1 2 3} #{[1 2] {:a 1}}
   {:name "sample" :tags #{:a :b} :scores [1.5 2.5] :nested {:deep {:deeper nil}}}])

(deftest everything-round-trips
  (doseq [v samples]
    (testing (pr-str v)
      (is (= v (p/from-lines (p/save v))))
      (is (= v (p/from-string (p/to-string v)))))))
```

A third test writes a value to a temporary directory and reads it back,
deleting the file afterwards, so that the file functions are exercised
without leaving anything behind.

## Stage 2: things that are shared

Here is a value that stage 1 handles badly:

```clojure
(let [shared (vec (range 100))
      big (vec (repeat 1000 shared))]
  (count (to-string big)))
;; => 698009
```

One vector of a hundred numbers, referred to a thousand times, becomes
seven hundred kilobytes of text, because `save` writes the vector out
in full every time it meets it. Size is the visible symptom. The
invisible one is that after loading, there are a thousand separate
vectors where there used to be one, and a program that relied on
`identical?` to tell whether two parts of a structure were the same
thing will no longer get the answer it had before.

### Equal or identical?

Clojure distinguishes two questions. `=` asks whether two values have
the same content; `identical?` asks whether they are the same object
in memory. For immutable data the difference rarely matters, which is
why Clojure code almost never uses `identical?`. Here it matters,
because we are asking "is this the *same* collection I already wrote?",
and `=` would give a confusing answer: two separately constructed
`[1 2]` vectors would be merged into one on loading, which changes the
structure of the data in a way nobody asked for.

So the second stage, `sdx.persist.aliasing`, keeps a **table** of every
collection it has written, in order. When it is about to write a
collection, it scans the table with `identical?`; if the collection is
there at position N, it writes `ref:N` instead. The loader keeps a table
of every collection it has built, in the same order, and `ref:N` means
"the Nth one again". Only collections go in the table. Numbers and
keywords are too small to be worth sharing, and strings are left out
for a subtler reason: whether two equal strings are the same object
depends on how they were made, so sharing them would make the output
depend on accidents of the program that produced the value.

### Threading the state

The table has to be available at every level of the recursion and
updated by every collection that is written. There are two ways to
arrange that. One is an atom that `save` reads and updates as a side
effect. The other is to make the state an explicit argument that every
function takes and returns. We take the second route, so that `save`
stays a pure function from a state and a value to a new state, and the
tests can inspect the table directly. The price is that every function
signature grows by one argument; the reward is that nothing happens
behind the reader's back.

The state is a map with two keys: `:lines`, the output so far, and
`:table`, the collections written so far.

```clojure
(defn emit
  "Append `lines` to the output in `state`."
  [state & lines]
  (update state :lines into lines))

(defn- seen
  "The position of `value` in `table`, if the very same object is there."
  [table value]
  (first (keep-indexed (fn [i x] (when (identical? x value) i)) table)))

(declare save)

(defn save-all
  "Save each of `values` in turn."
  [state values]
  (reduce save state values))
```

Compare `save-all` with its stage 1 version. Before, it concatenated
the lines of each element; now it threads the state through each
element in turn, which is exactly what `reduce` does. "Thread a state
through a sequence of steps" is one of the most common shapes in
Clojure, and `reduce` is nearly always the way to write it.

```clojure
(defn- save-fresh
  "Write a value the saver has not seen before."
  [state value]
  (cond
    (nil? value) (emit state "nil")
    (boolean? value) (emit state (str "bool:" value))
    (integer? value) (emit state (str "int:" value))
    (float? value) (emit state (str "float:" value))
    (keyword? value) (emit state (str "kw:" (subs (str value) 1)))
    (string? value) (let [lines (str/split value #"\n" -1)]
                      (apply emit state (str "str:" (count lines)) lines))
    (vector? value) (save-all (emit state (str "vec:" (count value))) value)
    (set? value) (save-all (emit state (str "set:" (count value))) value)
    (map? value) (save-all (emit state (str "map:" (count value))) (apply concat value))
    :else (throw (ex-info (str "don't know how to save " (type value))
                          {:value value}))))

(defn save
  "Write `value` into `state`: as `ref:N` if it is a collection already
   in the table, otherwise in full, adding it to the table afterwards if
   it is a collection. Returns the new state."
  [state value]
  (if-let [id (and (coll? value) (seen (:table state) value))]
    (emit state (str "ref:" id))
    (let [state (save-fresh state value)]
      (if (coll? value)
        (update state :table conj value)
        state))))
```

`save-fresh` is the stage 1 `save` with the state threaded through it.
`save` wraps it with the two new rules: check the table first, and add
to the table afterwards. The word *afterwards* is a design decision.
A collection is added to the table only once all of its elements have
been written, so collections are numbered in the order they are
*finished*, inner ones before the outer ones that contain them. The
loader can reproduce that order without any ids being written into the
file, because it finishes building the inner collections first too.

```clojure
(let [shared [1 2]]
  (to-lines {:a shared :b shared}))
;; => ["map:2" "kw:a" "vec:2" "int:1" "int:2" "kw:b" "ref:0"]

(to-lines [[1] [1]])
;; => ["vec:2" "vec:1" "int:1" "vec:1" "int:1"]
```

The first example writes the shared vector once; it is the first
collection to be finished, so it is number 0, and the map itself will
be number 1. The second example has two equal vectors that are not the
same object, and both are written in full, as they should be.

> **The empty vector is one object.**
> Clojure's `[]` is a single shared constant, so `(to-lines [[] []])`
> gives `["vec:2" "vec:0" "ref:0"]`. That is not a bug: the two empty
> vectors really are the same object, and the format faithfully says
> so. It is a reminder that `identical?` tells you about the runtime,
> not about your intentions.

### Loading with a table

The loader threads the same kind of state, except that `:lines` now
holds the input still to be read instead of the output written so far.

```clojure
(declare load)

(defn load-n
  "Load `n` values from the front of the lines in `state`.
   Returns [values state]."
  [state n]
  (loop [i 0, values [], state state]
    (if (= i n)
      [values state]
      (let [[value state] (load state)]
        (recur (inc i) (conj values value) state)))))

(defn- take-text
  "The next `n` lines in `state` joined into one string. Returns [string state]."
  [state n]
  (let [text (take n (:lines state))]
    (when (< (count text) n)
      (throw (ex-info "string is shorter than its header says" {:expected n})))
    [(str/join "\n" text) (update state :lines #(drop n %))]))

(defn- load-fresh
  "Read a value that is written out in full. Returns [value state]."
  [tag arg state]
  (case tag
    "nil" [nil state]
    "bool" [(= "true" arg) state]
    "int" [(parse-long arg) state]
    "float" [(parse-double arg) state]
    "kw" [(keyword arg) state]
    "str" (take-text state (parse-long arg))
    "vec" (load-n state (parse-long arg))
    "set" (let [[values state] (load-n state (parse-long arg))]
            [(set values) state])
    "map" (let [[values state] (load-n state (* 2 (parse-long arg)))]
            [(apply hash-map values) state])
    (throw (ex-info (str "unknown tag: " tag) {:tag tag}))))

(defn load
  "Read one value from the front of the lines in `state`. A `ref:N` line
   yields the Nth collection loaded so far. Returns [value state]."
  [state]
  (let [[line & more] (:lines state)]
    (when (nil? line)
      (throw (ex-info "ran out of lines while loading" {})))
    (let [[tag arg] (str/split line #":" 2)
          state (assoc state :lines more)]
      (if (= tag "ref")
        [(nth (:table state) (parse-long arg)) state]
        (let [[value state] (load-fresh tag arg state)]
          [value (if (coll? value)
                   (update state :table conj value)
                   state)])))))
```

Once again the loader mirrors the saver line for line: `ref` is
handled before dispatching on the tag, and a freshly built collection
is added to the table after its elements, so the numbering agrees. A
loaded `ref:N` is literally `(nth table N)`, the same object that was
built earlier, which is what makes sharing survive the round trip:

```clojure
(deftest sharing-survives-a-round-trip
  (let [shared {:name "shared" :items [1 2 3]}
        value [shared {:left shared :right shared} shared]
        loaded (p/from-lines (p/to-lines value))]
    (is (= value loaded))
    (testing "the loaded structure has one copy, referenced four times"
      (let [[a {:keys [left right]} b] loaded]
        (is (identical? a left))
        (is (identical? a right))
        (is (identical? a b))))))
```

One test pins down the numbering order, because it is the kind of
invariant that is easy to break while refactoring and hard to notice
when broken:

```clojure
(deftest references-are-numbered-in-completion-order
  (testing "an inner collection finishes before the one around it"
    (let [inner [1]
          outer [inner]]
      (is (= ["vec:3" "vec:1" "vec:1" "int:1" "ref:1" "ref:0"]
             (p/to-lines [outer outer inner]))))))
```

And the thousand-copies example now costs 6,701 characters instead of
698,009. The `seen` scan is linear in the size of the table, which
makes saving a structure with many collections quadratic; the
exercises ask you to fix that with a `java.util.IdentityHashMap`.

> **No cycles.**
> In a language with mutable objects, a persistence library has to
> worry about a structure that contains itself, and the table must be
> updated *before* descending into a collection's children or the
> saver loops forever. Clojure's immutable collections cannot contain
> themselves, which is why we were free to number collections after
> their children instead, and why there is no cycle check anywhere in
> this chapter.

## Stage 3: letting types describe themselves

The `cond` in `save-fresh` and the `case` in `load-fresh` are closed.
To persist a record, you would have to edit both, and if the record
lives in someone else's code, you cannot. The third stage,
`sdx.persist.extensible`, replaces them with two things other code can
add to.

The two directions need different tools. When saving, we have a value
in hand and want to dispatch on its type, which is exactly what a
protocol does. When loading, we have nothing but a tag string, so we
need dispatch on a value, which is what a multimethod does.
([Chapter 2](../02-objects/) introduced both.)

```clojure
(defprotocol Persistable
  "How a value writes itself. An implementation appends lines to the
   state with `emit` and returns the new state, using `save` or
   `save-all` for any parts that are themselves values."
  (save-value [this state] "Append the lines for this value to `state`."))

(defmulti load-value
  "How a header line is read back. Dispatches on the tag before the
   colon; receives the text after the colon (or nil) and the state, and
   must return [value state]."
  (fn [tag _arg _state] tag))
```

The machinery around them is the stage 2 code with `save-value` in
place of `save-fresh` and `load-value` in place of `load-fresh`:

```clojure
(defn save
  "Write `value` into `state`: as `ref:N` if it is a collection already
   in the table, otherwise however its type says, adding it to the table
   afterwards if it is a collection. Returns the new state."
  [state value]
  (if-let [id (and (coll? value) (seen (:table state) value))]
    (emit state (str "ref:" id))
    (let [state (save-value value state)]
      (if (coll? value)
        (update state :table conj value)
        state))))
```

```clojure
(defn load
  "Read one value from the front of the lines in `state`. A `ref:N` line
   yields the Nth collection loaded so far; any other tag is handed to
   `load-value`. Returns [value state]."
  [state]
  (let [[line & more] (:lines state)]
    (when (nil? line)
      (throw (ex-info "ran out of lines while loading" {})))
    (let [[tag arg] (str/split line #":" 2)
          state (assoc state :lines more)]
      (if (= tag "ref")
        [(nth (:table state) (parse-long arg)) state]
        (let [[value state] (load-value tag arg state)]
          [value (if (coll? value)
                   (update state :table conj value)
                   state)])))))
```

Aliasing stays in `save` and `load` rather than moving into the
per-type code, because it is a property of the format, not of any one
type. A type's implementation only has to say what its lines are.

### The built-in types become extensions

With the extension points in place, the built-in types are implemented
through them like anything else. `extend-protocol` attaches
implementations to existing types, including `nil` and Java classes,
without changing those types:

```clojure
(extend-protocol Persistable
  nil
  (save-value [_ state] (emit state "nil"))

  Boolean
  (save-value [b state] (emit state (str "bool:" b)))

  Long
  (save-value [n state] (emit state (str "int:" n)))

  Double
  (save-value [d state] (emit state (str "float:" d)))

  clojure.lang.Keyword
  (save-value [k state] (emit state (str "kw:" (subs (str k) 1))))

  String
  (save-value [s state]
    (let [lines (str/split s #"\n" -1)]
      (apply emit state (str "str:" (count lines)) lines)))

  clojure.lang.IPersistentVector
  (save-value [v state] (save-all (emit state (str "vec:" (count v))) v))

  clojure.lang.IPersistentSet
  (save-value [s state] (save-all (emit state (str "set:" (count s))) s))

  clojure.lang.IPersistentMap
  (save-value [m state]
    (save-all (emit state (str "map:" (count m))) (apply concat m))))
```

The type names are more specific than the predicates were. `integer?`
was true of several Java classes; the protocol is extended to `Long`
only, so `(save-value (int 5) state)` fails with "No implementation of
method" rather than silently working for some integers and not
others. On the collection side we extend the *interfaces*
`IPersistentVector`, `IPersistentSet` and `IPersistentMap` rather than
the concrete classes, because Clojure has several classes for each
(a small map and a large map are different classes) and protocol
dispatch finds the implementation attached to the nearest interface.

```clojure
(defmethod load-value "nil" [_ _ state] [nil state])
(defmethod load-value "bool" [_ arg state] [(= "true" arg) state])
(defmethod load-value "int" [_ arg state] [(parse-long arg) state])
(defmethod load-value "float" [_ arg state] [(parse-double arg) state])
(defmethod load-value "kw" [_ arg state] [(keyword arg) state])

(defmethod load-value "str" [_ arg state]
  (let [n (parse-long arg)
        text (take n (:lines state))]
    (when (< (count text) n)
      (throw (ex-info "string is shorter than its header says" {:expected n})))
    [(str/join "\n" text) (update state :lines #(drop n %))]))

(defmethod load-value "vec" [_ arg state]
  (load-n state (parse-long arg)))

(defmethod load-value "set" [_ arg state]
  (let [[values state] (load-n state (parse-long arg))]
    [(set values) state]))

(defmethod load-value "map" [_ arg state]
  (let [[values state] (load-n state (* 2 (parse-long arg)))]
    [(apply hash-map values) state]))

(defmethod load-value :default [tag _ _]
  (throw (ex-info (str "unknown tag: " tag) {:tag tag})))
```

Each `defmethod` is one old `case` branch. The `:default` method is
where the old `case` threw for an unknown tag; a multimethod with no
default would throw its own, less helpful error.

### Adding a type from outside

Now the payoff. The test file `test/sdx/persist/extensible_test.clj`
defines a record and teaches the library about it, without touching
the library:

```clojure
(defrecord Point [x y]
  p/Persistable
  (save-value [_ state]
    (p/save-all (p/emit state "point") [x y])))

(defmethod p/load-value "point" [_ _ state]
  (let [[[x y] state] (p/load-n state 2)]
    [(->Point x y) state]))
```

A point writes a header with no argument and then saves its two
coordinates with the library's own `save-all`, so they can be any
persistable value, shared or not. Loading reads two values back and
builds the record. The record implements the protocol inline, which
takes precedence over the `IPersistentMap` implementation that a
record would otherwise match (every record is also a map). Because a
record is a collection, it takes part in sharing like anything else:

```clojure
(deftest records-take-part-in-sharing
  (let [point (->Point 1 2)
        [a b] (p/from-lines (p/to-lines [point point]))]
    (is (= ["vec:2" "point" "int:1" "int:2" "ref:0"] (p/to-lines [point point])))
    (is (identical? a b))))
```

A Java class we do not own works the same way, and this is the thing
inheritance-based designs cannot do: we cannot make `java.time.LocalDate`
inherit from anything, but we can extend our protocol to it.

```clojure
(extend-protocol p/Persistable
  LocalDate
  (save-value [d state] (p/emit state (str "date:" d))))

(defmethod p/load-value "date" [_ arg state]
  [(LocalDate/parse arg) state])
```

```clojure
(deftest a-java-class-can-be-extended-too
  (let [day (LocalDate/of 2026 10 16)]
    (is (= ["date:2026-10-16"] (p/to-lines day)))
    (is (= day (p/from-lines (p/to-lines day))))
    (is (= {:when day :points [(->Point 0 0)]}
           (p/from-string (p/to-string {:when day :points [(->Point 0 0)]}))))))
```

What about a record that does *not* implement the protocol? It
matches `IPersistentMap`, is written as a map, and comes back as a
plain map with the same keys. That is a reasonable default, and the
test says so explicitly, because a reader of the code should not have
to guess:

```clojure
(deftest a-record-without-an-implementation-comes-back-as-a-map
  (let [loaded (p/from-lines (p/to-lines (->Plain 1)))]
    (is (= {:a 1} loaded))
    (is (not (instance? Plain loaded)))))
```

Finally, the stage 3 tests run the stage 1 `samples` and the stage 2
sharing test again. Three stages, one list of awkward values, and the
guarantee that each stage kept everything the previous one promised.

## Summary

- A self-describing format gives every value a header that says what
  it is and how much follows, so the reader never has to look ahead or
  guess.
- Counting lines is a simpler way to store strings than escaping
  characters, and leaves nothing to get wrong.
- Making `save` return data (a vector of lines) instead of writing to
  a stream keeps it pure, testable by comparing vectors, and separate
  from the I/O that happens at the edge.
- A reader that returns `[value remaining-input]` is the functional
  shape of a parser; `load` and `load-n` are mutually recursive
  mirrors of `save` and `save-all`.
- `identical?` asks about objects, `=` about content. Preserving
  sharing is a question about objects.
- State that must flow through a recursion can be an explicit argument
  and return value; `reduce` is the natural way to thread it through a
  sequence.
- Numbering collections when they are finished lets the reader
  reproduce the numbering without ids in the file, and is safe because
  immutable data has no cycles.
- A protocol dispatches on the type of a value you have; a multimethod
  dispatches on a value you have computed, such as a tag. Saving needs
  the first, loading the second.
- Implementing the built-in types through the same extension points as
  everything else proves the extension points are sufficient.

## Exercises

1. **Lists and ratios.** Add support for lists (which should come back
   as lists, not vectors) and ratios such as `3/4` to stage 3. Which
   side was harder to extend, and why?

2. **Faster lookup.** Replace the linear `seen` scan with a
   `java.util.IdentityHashMap` from object to id. The state is no
   longer a pure value; what do the tests have to change, and what do
   they lose?

3. **Records by default.** Change the stage 3 `IPersistentMap`
   implementation so that a record without its own implementation is
   saved with its class name and comes back as the same record type.
   You will need `(.getName (class record))` and a way to find the
   constructor from the name. What goes wrong if the class is renamed?

4. **Streams instead of lines.** Rewrite stage 1 so that `save` writes
   to a `java.io.Writer` and `load` reads from a
   `java.io.BufferedReader`. Which tests become harder to write, and
   which become unnecessary?

5. **Versioning.** Add a first line `version:1` to the format and make
   `from-lines` refuse a file whose version it does not understand.
   Then change the format of floats to hexadecimal and make the loader
   accept both versions.

6. **Checking the count.** The loader trusts the count in each header.
   Make `from-lines` detect a file where a collection claims more
   elements than are present, and write a test that produces a helpful
   error message rather than "ran out of lines".

7. **Pretty printing.** Write a function that renders the lines of a
   saved value with indentation showing the nesting, so that a saved
   file can be read by a person. Should it live in the library or
   beside it?

---
title: "Performance Profiling"
nav_order: 15
lede: "Two dataframe layouts behind one protocol, a benchmark harness to compare them, and a profiler to see where the time goes."
---

Programmers have strong opinions about what is fast, and most of them
are wrong. The only cure is to measure, and measuring turns out to be
a design problem of its own: what to time, how many times, how to
summarize the results, and how to keep the act of measuring from
changing the thing being measured.

This chapter builds three small tools. The first is a **dataframe**, a
table with named columns, implemented two different ways behind one
protocol so that we can swap the storage layout without touching the
code that uses it. The second is a benchmark harness that times a piece
of code properly, with warm-up and repetition, using nothing but
`System/nanoTime`. The third is a profiler: a wrapper that counts how
often a function is called and how long those calls take in total. Along
the way we will see that neither layout is simply "the fast one", that
the first few measurements a program makes are the least trustworthy,
and that a profiler lies a little by its very nature.

## One interface, two layouts

A dataframe has columns with names and rows with values, and the
operations we need are modest: how many rows and columns there are, what
the columns are called, the values in one column, a new frame with only
some of the columns, and a new frame with only the rows that pass a
test. We write those down as a **protocol** (introduced in
[Chapter 2](../02-objects/)): a named set of functions that a type can
promise to implement, with dispatch on the type of the first argument.

```clojure
(defprotocol DataFrame
  "A rectangular table: named columns, every column the same length."
  (ncol [df] "Number of columns.")
  (nrow [df] "Number of rows.")
  (cols [df] "The column names, as a set.")
  (column [df name]
    "Every value in the named column, as a vector, top to bottom.")
  (select [df names]
    "A new frame holding only the named columns, in the same row order.")
  (filter-rows [df pred]
    "A new frame holding only the rows for which (pred row) is truthy.
     `row` is a map from column name to value."))
```

Notice what is *not* in the protocol. Equality is not, because it can
be written once in terms of `cols`, `nrow` and `column` without knowing
how either frame is stored. The same goes for fetching a row. Keeping
the protocol small means each layout has less to implement and there is
less that can disagree between them.

```clojure
(defn eq?
  "True if `a` and `b` have the same columns holding the same values in
   the same order, regardless of how either frame is stored."
  [a b]
  (and (= (cols a) (cols b))
       (= (nrow a) (nrow b))
       (every? (fn [name] (= (column a name) (column b name))) (cols a))))

(defn row
  "Row `i` of `df` as a map from column name to value. Works for any
   layout, but a layout that stores rows can do better."
  [df i]
  (into {} (map (fn [name] [name (nth (column df name) i)])) (cols df)))
```

### Row-major: a vector of maps

The layout most programs fall into is a vector of maps, one map per
row, because that is what reading a CSV file or a query result
naturally produces. We wrap it in a **record** (also from
[Chapter 2](../02-objects/)), a map-like type with named fields that can
implement a protocol inline:

```clojure
(defrecord RowFrame [names rows]
  f/DataFrame
  (ncol [_] (count names))
  (nrow [_] (count rows))
  (cols [_] names)
  (column [_ name] (mapv #(get % name) rows))
  (select [_ wanted]
    (let [wanted (set wanted)]
      (->RowFrame wanted (mapv #(select-keys % wanted) rows))))
  (filter-rows [_ pred]
    (->RowFrame names (filterv pred rows))))
```

Why does the record carry `names` as well as `rows`? Because a frame
with no rows has nowhere else to keep its column names, and a frame with
no rows is exactly what `filter-rows` produces when nothing matches. A
layout that could not answer `cols` on an empty frame would be a layout
with a hole in it. The constructor checks that every row really has
those names:

```clojure
(defn row-frame
  "Build a row-major frame from a sequence of maps, which must all have
   the same keys. Pass `names` as well when there may be no rows."
  ([rows]
   (row-frame (keys (first rows)) rows))
  ([names rows]
   (let [names (set names)
         rows (vec rows)]
     (doseq [r rows]
       (when (not= (set (keys r)) names)
         (throw (ex-info "row does not have the frame's columns"
                         {:expected names :row r}))))
     (->RowFrame names rows))))
```

Look at the two operations we are going to measure. `filter-rows` is
one call to `filterv`: the rows are already maps, so the predicate can
be handed each one as it is. `select`, on the other hand, has to visit
every row and build a smaller map from it, so its cost grows with the
number of rows times the number of columns kept.

### Column-major: a map of vectors

The other layout stores each column as a vector and the frame as a map
from column name to vector. Statisticians and database engines prefer
it, because most of what they do touches a few columns and all of the
rows.

```clojure
(defn row-at
  "Row `i` of a column map, as a map from column name to value."
  [columns i]
  (reduce-kv (fn [row name values] (assoc row name (nth values i)))
             {}
             columns))

(defn- height
  "The number of rows in a column map."
  [columns]
  (if (empty? columns) 0 (count (val (first columns)))))

(defrecord ColFrame [columns]
  f/DataFrame
  (ncol [_] (count columns))
  (nrow [_] (height columns))
  (cols [_] (set (keys columns)))
  (column [_ name] (get columns name))
  (select [_ wanted]
    (->ColFrame (select-keys columns wanted)))
  (filter-rows [_ pred]
    (let [keep (filterv #(pred (row-at columns %)) (range (height columns)))]
      (->ColFrame (reduce-kv (fn [m name values] (assoc m name (mapv values keep)))
                             {}
                             columns)))))
```

Now the two operations have swapped roles. `select` is a single
`select-keys` on a map with one entry per column; it does not look at
the rows at all, and because Clojure's vectors are immutable, the new
frame can share the old frame's column vectors without copying them.
`filter-rows` has become the awkward one: the predicate wants a row as
a map, and a column-major frame does not have any, so `row-at` has to
build one for every row, call the predicate, and then pull the surviving
indexes out of every column.

```clojure
(defn col-frame
  "Build a column-major frame from a map of column name to a sequence of
   values. Every column must have the same length."
  [columns]
  (let [columns (into {} (map (fn [[name values]] [name (vec values)])) columns)
        lengths (set (map count (vals columns)))]
    (when (> (count lengths) 1)
      (throw (ex-info "columns have different lengths"
                      {:lengths (into {} (map (fn [[k v]] [k (count v)])) columns)})))
    (->ColFrame columns)))

(defn from-rows
  "Build a column-major frame from the same input `row-frame` takes:
   a sequence of maps with identical keys."
  ([rows]
   (from-rows (keys (first rows)) rows))
  ([names rows]
   (col-frame (into {} (map (fn [name] [name (mapv #(get % name) rows)])) names))))
```

`from-rows` takes the same input as `row-frame` so that the two
layouts can be built from one piece of data and compared.

### Testing both at once

Because the protocol is the contract, the tests in
`test/sdx/perf/frame_test.clj` are written once and run against both
layouts. A map from a label to a constructor is all it takes:

```clojure
(def layouts
  "Each layout's constructor, so every test below runs on both."
  {"row-major" rows/row-frame
   "column-major" cols/from-rows})

(deftest filtering-rows
  (doseq [[label build] layouts]
    (testing label
      (let [df (f/filter-rows (build sample) :flies)]
        (is (= 2 (f/nrow df)))
        (is (= 3 (f/ncol df)))
        (is (= ["bat" "dove"] (f/column df :name)))))))
```

And `eq?` lets us say directly that the layouts are interchangeable,
which is the property the rest of the chapter depends on:

```clojure
(deftest layouts-are-interchangeable
  (let [by-row (rows/row-frame sample)
        by-col (cols/from-rows sample)]
    (is (f/eq? by-row by-col))
    (is (f/eq? by-col by-row))
    (is (f/eq? (f/select by-row [:legs]) (f/select by-col [:legs])))
    (is (f/eq? (f/filter-rows by-row :flies) (f/filter-rows by-col :flies)))
    (is (not (f/eq? by-row (f/select by-col [:legs]))))
    (is (not (f/eq? by-row (f/filter-rows by-col :flies))))))
```

## Measuring time

Clojure comes with `time`, which prints how long one evaluation of an
expression took. That is fine for a quick look and useless for a
comparison, for three reasons. It runs the code once, so a single
garbage collection pause or a cache miss can double the number. It runs
the code cold: the JVM starts by interpreting bytecode and only
compiles a method to machine code after it has been called thousands of
times, so the first run of anything is slow in a way that has nothing
to do with the algorithm. And it prints its result instead of returning
it, so a program cannot do anything with the number.

Our harness fixes all three. It calls the code a number of times
without timing them (the **warm-up**), then a number of times with
timing (the **repeats**), and returns the median along with the raw
timings so that the caller can look at the spread.

```clojure
(defn median
  "The median of a non-empty collection of numbers."
  [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)
        mid (quot n 2)]
    (if (odd? n)
      (nth sorted mid)
      (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2.0))))
```

Why the median rather than the mean? Timings are not symmetric. A run
can be slower than usual for many reasons (a garbage collection, another
process waking up, the JIT compiler deciding to recompile something) but
it can never be faster than the work requires. The distribution has a
hard floor and a long tail to the right, and the mean is dragged toward
the tail by every outlier. The median shrugs them off.

```clojure
(def ^:private sink
  "Somewhere to put each result so that the JIT compiler cannot decide
   the work was unnecessary and skip it."
  (atom nil))

(defn- time-once
  "Call `thunk` and return how long it took, in nanoseconds."
  [thunk]
  (let [start (System/nanoTime)
        result (thunk)
        stop (System/nanoTime)]
    (reset! sink result)
    (- stop start)))
```

`System/nanoTime` is a Java static method, called with the
`Class/method` syntax, that returns a counter in nanoseconds. It is not
a clock (its zero point is arbitrary) but the difference between two
readings is as precise as the operating system allows. The `sink` is an
**atom**, a mutable reference whose value is changed with `reset!` or
`swap!`. We need mutability here for a slightly paranoid reason: a
compiler that notices a result is never used is allowed to skip
computing it. Storing every result somewhere keeps the work honest.

```clojure
(defn- warm-up
  "Call `thunk` until both `warmup` calls and `warmup-ms` milliseconds
   have gone by. Returns how many calls that took."
  [thunk warmup warmup-ms]
  (let [deadline (+ (System/nanoTime) (* warmup-ms 1e6))]
    (loop [calls 0]
      (if (or (< calls warmup) (< (System/nanoTime) deadline))
        (do (time-once thunk)
            (recur (inc calls)))
        calls))))
```

The warm-up has two limits, a count and a duration, and keeps going
until both are satisfied. The first version of this harness had only
the count, and it produced nonsense: five warm-up runs of a 200
microsecond operation is one millisecond, nowhere near long enough for
the JIT to notice the code is hot, so the timed runs were measuring
interpreted bytecode. A time limit scales with the size of the work. We
return the number of calls made so that a curious caller can see how
many it took.

```clojure
(defn bench*
  "Call `thunk` without timing it until it has run `warmup` times and
   `warmup-ms` milliseconds have passed, then `repeats` times with
   timing. Returns a map with the :median-ms, :min-ms and :max-ms of the
   timed runs, the individual :times-ms, and the number of :warmup-runs."
  ([thunk] (bench* thunk {}))
  ([thunk {:keys [warmup warmup-ms repeats]
           :or {warmup 5 warmup-ms 500 repeats 11}}]
   (let [warmup-runs (warm-up thunk warmup warmup-ms)
         times (mapv (fn [_] (/ (time-once thunk) 1e6)) (range repeats))]
     {:median-ms (median times)
      :min-ms (apply min times)
      :max-ms (apply max times)
      :times-ms times
      :warmup-runs warmup-runs})))
```

`bench*` takes a **thunk**, a function of no arguments, because the
code under test has to be run many times and a function is the only
kind of value that can be run. Asking callers to write
`(bench* (fn [] ...))` everywhere is tedious, and that tedium is the
reason for the one macro in this chapter:

```clojure
(defmacro bench
  "Time `body` the way `bench*` times a thunk. `opts` is a map that may
   hold :warmup, :warmup-ms and :repeats; pass {} for the defaults.

     (bench {:repeats 5} (expensive-thing))"
  [opts & body]
  `(bench* (fn [] ~@body) ~opts))
```

A **macro** is a function that runs at compile time, receives its
arguments as unevaluated code, and returns code to be compiled in its
place. A normal function could not do this job: by the time a function
is called, its arguments have already been evaluated, and `(expensive-thing)`
would have run exactly once, before the harness ever saw it. The
backquote builds a code template; `~@body` splices the body forms into
it and `~opts` inserts the options map. We can ask Clojure to show us
the result:

```clojure
(macroexpand-1 '(b/bench {:repeats 5} (expensive)))
;; => (sdx.perf.bench/bench* (clojure.core/fn [] (expensive)) {:repeats 5})
```

[Chapter 9](../09-protocols/) looks at macros in more depth. For now
the rule of thumb is: write a function unless the thing you need is
control over *when* (or whether) an argument is evaluated, and then
write the smallest macro that hands the work to a function.

```clojure
(b/bench {:repeats 5} (reduce + (range 1000000)))
;; => {:median-ms 8.752499, :min-ms 7.905834, :max-ms 25.150876,
;;     :times-ms [8.515222 11.831518 8.752499 7.905834 25.150876]}
```

Even in five runs the spread is visible: the slowest took three times
as long as the fastest, and the median is near the floor where the
real cost lives.

The tests in `test/sdx/perf/bench_test.clj` cannot check timings
exactly, but they can check the things that must be true of any
timing. A two millisecond sleep can never be measured as less than two
milliseconds; a body runs warm-up plus repeats times; the minimum,
median and maximum are in order:

```clojure
(deftest body-runs-warmup-plus-repeats-times
  (let [calls (atom 0)
        result (b/bench {:warmup 2 :warmup-ms 0 :repeats 3} (swap! calls inc))]
    (is (= 5 @calls))
    (is (= 2 (:warmup-runs result)))))

(deftest timings-are-plausible
  (let [result (b/bench {:warmup 1 :warmup-ms 0 :repeats 3} (Thread/sleep 2))]
    (testing "a 2ms sleep never takes less than 2ms"
      (is (>= (:min-ms result) 2.0)))
    (testing "min, median and max are in order"
      (is (<= (:min-ms result) (:median-ms result) (:max-ms result))))
    (testing "every timed run is reported"
      (is (= 3 (count (:times-ms result)))))))
```

## The experiment

With two layouts and a harness we can ask the question the chapter is
about: which layout is faster? The experiment builds frames of
increasing size with ten columns, then times selecting three columns
and filtering to the rows whose first column is even, on each layout.

```clojure
(defn make-rows
  "`n` rows with `width` columns named :c0, :c1, ... The value in row i,
   column j is i + j, so every column is different and nothing is random."
  [n width]
  (mapv (fn [i]
          (into {} (map (fn [j] [(keyword (str "c" j)) (+ i j)])) (range width)))
        (range n)))

(def ^:private wanted [:c0 :c1 :c2])

(defn- even-first?
  "The row predicate used by every filter benchmark: keeps half the rows."
  [row]
  (even? (:c0 row)))
```

Nothing in the data is random: a benchmark that cannot be rerun on
exactly the same input cannot tell you whether a change in the numbers
came from the code or from the data.

```clojure
(defn measure
  "Median times, in milliseconds, for select and filter on a row-major
   and a column-major frame with `n` rows and ten columns. `opts` are
   passed to `bench`."
  ([n] (measure n {:warmup 5 :warmup-ms 2000 :repeats 11}))
  ([n opts]
   (let [data (make-rows n 10)
         by-row (rows/row-frame data)
         by-col (cols/from-rows data)]
     {:rows n
      :select-rows (:median-ms (b/bench opts (f/select by-row wanted)))
      :select-cols (:median-ms (b/bench opts (f/select by-col wanted)))
      :filter-rows (:median-ms (b/bench opts (f/filter-rows by-row even-first?)))
      :filter-cols (:median-ms (b/bench opts (f/filter-rows by-col even-first?)))})))
```

`measure` takes the benchmark options as an argument with a generous
default, so that the test can run it on fifty rows with no warm-up and
finish in milliseconds. The last two functions turn the results into a
table and make the namespace runnable from the command line:

```clojure
(defn table
  "Format a sequence of `measure` results as a Markdown table."
  [results]
  (let [line (fn [cells] (str "| " (str/join " | " cells) " |"))
        cell (fn [ms] (format "%.3f" (double ms)))]
    (str/join
     "\n"
     (into [(line ["rows" "select (rows)" "select (cols)" "filter (rows)" "filter (cols)"])
            (line (repeat 5 "---:"))]
           (map (fn [{:keys [rows select-rows select-cols filter-rows filter-cols]}]
                  (line [rows (cell select-rows) (cell select-cols)
                         (cell filter-rows) (cell filter-cols)])))
           results))))

(defn -main
  "Print the comparison table for the sizes given on the command line,
   or for 1000, 10000 and 100000 rows if none are given."
  [& args]
  (let [sizes (if (seq args) (map parse-long args) [1000 10000 100000])]
    (println (table (map measure sizes)))))
```

Running it takes about forty seconds, most of which is warm-up:

```
clojure -M -m sdx.perf.compare 1000 10000 100000 300000
```

Here is one run, on a four-core virtual machine with a 2.1 GHz Xeon
and JDK 21. All times are medians in milliseconds. Your numbers will
differ, possibly by a lot; the shape of the table is what matters.

| rows | select (rows) | select (cols) | filter (rows) | filter (cols) |
| ---: | ---: | ---: | ---: | ---: |
| 1000 | 0.327 | 0.000 | 0.013 | 1.655 |
| 10000 | 3.225 | 0.000 | 0.213 | 7.545 |
| 100000 | 26.538 | 0.000 | 3.513 | 97.316 |
| 300000 | 68.247 | 0.000 | 22.177 | 358.259 |

Three things stand out.

First, selecting columns from the column-major frame rounds to zero at
every size, because it is a `select-keys` on a ten-entry map that
never looks at a row. It is not merely faster; its cost does not depend
on the number of rows at all. Selecting from the row-major frame grows
in step with the rows, as it must.

Second, filtering is the other way round, and by a wide margin. The
row-major filter is the cheapest thing in the table, since the rows are
already the maps the predicate wants. The column-major filter is
roughly thirty times slower, and all of that is the cost of
manufacturing a map for every row just so that the predicate can look
at one key in it.

Third, and this is the point of the exercise, there is no answer to
"which layout is faster?". If a program selects a hundred times for
every filter, column-major wins by a mile. If it filters as often as it
selects, row-major wins. Anyone who tells you that one of these is "the
fast one" is telling you about their workload, not yours.

### Why the first table was wrong

The table above is not the first one this experiment produced. The
first version used the count-only warm-up and showed the 100,000-row
select on the row-major frame taking 270 milliseconds: ten times the
data of the row above it, fifty-five times the time. That is the kind
of number that sends people off to rewrite code. Looking at the raw
timings instead of the median told a different story:

```clojure
(:times-ms (b/bench {:warmup-ms 500} (f/select by-row [:c0 :c1 :c2])))
;; => [136.9 287.9 258.3 49.9 52.1 46.9 20.6 20.2 21.0 20.6 20.2]
```

The runs get faster as they go. The JIT compiler was still working
through the hot methods during the timed runs, and once it finished, a
run cost twenty milliseconds, which is exactly ten times the
10,000-row figure. The algorithm was linear all along; the
measurement was not. Even with a two-second warm-up, one run in three
of the experiment above still produced the slow figure for that one
cell (21.6, 267.8 and 26.5 milliseconds on three consecutive runs),
because the JIT does its work on its own schedule and a fresh JVM is a
noisy place. Two rules follow. Look at the distribution, not just the
summary, whenever a number surprises you. And run the whole experiment
more than once before believing any single cell of it.

## Where does the time go?

A benchmark says how long something takes. A **profiler** says where
the time went, which is what you need when you have decided something
is too slow and want to know what to change. Real profilers sample the
call stack hundreds of times a second; ours is cruder and more
transparent. It wraps a function so that each call is counted and
timed, and it keeps the totals in one place.

```clojure
(def stats
  "Everything measured so far: a map from label to {:calls n :nanos total}.
   This has to be an atom because calls happen all over the program, at
   different times, and they all need to add to the same totals."
  (atom {}))

(defn reset-stats!
  "Forget everything measured so far."
  []
  (reset! stats {}))

(defn record!
  "Add one call to `label` that took `nanos` nanoseconds."
  [label nanos]
  (swap! stats update label
         (fnil (fn [entry] (-> entry (update :calls inc) (update :nanos + nanos)))
               {:calls 0 :nanos 0})))
```

This is the second atom in the chapter, and it is there for the
opposite reason from the first. The benchmark's sink exists so that
nobody will look at it. `stats` exists so that everyone can: a profiled
function deep inside some computation has no way to return its timing
to the report function that will run later, so the timing has to be
left somewhere both of them can reach. `swap!` applies a function to
the atom's current value and stores the result, retrying if another
thread got there first, so it is safe to profile code that runs in
parallel. `fnil` supplies a starting entry the first time a label is
seen, which saves an `if` and is a trick worth knowing.

```clojure
(defmacro timed
  "Evaluate `body`, recording how long it took under `label`, even if
   it throws."
  [label & body]
  `(let [start# (System/nanoTime)]
     (try
       ~@body
       (finally
         (record! ~label (- (System/nanoTime) start#))))))

(defn profiled
  "A function that behaves like `f` but records every call under `label`."
  [label f]
  (fn [& args]
    (timed label (apply f args))))
```

`timed` is a macro for the same reason `bench` was: it has to run code
that was handed to it. The `start#` is an **auto-gensym**, a name that
the backquote replaces with a unique symbol so that it cannot collide
with a `start` in the caller's code. The `try`/`finally` means a call
that throws is still recorded; without it, the profile of a function
that fails half the time would be missing half its calls.
`profiled` is then an ordinary function: given a label and a function,
it returns a new function that times every call and passes everything
else through.

Wrapping a function value is only useful if callers call the wrapper,
and callers do not call function values, they call **vars**. A var is
the box that `defn` puts a function into; a call like `(row-at columns i)`
looks in the box at run time. [Chapter 9](../09-protocols/) used this to
replace a function during a test. We use it to put a profiled version
in the box and remember the original in the var's metadata:

```clojure
(defn profile-var!
  "Replace the function held by var `v` with a profiled version of
   itself, labelled with the var's name. Does nothing if `v` is already
   profiled. Returns `v`."
  [v]
  (when-not (::original (meta v))
    (let [f @v]
      (alter-meta! v assoc ::original f)
      (alter-var-root v (constantly (profiled (symbol v) f)))))
  v)

(defn unprofile-var!
  "Put back the function that `v` held before `profile-var!`. Returns `v`."
  [v]
  (when-let [f (::original (meta v))]
    (alter-var-root v (constantly f))
    (alter-meta! v dissoc ::original))
  v)
```

`@v` reads the function out of the var, `alter-var-root` replaces it,
and `alter-meta!` attaches the original under a namespaced key so that
`unprofile-var!` can find it and so that profiling a var twice does not
wrap the wrapper. The report sorts the totals:

```clojure
(defn report
  "What has been measured so far: one map per label with :calls,
   :total-ms and :mean-ms, sorted so the biggest total comes first."
  []
  (->> @stats
       (map (fn [[label {:keys [calls nanos]}]]
              {:label label
               :calls calls
               :total-ms (/ nanos 1e6)
               :mean-ms (/ nanos calls 1e6)}))
       (sort-by :total-ms >)
       vec))

(defn print-report
  "Print `report` as a table."
  []
  (println (format "%-40s %10s %12s %12s" "label" "calls" "total ms" "mean ms"))
  (doseq [{:keys [label calls total-ms mean-ms]} (report)]
    (println (format "%-40s %10d %12.3f %12.6f" (str label) calls total-ms mean-ms))))
```

Now we can ask the question the table raised: when the column-major
filter spends 97 milliseconds on 100,000 rows, how much of that is
`row-at`?

```clojure
(p/profile-var! #'cols/row-at)
(f/filter-rows by-col even-first?)   ; after warming up
(p/print-report)
;; label                                         calls     total ms      mean ms
;; sdx.perf.cols/row-at                         100000       70.588     0.000706
```

One hundred thousand calls, seven tenths of a microsecond each,
seventy milliseconds in all out of a call that took about 120. So
building row maps is most of the cost but not all of it; the rest is
the predicate, `filterv`, and pulling the kept indexes out of ten
columns. If we wanted the column-major filter to be competitive, this
is where we would start: a predicate that took the column vectors and
an index instead of a map would skip the allocation entirely. The
exercises take that up.

But look again at the numbers. The unprofiled filter takes 97
milliseconds and the profiled one 120, so wrapping `row-at` added
about 230 nanoseconds per call, which is a third of the time we then
attributed to it. Profiling an identity function makes the effect
stark:

```clojure
(b/bench {} (dotimes [i 100000] (ident i)))
;; => {:median-ms 0.032 ...}          ; unprofiled
(p/profile-var! #'ident)
(b/bench {} (dotimes [i 100000] (ident i)))
;; => {:median-ms 15.902 ...}         ; profiled: 500 times slower
```

Two calls to `nanoTime`, a `swap!` on an atom, and an `apply` over a
rest-args sequence cost far more than the function they are measuring.
This is the **observer effect** of profiling: the act of measuring
changes what is measured, and the smaller the function, the larger the
distortion. A profiler is a good guide to where big chunks of time go
and a poor one for anything that takes less than a microsecond. Real
profilers reduce the distortion by sampling instead of wrapping, but
they cannot eliminate it.

One more subtlety, which the tests make explicit: a profiled function
that calls itself is timed once per call, and the outer call's time
includes the inner ones. The totals are **inclusive** times. Summing
them across labels can add up to more than the program's running time,
which confuses everyone the first time they see it.

The tests in `test/sdx/perf/profile_test.clj` reset the statistics
before each test, check that wrapping is transparent, and check that
unwrapping really restores the original:

```clojure
(deftest timed-records-even-when-the-body-throws
  (is (thrown? ArithmeticException (p/timed :boom (/ 1 0))))
  (is (= 1 (get-in @p/stats [:boom :calls]))))

(deftest profiling-a-var-and-restoring-it
  (let [original slow-add]
    (p/profile-var! #'slow-add)
    (testing "callers see the same results"
      (is (= 5 (slow-add 2 3)))
      (is (= 7 (slow-add 3 4))))
    (testing "and the calls were recorded under the var's name"
      (let [{:keys [calls total-ms mean-ms]} (get-in (into {} (map (juxt :label identity)) (p/report))
                                                     ['sdx.perf.profile-test/slow-add])]
        (is (= 2 calls))
        (is (>= total-ms 2.0))
        (is (>= mean-ms 1.0))))
    (testing "profiling twice does not wrap twice"
      (p/profile-var! #'slow-add)
      (slow-add 1 1)
      (is (= 3 (get-in @p/stats ['sdx.perf.profile-test/slow-add :calls]))))
    (testing "unprofiling restores the original function"
      (p/unprofile-var! #'slow-add)
      (is (identical? original slow-add))
      (slow-add 1 1)
      (is (= 3 (get-in @p/stats ['sdx.perf.profile-test/slow-add :calls]))))))
```

## What to measure

The tools in this chapter are small, but using them well is mostly a
matter of judgment, and the judgment comes down to a few questions.

*Is this the operation that matters?* The table showed column-major
select as effectively free. If the program rarely selects, that is
irrelevant. Measure the operations the program actually performs, in
roughly the proportions it performs them.

*Is this the size that matters?* Costs that are invisible at a thousand
rows dominate at a million, and not always in the direction big-O
notation predicts: a cache miss costs the same whether or not the
algorithm is linear, and a program whose working set stops fitting in
memory changes behavior entirely. Measure at the sizes the program will
see.

*Is the measurement warm, repeated, and summarized honestly?* A cold
run measures the JIT compiler. A single run measures luck. A mean
measures outliers. Warm up, repeat, take the median, and keep the raw
timings in case the median hides something.

*What did measuring cost?* A profiler adds time to every call it
wraps, in proportion to how short the call is. Compare profiled and
unprofiled totals before trusting the breakdown.

Intuition fails at all four of these, in predictable ways. We
overestimate the cost of things we can see (a loop in our own code) and
underestimate the cost of things we cannot (allocation, garbage
collection, the compiler's warm-up). We remember a number from one run
and treat it as a fact. The remedy is not to think harder; it is to
measure, and to be suspicious of the first number you get.

## Summary

- A protocol separates what a data structure can do from how it is
  stored, which is what makes it possible to try two storage layouts
  against the same tests and the same benchmarks.
- Row-major storage makes row-at-a-time work cheap and column-at-a-time
  work expensive; column-major storage is the reverse. Neither is
  faster in general; the workload decides.
- Immutable vectors let the column-major `select` return a new frame
  that shares the old frame's columns without copying anything.
- A fair timing warms the code up first, repeats the measurement, and
  summarizes with the median, because timing distributions have a hard
  floor and a long tail.
- The JIT compiler makes the first seconds of a JVM program
  unrepresentative. Warm up by time as well as by count, and look at
  the raw timings whenever a result surprises you.
- A macro is the right tool when you need control over whether or when
  an argument is evaluated; keep it thin and let a function do the work.
- A profiler needs a shared mutable place to accumulate its counts,
  which is one of the few jobs an atom is clearly right for.
- Measuring has a cost that falls hardest on the smallest functions.
  Profile totals are inclusive and distorted; use them to find the big
  chunks, not to split hairs.

## Exercises

1. **Column-wise predicates.** Add a `filter-cols` operation to the
   protocol that takes a column name and a predicate on values, so that
   the column-major frame can filter without building any row maps.
   Benchmark it against `filter-rows` on both layouts.

2. **Chunked rows.** The row-major `select` spends its time building
   100,000 small maps. Change `RowFrame` to keep rows as vectors in a
   fixed column order instead of maps, with the names stored once. How
   does `select` change, and what happens to `filter-rows`?

3. **Mean and standard deviation.** Extend `bench*` to report the mean
   and standard deviation alongside the median. Run it on an operation
   that triggers garbage collection and compare the mean and the median.
   Which would you put in a report?

4. **Warm-up detection.** Instead of a fixed warm-up time, keep running
   until the last five timings are all within ten percent of each
   other, with an upper limit. Does this make the experiment faster,
   slower, or more reliable?

5. **Exclusive time.** Change the profiler so that time spent in nested
   profiled calls is subtracted from the outer call's total. You will
   need to track the current call stack; where should that live, and
   what happens if profiled functions run on several threads?

6. **Sampling.** Write a profiler that does not wrap anything but
   instead starts a background thread which, every millisecond, records
   the top frame of the main thread's stack trace
   (`(.getStackTrace thread)`). Compare its overhead and its answers
   with the wrapping profiler.

7. **Bigger frames.** Run the experiment with one hundred columns
   instead of ten. Which cells of the table change, and does the
   relative ranking of the layouts change with them?

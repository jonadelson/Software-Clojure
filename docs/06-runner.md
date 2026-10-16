---
title: "Running Tests"
nav_order: 6
lede: "A test runner that finds tests by name, classifies what each one does, and uses metadata to decide what to run."
---

Every chapter in this book ends with tests, and every one of those tests
is run by a program rather than by hand. A test runner is a small tool,
but it has to solve a problem that comes up again and again when we build
tools: how does a program find and run code that it has never seen? The
runner cannot know in advance what the tests are called, what they check,
or whether one of them is about to throw an exception. It needs a
convention that test writers agree to follow, and it needs a way to look
at a program from the outside, the way a debugger or a documentation
generator does.

Clojure makes that outside view unusually easy to get. A namespace is a
map from names to **vars**, a var is a box with a function (or any other
value) in it, and both namespaces and vars carry **metadata** that any
program can read. This chapter builds a runner in two stages. The first
finds every function whose name starts with `test-`, calls it, and sorts
the results into passes, failures, and errors. The second uses metadata on
the test functions to decide which ones to run and to run set-up and
tear-down code around each one. At the end we look at `clojure.test`,
which we have been using all along, and find that it works the same way.

## What a test runner has to do

A runner has three jobs: find the tests, run each one in a way that
nothing a test does can stop the runner, and report what happened. The
first job needs a convention, and ours is the simplest one that works: a
test is a public function that takes no arguments and whose name starts
with `test-`. It checks what it needs to check with `assert`, which
throws an `AssertionError` when its condition is false and does nothing
otherwise.

That gives every test three possible outcomes. It **passes** if it
returns normally. It **fails** if an assertion inside it throws. And it
**errors** if it throws anything else, for example because of a typo, a
missing file, or a division by zero. The distinction between failing and
erroring matters: a failure says the code under test gave the wrong
answer, while an error says the test could not even ask the question.
Both need fixing, but you start looking in different places.

To exercise the runner we need something for it to find, so the chapter's
tests include a sample namespace with a bit of everything:

```clojure
(ns sdx.runner.sample.basic
  "A namespace of tests for the runner to find. Two pass, one fails,
   one errors, and two are not tests at all.")

(defn test-addition
  "Passes: the assertion holds."
  []
  (assert (= 4 (+ 2 2))))

(defn test-subtraction
  "Fails: the assertion is false."
  []
  (assert (= 1 (- 2 2)) "subtraction is broken"))

(defn test-division
  "Errors: dividing by zero throws before any assertion runs."
  []
  (/ 1 0))

(defn test-strings
  "Passes."
  []
  (assert (= "ab" (str "a" "b"))))

(defn helper
  "Not a test: its name does not start with test-."
  []
  (assert false "the runner must not call helper"))

(defn- test-private
  "Not a test either: private vars are not in ns-publics."
  []
  (assert false "the runner must not call test-private"))
```

The last two functions are traps. If the runner ever calls `helper` or
`test-private`, we will know, because they fail loudly.

## Finding tests in a namespace

When Clojure loads the file above, it creates a namespace object and, for
each `defn`, a var inside it. `ns-publics` hands us the public ones as a
map from symbol to var:

```clojure
(keys (ns-publics 'sdx.runner.sample.basic))
;; => (helper test-subtraction test-division test-strings test-addition)
```

A var is what `defn` and `def` create. It is a named, mutable reference
whose current value is usually a function. Writing `#'test-addition`
gives you the var itself rather than its value, and `@v` (short for
`(deref v)`) gets the value back out. Most of the time we never think
about vars because calling `(test-addition)` looks the value up for us,
but a runner has to think about them, because the var is the thing that
carries the name.

It carries more than the name. Every var has a map of metadata attached
to it, which `meta` returns:

```clojure
(meta #'sdx.runner.sample.basic/test-addition)
;; => {:arglists ([]), :line 5, :file "sdx/runner/sample/basic.clj",
;;     :name test-addition, :ns #object[clojure.lang.Namespace ...], :doc "..."}
```

The compiler fills in `:name`, `:ns`, `:file`, `:line`, `:arglists`, and
`:doc` for us. Later in the chapter we will add keys of our own. For now
we only need the name:

```clojure
(defn var-name
  "The symbol a var was defined with, without its namespace."
  [v]
  (:name (meta v)))

(defn find-tests
  "Every public var in the namespace `ns-sym` whose name starts with
   \"test-\", sorted by name so that runs are repeatable."
  [ns-sym]
  (->> (ns-publics ns-sym)
       vals
       (filter #(str/starts-with? (name (var-name %)) "test-"))
       (sort-by var-name)))
```

Two details are worth a sentence each. `name` turns the symbol
`test-addition` into the string `"test-addition"` so that we can check
its prefix. And we sort, because `ns-publics` returns a hash map whose
order depends on the hash codes of the symbols; sorting makes every run
produce the same report in the same order, which is what we want from a
tool whose output people compare from one run to the next.

```clojure
(map var-name (find-tests 'sdx.runner.sample.basic))
;; => (test-addition test-division test-strings test-subtraction)
```

Neither trap appears. `helper` has the wrong name, and `test-private` is
private, so `ns-publics` never shows it to us. That second rule is a
feature: a `defn-` is a promise that nothing outside the namespace will
call it, and the runner keeps that promise.

## Running one test

The second job is to call a test without letting it take the runner down
with it. That is what `try` is for, but the shape of the `catch` clauses
is where the design lives:

```clojure
(defn classify
  "Call `thunk` with no arguments and report how it ended:
   {:outcome :pass}, {:outcome :fail ...} if an assertion failed, or
   {:outcome :error ...} if anything else was thrown."
  [thunk]
  (try
    (thunk)
    {:outcome :pass}
    (catch AssertionError e
      {:outcome :fail :message (.getMessage e)})
    (catch Throwable e
      {:outcome :error :message (str e)})))
```

Catch clauses are tried in order and the first one whose type matches
wins, so the specific `AssertionError` has to come before the catch-all.
The catch-all is `Throwable` rather than the more usual `Exception`
because `AssertionError` is not an `Exception` at all: in Java's
hierarchy it is an `Error`, the branch reserved for things like running
out of memory or stack. If we only caught `Exception`, an assertion
failure in a test would escape the runner, which is precisely the opposite
of what we want. `Throwable` is the root of both branches and catches
everything.

`classify` takes a function of no arguments (a **thunk**) rather than a
var. That is a small decision with a large payoff: it means we can test
the classification logic with inline functions and no sample namespace at
all, and in the second stage we will reuse it by handing it a function
that wraps the test in set-up and tear-down code. Running a var is then a
one-liner, because a var that holds a function can be called directly,
with the same effect as calling its value:

```clojure
(defn run-test
  "Run the test held in var `v` and return its result, tagged with the
   test's name."
  [v]
  (assoc (classify v) :test (var-name v)))
```

```clojure
(run-test #'sdx.runner.sample.basic/test-addition)
;; => {:outcome :pass, :test test-addition}
(run-test #'sdx.runner.sample.basic/test-division)
;; => {:outcome :error, :message "java.lang.ArithmeticException: Divide by zero",
;;     :test test-division}
```

The tests for this stage live in `test/sdx/runner/simple_test.clj`. Here
is the one for `classify`, which is exactly the kind of test that the
thunk design makes possible:

```clojure
(deftest classifying-outcomes
  (is (= {:outcome :pass} (r/classify (fn [] 42))))
  (testing "a false assert is a failure, with its message"
    (let [{:keys [outcome message]} (r/classify (fn [] (assert false "nope")))]
      (is (= :fail outcome))
      (is (str/includes? message "nope"))))
  (testing "any other exception is an error"
    (is (= :error (:outcome (r/classify (fn [] (throw (ex-info "boom" {})))))))
    (is (= :error (:outcome (r/classify (fn [] (/ 1 0))))))))
```

> **Assertions can be switched off.**
> `assert` checks the dynamic var `*assert*` at compile time, and if it
> is false the whole form compiles to nothing. Production builds
> sometimes do this for speed. A test suite built on `assert` therefore
> has to be compiled with assertions on, which they are by default; just
> be aware that the behaviour of our runner depends on a compiler flag.

## Running them all and reporting

With the pieces in place, running a namespace is a `mapv` over the vars
we found:

```clojure
(defn run-tests
  "Run every test in `ns-sym`, in name order, and return the results."
  [ns-sym]
  (mapv run-test (find-tests ns-sym)))
```

`mapv` rather than `map` is deliberate. `map` returns a **lazy sequence**,
one whose elements are not computed until something asks for them. That
is a fine way to describe an infinite series of numbers, but our tests
have side effects (they print, they create files, they fail), and we want
them to run now, in order, exactly once. `mapv` builds a vector eagerly
and gives us that.

Notice what `run-tests` returns: data, not text. A vector of small maps is
easy to count, filter, compare in a test, or serialize, and it leaves the
decision about how to display results to someone else. Counting is
`frequencies`, with a merge to make sure the outcomes that did not occur
show up as zero rather than going missing:

```clojure
(defn summarize
  "Count how many results had each outcome, e.g. {:pass 2 :fail 1 :error 0}."
  [results]
  (merge {:pass 0 :fail 0 :error 0}
         (frequencies (map :outcome results))))
```

Displaying is the only place in the stage that prints anything:

```clojure
(defn report
  "Print one line per result followed by a summary line, and return the
   summary. `outcomes` lists the outcomes to show on the summary line,
   in order; it defaults to pass, fail, and error."
  ([results]
   (report [:pass :fail :error] results))
  ([outcomes results]
   (doseq [{:keys [test outcome message]} results]
     (println (format "%-6s %s%s"
                      (name outcome)
                      test
                      (if message
                        (str ": " (first (str/split-lines message)))
                        ""))))
   (let [summary (summarize results)]
     (println (str/join "  " (map #(str (name %) " " (get summary % 0)) outcomes)))
     summary)))
```

`report` takes the list of outcomes to print as a parameter instead of
hard-coding it, because the set of outcomes belongs to whoever is doing
the classifying, and the next stage is going to add one. Only the first
line of each message is shown, since `assert` builds messages that span
two lines and a report is easier to scan when each test takes one.

Because printing is confined to this one function, testing it is a matter
of capturing standard output with `with-out-str`, which rebinds `*out*`
to a string buffer for the duration of its body:

```clojure
(deftest reporting
  (let [results (r/run-tests 'sdx.runner.sample.basic)
        output (with-out-str (r/report results))
        lines (str/split-lines output)]
    (is (= 5 (count lines)))
    (is (= "pass   test-addition" (first lines)))
    (is (str/starts-with? (second lines) "error  test-division: java.lang.ArithmeticException"))
    (is (= "fail   test-subtraction: Assert failed: subtraction is broken" (nth lines 3)))
    (is (= "pass 2  fail 1  error 1" (last lines)))))
```

That is the whole first stage: about forty lines, in
`src/sdx/runner/simple.clj`.

## Choosing what to run with metadata

A real test suite soon has tests that take minutes, tests that only work
on one operating system, and tests for a bug you are in the middle of
fixing and would like to run on their own. The runner needs a way to be
told which tests to run, and the question is where that information
should live.

It could live in the names, with a convention like `slow-test-...`, but
then every piece of information needs its own prefix and the names become
a mess. It could live in a configuration file, but then the information
is far from the code it describes and the two drift apart. Or it could
live on the test itself, as metadata, which is what we will do.

Clojure lets you attach metadata to a symbol when you write it. The form
`^{:skip true}` placed before a name adds those keys to its metadata, and
`^:skip` is shorthand for the same thing. `defn` copies the metadata from
the name onto the var it creates, so a test that should be skipped looks
like this:

```clojure
(defn ^:skip test-slow
  "Skipped: would take far too long if it ran."
  []
  (swap! log conj :slow)
  (Thread/sleep 100000))
```

and `(:skip (meta #'test-slow))` is `true`. We will support two flags:
`:skip` means "do not run this", and `:only` means "run this and nothing
else", which is the one you want while chasing a single failure. The
decision for one var depends on that var's metadata and on whether
*any* var in the namespace is marked `:only`:

```clojure
(defn- decision
  "Whether one test var should :run or be :skip-ped. `any-only?` says
   whether any test in the namespace is marked ^:only."
  [any-only? v]
  (let [m (meta v)]
    (cond
      (:skip m)                        :skip
      (and any-only? (not (:only m)))  :skip
      :else                            :run)))

(defn plan
  "Pair each test var with the decision about it, [var :run] or
   [var :skip], keeping the vars in their original order."
  [vars]
  (let [any-only? (boolean (some #(:only (meta %)) vars))]
    (mapv (fn [v] [v (decision any-only? v)]) vars)))
```

The order of the `cond` branches encodes a rule: `:skip` wins over
`:only`, so a test marked with both is skipped. The other choice would
be defensible too; what matters is that the rule is written down in one
place and tested. And `plan` returns a plan, a vector of pairs, rather
than running anything. We can inspect it, print it, or check it in a test
without a single test function being called:

```clojure
(deftest planning-with-only
  (testing "one ^:only test silences the others, and ^:skip beats ^:only"
    (is (= '[[test-first :skip] [test-fourth :skip] [test-second :run] [test-third :skip]]
           (decisions 'sdx.runner.sample.only)))))
```

(`decisions` is a two-line helper in the test file that replaces each var
in the plan with its name.) The sample namespace this runs against has
`test-second` marked `^:only` and `test-fourth` marked with both flags.

## Setup and teardown

Many tests need the same preparation: a temporary directory, a connection,
a known starting state. Writing it at the top of every test is tedious and
error-prone, and cleaning up at the bottom is worse, because a failing
assertion skips everything after it. Runners therefore let you supply a
set-up function to run before each test and a tear-down function to run
after it.

We find those functions the same way we find everything else, by looking
at metadata. A var marked `^:setup` is the set-up hook, and one marked
`^:teardown` is the tear-down hook; their names do not matter:

```clojure
(defn find-hook
  "The first public var in `ns-sym` whose metadata has `flag` set
   (for example :setup), or nil if there is none."
  [ns-sym flag]
  (->> (ns-publics ns-sym)
       vals
       (filter #(flag (meta %)))
       first))
```

Running a test with hooks around it is where the thunk design of
`classify` pays off. We build a function that calls set-up, then the
test, then tear-down, and hand that whole thing to `classify`:

```clojure
(defn run-test
  "Run the test in var `v` with `setup` called before it and `teardown`
   after it. Teardown runs even if the test fails or throws. A failure in
   either hook is reported as the test's own result."
  [{:keys [setup teardown]} v]
  (assoc (simple/classify
          (fn []
            (when setup (setup))
            (try
              (v)
              (finally
                (when teardown (teardown))))))
         :test (simple/var-name v)))
```

The `try` has no `catch`, only a `finally`. A `finally` block runs
whether its body returned or threw, and when the body threw, the
exception carries on afterwards as if the `finally` were not there. So a
failing assertion in `(v)` still runs tear-down, and then still reaches
`classify`, which reports it as a failure. If set-up itself throws, the
test is never called and tear-down is never run, on the theory that there
is nothing to tear down; `classify` reports that as an error for the
test, which is where the person reading the report will look first.

Putting the stage together means planning, running what the plan allows,
and recording what it skipped:

```clojure
(defn run-tests
  "Run the tests in `ns-sym` that their metadata allows, with the
   namespace's setup and teardown hooks around each one. Skipped tests
   appear in the results with outcome :skip."
  [ns-sym]
  (let [hooks {:setup (find-hook ns-sym :setup)
               :teardown (find-hook ns-sym :teardown)}]
    (mapv (fn [[v action]]
            (if (= action :run)
              (run-test hooks v)
              {:test (simple/var-name v) :outcome :skip}))
          (plan (simple/find-tests ns-sym)))))

(defn report
  "Print results the way stage 1 does, with skips on the summary line."
  [results]
  (simple/report [:pass :fail :error :skip] results))
```

Skipped tests are results like any other, with their own outcome, so
`summarize` counts them without being changed and `report` only needs to
be told that `:skip` exists. Here the parameter we gave `report` earlier
does its job.

To test the hooks we need to see the order in which things ran, and that
needs a little bit of state. The sample namespace keeps a log in an atom,
because the hooks and the tests are separate functions called one after
another and an atom is the simplest thing they can all append to:

```clojure
(def log
  "What has run so far. An atom because the hooks and the tests are
   called one after another and have to share it."
  (atom []))

(defn ^:setup before-each
  "Runs before every test."
  []
  (swap! log conj :setup))

(defn ^:teardown after-each
  "Runs after every test, even one that failed."
  []
  (swap! log conj :teardown))
```

The test in `test/sdx/runner/meta_test.clj` resets the log, runs the
namespace, and checks both the outcomes and the order:

```clojure
(deftest hooks-wrap-every-test
  (reset! sample-meta/log [])
  (let [results (m/run-tests 'sdx.runner.sample.meta)]
    (is (= [:pass :skip :fail] (map :outcome results)))
    (testing "teardown ran after the failing test, and the skipped test never ran"
      (is (= [:setup :one :teardown :setup :two :teardown] @sample-meta/log)))))
```

`:slow` never appears in the log, and `:teardown` appears after `:two`
even though `test-two` fails.

## A command-line runner

A test runner is something you run from a shell, so the last file in
`src/sdx/runner/` is an entry point:

```clojure
(defn run-namespaces
  "Load and run each namespace named in `ns-names`, printing a report
   for each. Returns the combined summary."
  [ns-names]
  (let [summaries (mapv (fn [n]
                          (let [ns-sym (symbol n)]
                            (require ns-sym)
                            (println "==" n)
                            (meta/report (meta/run-tests ns-sym))))
                        ns-names)]
    (apply merge-with + {:pass 0 :fail 0 :error 0 :skip 0} summaries)))

(defn -main
  "Run the tests in the named namespaces and exit non-zero on failure."
  [& ns-names]
  (let [{:keys [fail error]} (run-namespaces ns-names)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
```

`require` is usually seen inside an `ns` declaration, but it is an
ordinary function, and calling it at run time with a symbol built from a
command-line string is how the runner loads code it has never seen.
`merge-with +` adds up the summary maps key by key. And `System/exit`
lives in `-main` rather than in `run-namespaces`, because a function that
ends the process cannot be called from a test; `run-namespaces` returns
the totals and the test checks them.

The sample namespaces live under `test/`, so the `:dev` alias puts them
on the classpath:

```
$ clojure -M:dev -m sdx.runner.main sdx.runner.sample.basic sdx.runner.sample.meta
== sdx.runner.sample.basic
pass   test-addition
error  test-division: java.lang.ArithmeticException: Divide by zero
pass   test-strings
fail   test-subtraction: Assert failed: subtraction is broken
pass 2  fail 1  error 1  skip 0
== sdx.runner.sample.meta
pass   test-one
skip   test-slow
fail   test-two: Assert failed: two fails
pass 1  fail 1  error 0  skip 1
```

The exit status is 1, because two tests failed and one errored.

## How clojure.test does it

We have been writing tests with `deftest` since the first chapter, and
it is worth asking what that macro actually does. The answer is: the same
thing we just did, with one twist. `deftest` creates a var, and it stores
the body of the test as a function in that var's metadata under the key
`:test`:

```clojure
(keys (meta #'sdx.runner.meta-test/finds-deftests))
;; => (:test :line :column :file :name :ns)
(fn? (:test (meta #'sdx.runner.meta-test/finds-deftests)))
;; => true
```

So a `clojure.test` runner does not look at names at all. It looks for
vars that have a `:test` key, which is exactly the kind of search we
wrote for `:setup` and `:skip`:

```clojure
(defn find-deftests
  "The vars in `ns-sym` that clojure.test's deftest created. Each one
   carries its test body as a function in its :test metadata."
  [ns-sym]
  (->> (ns-publics ns-sym)
       vals
       (filter #(:test (meta %)))
       (sort-by simple/var-name)))
```

The chapter's own test file is full of `deftest`s, so it can check this
on itself:

```clojure
(deftest finds-deftests
  (testing "clojure.test marks its tests with :test metadata, so we can find them too"
    (let [found (m/find-deftests 'sdx.runner.meta-test)]
      (is (some #{'finds-deftests} (map simple/var-name found)))
      (is (every? #(fn? (:test (meta %))) found)))))
```

Using metadata rather than names has a nice consequence: any var can
carry a `:test`, so you can attach a test to an ordinary function by
putting `^{:test (fn [] ...)}` on its name, and `clojure.test` will find
and run it. `clojure.test/test-var` plays the role of our `run-test`,
`clojure.test/run-tests` plays the role of `run-namespaces`, and the
`:once` and `:each` fixtures registered with `use-fixtures` are its
set-up and tear-down.

The twist is in what a failure looks like. Our tests stop at the first
false `assert`, because `assert` throws. The `is` macro does not throw.
It reports the failure to a function held in a dynamic var and then lets
the test continue, so one test can report several failures, and the
summary line counts assertions as well as tests ("Ran 16 tests containing
34 assertions"). That gives more information per run at the cost of a
runner that is three or four times the size of ours, because every `is`
has to know how to report, and the reporting has to be redirectable so
that different tools can present results differently. Whether the extra
information is worth the extra machinery is a judgement call; for a
runner that fits in a chapter, stopping at the first failure is the right
one.

## Summary

- A test runner needs a convention for recognizing tests, a way to call
  them that survives whatever they do, and a report. Ours uses a name
  prefix, `try` with `Throwable`, and a vector of result maps.
- A namespace is a map from symbols to vars, and `ns-publics` lets a
  program read it. A var is a named box whose value is usually a
  function; it can be called directly, or dereferenced with `@`.
- Vars carry metadata. The compiler records the name, file, and line;
  `^:flag` on a name adds whatever keys you like, and the runner reads
  them to decide what to run and what to run around it.
- Sort anything that comes out of a hash map before showing it to people,
  so that runs are comparable.
- Keep side effects at the edges: `run-tests` returns data, `report`
  prints, and `-main` is the only function allowed to end the process.
  Everything else can be tested by comparing values.
- `finally` is how tear-down runs no matter what the test did.
- `clojure.test` is the same design with `:test` metadata in place of a
  name prefix, and assertions that record failures instead of throwing.

## Exercises

1. **Timing.** Record how long each test took in its result map, using
   `System/nanoTime` before and after the call, and make `report` print
   the slowest test at the end. Where is the right place to measure, in
   `classify` or in `run-test`, and why?

2. **Tags.** Replace the boolean `:skip` flag with a set of tags, such as
   `^{:tags #{:slow :network}}`, and let `plan` take a set of tags to
   exclude. Write the test for a test that has no tags at all.

3. **Expected failures.** Add a `^:fails` flag meaning "this test is
   known to fail". A failing test marked `:fails` should count as a
   pass, and a passing one should be reported as an unexpected pass. How
   many places in the code have to change?

4. **Several hooks.** `find-hook` returns the first var it finds. Change
   it to return all of them, run set-up hooks in name order, and run
   tear-down hooks in reverse name order. Why reverse?

5. **Private tests.** Change `find-tests` to use `ns-interns`, which
   includes private vars, and see which tests in this chapter break.
   Would you keep the change?

6. **Running `deftest`s.** Extend `run-tests` so that a var with `:test`
   metadata is run by calling that function. Then write a `deftest` that
   uses `is` with a false condition and see what our runner reports.
   Explain the result.

7. **Metadata on the namespace.** Namespaces have metadata too:
   `(meta (the-ns 'sdx.runner.sample.meta))`. Use it to let a whole
   namespace be marked `^:skip`, and decide what should happen when a
   skipped namespace contains a test marked `^:only`.

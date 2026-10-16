---
title: "An Interpreter"
nav_order: 7
lede: "A small language whose programs are Clojure vectors, and an evaluator that grows one operation at a time."
---

Every tool we have built so far has taken data as its input: file paths,
glob patterns, the names of tests. This chapter's tool takes a *program*
as its input and runs it. That sounds like a much bigger job, and
historically it was, because the first thing an interpreter for a
language like C or Python has to do is turn text into a tree. But if we
let programs be written as trees in the first place, the hard part
disappears, and what is left is small enough to understand completely.

Our programs will be Clojure vectors. `[:add 1 2]` is a program that
adds one and two. `[:set :x 5]` stores five in a variable. The first
element of every vector names an operation and the rest are its
arguments, which may themselves be vectors. This is the same shape as
Clojure itself, which is no coincidence: Clojure's reader turns text into
lists and vectors and its evaluator walks them, exactly as ours will.

We build the interpreter in stages. The first is a calculator with no
variables, which shows the basic recursive shape of evaluation. The
second adds an environment for variables and control flow, and makes a
design decision about how new operations are added. The third and fourth
add arithmetic and arrays without changing a line of the core, which is
the test of whether the decision was a good one. Finally we read programs
from files, so that the interpreter can be run from the command line.
The next chapter, [Functions and Closures](../08-closures/), extends
what we build here.

## A calculator

The smallest useful version of the language has numbers and arithmetic.
Here is all of it:

```clojure
(ns sdx.interp.calc
  "Stage 1: a calculator. A program is a number, or a vector whose first
   element names an operation and whose remaining elements are programs.
   Evaluating a vector means evaluating its arguments and applying the
   operation to the results.")

(def ops
  "The operations the calculator knows, each an ordinary Clojure function
   of the evaluated arguments."
  {:add + :sub - :mul * :div quot :max max :min min})

(defn evaluate
  "Evaluate `expr` using the operations in `table`, which defaults to
   `ops`. Numbers evaluate to themselves. A vector is an operation
   applied to the values of its arguments."
  ([expr] (evaluate ops expr))
  ([table expr]
   (if (vector? expr)
     (let [[op & args] expr
           f (get table op)]
       (when-not f
         (throw (ex-info (str "unknown operation " op) {:op op :expr expr})))
       (apply f (map #(evaluate table %) args)))
     expr)))
```

```clojure
(evaluate 5)                      ;; => 5
(evaluate [:add 1 [:mul 2 3]])    ;; => 7
(evaluate [:pow 2 3])             ;; => throws "unknown operation :pow"
```

`evaluate` is recursive, and the recursion follows the shape of the
program. To evaluate `[:add 1 [:mul 2 3]]` it evaluates `1` (a number, so
itself), then evaluates `[:mul 2 3]` the same way to get `6`, and then
applies `+` to `1` and `6`. The operations are not special in any way:
`:mul` is just a key whose value is Clojure's own `*`. The whole
"language" is a map from keywords to functions, and the evaluator is a
tree walk that looks things up in it.

Making the table a parameter, with a one-argument version that supplies
the default, is a small decision that pays for itself immediately in the
tests (`test/sdx/interp/calc_test.clj`). A test can extend the language
without touching the source file:

```clojure
(deftest the-table-is-a-parameter
  (let [table (assoc calc/ops :pow (fn [base exp] (long (Math/pow base exp))))]
    (is (= 8 (calc/evaluate table [:pow 2 3])))))
```

## Why we cannot just evaluate everything

The calculator's rule is "evaluate all the arguments, then apply the
function". It is a good rule for arithmetic, and it is the wrong rule for
almost everything else. Suppose we want a conditional, and we add it to
the table in the obvious way:

```clojure
(deftest why-if-cannot-be-a-table-entry
  (let [table (assoc calc/ops :if (fn [test then else] (if test then else)))]
    (testing "it picks the right branch"
      (is (= 1 (calc/evaluate table [:if true 1 2]))))
    (testing "but both branches are evaluated before it gets to choose"
      (is (thrown? ArithmeticException
                   (calc/evaluate table [:if true 1 [:div 1 0]]))))))
```

The first assertion passes and the second one shows the problem. By the
time our `:if` function runs, `evaluate` has already computed both
branches, so a branch that was never meant to run has already divided by
zero. A loop is worse: a `:while` written this way would evaluate its
body exactly once, before the loop started, and never again. And a
`[:set :x 5]` that tried to evaluate its arguments would evaluate `:x`
and get... `:x`, a keyword, with no way to know it was meant as a name.

Operations like these need to see their arguments *before* evaluation and
decide for themselves what to do with them. In Lisp they are called
**special forms**, and the distinction between them and ordinary
functions is one of the oldest ideas in language design. Our second stage
is built around it: every operation receives the raw expression, and the
ones that want all their arguments evaluated simply ask for that.

## Where variables live

Before we can write `:set` and `:get` we have to decide what a variable
is. An **environment** is a mapping from names to values, and the
question is how the evaluator gets at it and how changes to it are
passed along.

There are two honest answers. The first is to make the environment an
immutable map and thread it through every evaluation: `evaluate` takes an
environment and an expression and returns *both* a value and a possibly
updated environment, and every operation that evaluates sub-expressions
has to pass the environment from one to the next. That design has real
advantages. Evaluation is a pure function, so two tests can never
interfere with each other, and you can keep every intermediate
environment and step backwards through a program's history for free. The
cost is noise: every operation returns a pair, every `:seq` is a
`reduce` that carries the environment along, and a mistake in the
threading, using the environment from before a sub-expression rather than
after it, is silent.

The second answer is to put the map in an **atom**, a mutable reference
whose value is read with `@` and changed with `swap!`. Then `evaluate`
returns only a value, and the environment changes in place as a side
effect. The signatures stay simple and the operations stay short. The
cost is that evaluation is no longer pure: a test has to start from a
fresh environment every time, and two evaluations that share an
environment can see each other's changes.

We choose the atom, for the reason the next chapter will make
concrete. Functions that remember things between calls, and functions
that call themselves, both need the environment to be a *place* that can
be shared and updated rather than a value that is copied. It is better to
make that choice now than to rewrite every operation later.

Whatever the environment is, the evaluator needs only two things from
it: look a name up, and bind a name to a value. We write those down as a
**protocol**, a named set of functions that a type promises to
implement, and give it one implementation, a **record** holding the atom:

```clojure
(defprotocol Env
  "What the interpreter needs from an environment: look a name up, and
   bind a name to a value."
  (lookup [env name] "The value bound to `name`; throws if it is unbound.")
  (assign! [env name value] "Bind `name` to `value` and return the value."))

(defrecord MapEnv [vars]
  Env
  (lookup [_ name]
    (let [m @vars]
      (if (contains? m name)
        (get m name)
        (throw (ex-info (str "unknown variable " name) {:name name})))))
  (assign! [_ name value]
    (swap! vars assoc name value)
    value))

(defn make-env
  "A fresh environment with no variables in it."
  []
  (->MapEnv (atom {})))
```

We met protocols and records in [Objects and Classes](../02-objects/).
Here the protocol is doing something slightly different from what it did
there: it is not choosing between several kinds of thing that exist now,
but drawing a line between the evaluator and the storage so that a
different kind of environment can be slotted in later without the
evaluator noticing. `lookup` uses `contains?` rather than `get` so that a
variable whose value is `nil` or `false` is still found, and an unbound
name is an error rather than a silent `nil`, because a typo in a variable
name is the most common mistake in any language and the interpreter
should say so.

```clojure
(def env (make-env))
(assign! env :x 5)   ;; => 5
(lookup env :x)      ;; => 5
(lookup env :y)      ;; => throws "unknown variable :y"
```

## Dispatching on the operation

The calculator looked operations up in a map. The second stage uses a
**multimethod** instead: a function whose implementation is chosen by
calling a dispatch function on the arguments. Our dispatch function
returns the keyword at the head of the expression:

```clojure
(defmulti do-op
  "Evaluate one operation: a vector whose first element is a keyword
   naming it. Dispatches on that keyword. Methods receive the environment
   and the whole unevaluated vector, and return the operation's value."
  (fn [_env expr] (first expr)))

(defmethod do-op :default [_ expr]
  (throw (ex-info (str "unknown operation " (first expr)) {:expr expr})))

(defn evaluate
  "Evaluate `expr` in `env` and return its value. Vectors are operations;
   anything else is a literal that evaluates to itself."
  [env expr]
  (if (vector? expr)
    (do-op env expr)
    expr))

(defn evaluate-all
  "Evaluate each of `exprs` in order and return their values as a vector."
  [env exprs]
  (mapv #(evaluate env %) exprs))
```

Why a multimethod rather than a map? A map lives in one place, and adding
an entry means editing that place, or, as in the calculator, passing a
different map around. A multimethod is open: a `defmethod` in any
namespace, in any file, adds a case, and the namespace that defined
`do-op` never hears about it. That is what lets the rest of this chapter
add arithmetic and arrays from the outside. The `:default` method is the
case that runs when no other matches, and it turns an unknown operation
into a clear error instead of a confusing one from deep inside Clojure.

`evaluate-all` is the "evaluate all the arguments" rule from the
calculator, packaged up for the operations that want it. It uses `mapv`
rather than `map` because `map` is lazy and our sub-expressions have side
effects: `[:print "a"]` had better print before `[:print "b"]`, and both
had better print even if nothing looks at their values.

Note what `evaluate` says about vectors: a vector is always code. There is
no way to write a literal vector in a program and have it come out as
data, which is why the array operations later in the chapter exist.
Lisp solved the same problem with `quote`; one of the exercises asks you
to do the same.

## Control flow

Six operations need to see their arguments before evaluation or need the
environment. They live in the core, next to `evaluate`:

```clojure
(defmethod do-op :seq [env [_ & exprs]]
  (last (evaluate-all env exprs)))

(defmethod do-op :set [env [_ name expr]]
  (assign! env name (evaluate env expr)))

(defmethod do-op :get [env [_ name]]
  (lookup env name))

(defmethod do-op :if [env [_ test then else]]
  (if (evaluate env test)
    (evaluate env then)
    (evaluate env else)))

(defmethod do-op :while [env [_ test body]]
  (while (evaluate env test)
    (evaluate env body)))

(defmethod do-op :print [env [_ & exprs]]
  (println (str/join " " (evaluate-all env exprs))))
```

Each method destructures the expression in its argument list, with `_`
standing in for the keyword we already dispatched on, so the shape of
each operation is visible at a glance: `:set` takes a name and an
expression, `:if` takes three expressions, `:seq` takes any number.

`:set` evaluates its second argument but not its first, which is the
whole reason it could not be a table entry. `:if` evaluates its test and
then exactly one of the other two. `:while` is Clojure's own `while`
macro, which expands to a `loop`/`recur` that re-evaluates the test each
time around; this only works because `evaluate` has side effects, since a
pure evaluation of the same test in the same environment would return the
same answer forever. And `:seq` evaluates everything and returns the last
value, so that a program can be a sequence of steps that also has a
result.

The tests in `test/sdx/interp/core_test.clj` use only these six
operations, which is enough to check each one's rule. The one for `:if`
uses an operation that does not exist to prove that the untaken branch
is never looked at:

```clojure
(deftest if-evaluates-one-branch
  (let [env (core/make-env)]
    (is (= :yes (core/evaluate env [:if true :yes [:explode]])))
    (is (= :no (core/evaluate env [:if false [:explode] :no])))
    (testing "nil and false are false; everything else is true"
      (is (= :no (core/evaluate env [:if nil :yes :no])))
      (is (= :yes (core/evaluate env [:if 0 :yes :no]))))))
```

Printing goes to `*out*`, so a test can capture it with `with-out-str`
just as the test runner's tests did:

```clojure
(deftest print-writes-to-out
  (is (= "x is 5\n"
         (with-out-str
           (core/evaluate (core/make-env)
                          [:seq [:set :x 5] [:print "x is" [:get :x]]])))))
```

## Adding operations without touching the core

The core knows how to sequence, assign, branch, loop, and print, but it
cannot add two numbers. Arithmetic lives in a separate namespace, and
that namespace is short, because every operation in it follows the
calculator's rule: evaluate everything, apply a function.

```clojure
(ns sdx.interp.ops
  "Arithmetic, comparison, and logic for the interpreter. None of these
   needs to see the environment or to control evaluation: each one
   evaluates all of its arguments and applies an ordinary Clojure
   function to the values, so they are defined from a table."
  (:require [sdx.interp.core :as core]))

(def fn-ops
  "Operations that are plain functions of their evaluated arguments."
  {:add + :sub - :mul * :div quot :mod mod
   :lt < :gt > :eq = :not not})

(doseq [[op f] fn-ops]
  (defmethod core/do-op op [env [_ & args]]
    (apply f (core/evaluate-all env args))))
```

The `doseq` deserves a look. `defmethod` is usually written at the top of
a file with a literal dispatch value, and it is easy to assume that it
has to be. It does not. `defmethod` is a macro that expands to a call
which registers a function with the multimethod, and like any other call
it can run inside a loop with the dispatch value coming from a local.
Running this `doseq` when the namespace loads installs nine methods on
`do-op`, one per table entry. The calculator's table has come back, but
now it feeds a multimethod instead of being the whole language.

We can ask the multimethod what it knows. Before loading the arithmetic
namespace:

```clojure
(keys (methods do-op))
;; => (:get :if :default :print :seq :set :while)
```

and after:

```clojure
(sort (keys (methods do-op)))
;; => (:add :default :div :eq :get :gt :if :lt :mod :mul :not :print :seq :set :sub :while)
```

With arithmetic in place, real programs become possible. The tests in
`test/sdx/interp/ops_test.clj` run a few, and because programs are data,
each one is just a `def`:

```clojure
(def sum-to-ten
  [:seq
   [:set :i 1]
   [:set :total 0]
   [:while [:not [:gt [:get :i] 10]]
    [:seq
     [:set :total [:add [:get :total] [:get :i]]]
     [:set :i [:add [:get :i] 1]]]]
   [:get :total]])

(deftest looping
  (is (= 55 (run sum-to-ten))))
```

> **Integer division.**
> `:div` is `quot`, which truncates, rather than `/`, which in Clojure
> produces an exact ratio such as `7/2`. Ratios are a fine feature of
> Clojure but a surprising one to meet in a language that does not
> otherwise have them. This is the kind of small decision that a
> language designer makes dozens of times, and each one is a place where
> the language can be made more or less surprising.

## Arrays

A language without a way to hold several values is not much of a
language, so the fourth stage adds arrays: fixed-size, indexed from zero,
and represented as Clojure vectors. Three of the four operations are
straightforward. The fourth makes us think about immutability.

```clojure
(defn- check-index
  "Throw a helpful error unless `i` is a valid index into `arr`."
  [arr i]
  (when-not (and (integer? i) (< -1 i (count arr)))
    (throw (ex-info (str "index " i " out of range for array of " (count arr))
                    {:index i :size (count arr)}))))

(defmethod do-op :array [env [_ size]]
  (vec (repeat (evaluate env size) nil)))

(defmethod do-op :array-len [env [_ arr]]
  (count (evaluate env arr)))

(defmethod do-op :array-get [env [_ arr index]]
  (let [a (evaluate env arr)
        i (evaluate env index)]
    (check-index a i)
    (nth a i)))

(defmethod do-op :array-set [env [_ name index value]]
  (let [i (evaluate env index)
        v (evaluate env value)
        a (lookup env name)]
    (check-index a i)
    (assign! env name (assoc a i v))
    v))
```

`:array-get` takes an expression for the array, so you can index into
anything that evaluates to a vector. `:array-set` takes a *name*. A
Clojure vector cannot be changed, so "set element 2 of the array" has to
mean "make a new vector with element 2 replaced and store it where the
old one was", and to store it we need to know where the old one was. The
operation is therefore a cousin of `:set`: it evaluates the index and the
value, looks the current array up, and binds the name to a new one.

This sounds expensive and is not. Clojure's vectors are **persistent**:
`assoc` on a vector of a million elements builds a new vector that shares
almost all of its structure with the old one and copies only a handful of
small nodes. The old vector is still valid, which a test can check
directly:

```clojure
(deftest old-values-are-untouched
  (testing "setting an element rebinds the variable; the old vector is unchanged"
    (let [env (core/make-env)
          before (core/evaluate env [:set :a [:array 2]])]
      (core/evaluate env [:array-set :a 0 :changed])
      (is (= [nil nil] before))
      (is (= [:changed nil] (core/lookup env :a))))))
```

In a language with mutable arrays, `before` would have changed too, and
every function that had ever been handed that array would see the change.
Whether that is a feature or a bug depends on the day, but it is a real
difference between the two kinds of language, and here it is visible in
five lines.

`check-index` is called before anything is modified, and it produces a
message that says what the index was and how big the array is. Clojure
would throw its own exception for a bad `nth`, but the message would talk
about Java classes rather than about the user's program. Part of writing
an interpreter is deciding whose vocabulary the error messages use.

## Programs in files

Programs are data, and Clojure already has a format for data in files:
**EDN**, the subset of Clojure syntax that covers literals and
collections. `clojure.edn/read-string` turns a string of EDN into exactly
the vectors and keywords our evaluator expects, so a file runner is three
lines:

```clojure
(ns sdx.interp.main
  "Run a program stored as EDN in a file:

       clojure -M -m sdx.interp.main resources/interp/countdown.edn"
  (:require [clojure.edn :as edn]
            [sdx.interp.array]
            [sdx.interp.core :as core]
            [sdx.interp.ops]))

(defn run-file
  "Read one EDN program from `path` and evaluate it, in a fresh
   environment unless one is given. Returns the program's value."
  ([path] (run-file path (core/make-env)))
  ([path env]
   (core/evaluate env (edn/read-string (slurp path)))))

(defn -main
  "Run the program in the named file."
  [path]
  (run-file path)
  (flush))
```

Two of the `:require` entries have no alias and are never mentioned
again. `sdx.interp.ops` and `sdx.interp.array` are loaded purely for
their effect, which is to register methods on `do-op`; if they were left
out, every program that used `:add` would fail with "unknown operation".
This is the one awkwardness of open dispatch: the core cannot tell you
which namespaces you forgot to load, because it does not know they
exist.

Here is the countdown program from `resources/interp/countdown.edn`:

```clojure
[:seq
 [:set :n 3]
 [:while [:gt [:get :n] 0]
  [:seq
   [:print "n is" [:get :n]]
   [:set :n [:sub [:get :n] 1]]]]
 [:print "liftoff"]]
```

and here it is running:

```
$ clojure -M -m sdx.interp.main resources/interp/countdown.edn
n is 3
n is 2
n is 1
liftoff
```

The test for the runner, in `test/sdx/interp/main_test.clj`, runs the
sample programs from the classpath and also writes a program to a
temporary file, runs it, and deletes it, which is the pattern every
chapter uses when it needs the file system.

## Summary

- A program can be data. If programs are vectors, there is no parser to
  write, and an interpreter is a recursive walk over a tree.
- Operations that are plain functions of their arguments can be a table.
  Operations that choose what to evaluate, such as `:if`, `:while`, and
  `:set`, cannot: they are special forms, and they need the unevaluated
  expression.
- An environment can be threaded through evaluation as an immutable value
  or kept in an atom. Threading keeps evaluation pure; the atom keeps the
  code short and makes shared, changing state possible. We chose the
  atom, and hid it behind a two-function protocol so the choice is
  contained.
- A multimethod dispatching on the head of the expression lets any
  namespace add operations without editing the core, at the cost of
  having to remember to load those namespaces.
- Vectors in this language are always code; arrays are built with an
  operation and updated by rebinding a variable to a new vector, which
  persistent data structures make cheap.
- Error messages should speak the user's language, not the
  implementation's.

## Exercises

1. **Quote.** Add a `:quote` operation so that `[:quote [1 2 3]]`
   evaluates to the vector `[1 2 3]` without treating it as code. Then
   add `:array-of` which builds an array from its evaluated arguments,
   so that `[:array-of 1 [:add 1 1] 3]` is `[1 2 3]`.

2. **Threading the environment.** Rewrite `sdx.interp.core` so that the
   environment is an immutable map and every method returns a
   `[value env]` pair. How many lines change? Which operation is the
   hardest to get right?

3. **Tracing.** Add a dynamic var `*trace*` that, when true, makes
   `evaluate` print each expression before evaluating it, indented by
   depth. Where does the depth have to be kept?

4. **Short-circuit logic.** `:and` and `:or` cannot be table entries for
   the same reason `:if` cannot. Implement them as methods on `do-op`
   that stop evaluating as soon as the answer is known, and write a test
   that proves it.

5. **Strings.** Add `:str` (concatenate), `:len`, and `:substr`
   operations. Do any of them need to be special forms?

6. **Loading operations.** Write a function `(load-ops! names)` that
   `require`s each named namespace, and make `run-file` accept a list of
   operation namespaces so the user chooses the language's size. What
   should happen if a namespace is loaded twice?

7. **Nested array updates.** `:array-set` can only update an array held
   directly in a variable. Design an operation that can update an array
   inside an array, such as `[:array-set-in :grid [1 2] 0]`, using
   `assoc-in`. What has to be true of every level of the path?

---
title: "Functions and Closures"
nav_order: 8
lede: "Function definitions and calls for the interpreter, with a stack of frames and closures that remember where they were born."
---

The interpreter from [the previous chapter](../07-interp/) can loop,
branch, and keep variables, which is enough to compute anything, but
nobody would want to write a large program in it. What it lacks is a way
to name a piece of code and use it from more than one place. This chapter
adds functions, and in doing so runs into the question that every
language designer has to answer: when a function refers to a variable
that is not one of its parameters, which variable does it mean?

The answer we choose is **lexical scope**: a function sees the variables
that were visible where it was *written*, not where it is called. Getting
there means replacing the flat environment with a chain of frames, making
function values that remember the frame they were defined in, and being
careful about the one argument that decides between lexical scope and its
rival. The function values that come out of this are **closures**, and
by the end of the chapter we will have one that counts, which is a thing
no pure value can do.

Everything here is in one namespace, `sdx.closures`, and it changes
nothing in the interpreter's core. That is the payoff of the protocol and
the multimethod from the last chapter: a new kind of environment and two
new operations slot in from outside.

## What a function call needs

Suppose we define a function `fact` with a parameter `n`, and `fact`
calls itself. With a single map of variables, the inner call's `n`
overwrites the outer call's `n`, and when the inner call returns, the
outer one carries on with the wrong value. Every language solves this the
same way: each call gets its own set of variables, created when the call
starts and thrown away when it ends. The sets form a **stack**, because
calls nest, and the set for the current call is on top.

Our environment protocol says nothing about how variables are stored,
only that they can be looked up and assigned. So we can build a new kind
of environment, a **frame**, that holds its own variables and a link to
the frame that encloses it, and the core will use it without knowing the
difference.

## Frames

A frame is a record with two fields: an atom holding this frame's
variables, and a parent, which is another frame or `nil` for the
outermost one. Looking a name up means searching this frame and then its
ancestors, so we start with a helper that finds the frame a name lives
in:

```clojure
(defn- owner
  "The frame in the chain starting at `frame` that binds `name`, or nil
   if none of them does."
  [frame name]
  (loop [f frame]
    (cond
      (nil? f)                     nil
      (contains? @(:vars f) name)  f
      :else                        (recur (:parent f)))))
```

With `owner` in hand, the protocol implementation is short, and its two
methods encode the two rules of the chapter:

```clojure
(defrecord Frame [vars parent]
  core/Env
  (lookup [this name]
    (if-let [f (owner this name)]
      (get @(:vars f) name)
      (throw (ex-info (str "unknown variable " name) {:name name}))))
  (assign! [this name value]
    (let [f (or (owner this name) this)]
      (swap! (:vars f) assoc name value)
      value)))
```

The lookup rule: the nearest frame that binds the name wins. The
assignment rule: if some frame in the chain already binds the name,
update it there; otherwise create the variable in the current frame. The
second rule is what makes a `:set` inside a function body create a local
variable rather than a global one, and it is also what will let a closure
update a variable in the frame it came from. It has a sharp edge, which we
come back to at the end of the chapter.

Two constructors and one inspector round out the frames:

```clojure
(defn make-env
  "A fresh global frame with no variables and no parent."
  []
  (->Frame (atom {}) nil))

(defn push-frame
  "A new frame holding `bindings`, a map from names to values, whose
   parent is `parent`."
  [parent bindings]
  (->Frame (atom bindings) parent))

(defn snapshot
  "The variables visible from `frame` as a vector of maps, innermost
   frame first. Useful for looking at the stack."
  [frame]
  (loop [f frame, acc []]
    (if f
      (recur (:parent f) (conj acc @(:vars f)))
      acc)))
```

`snapshot` exists for tests and for curiosity. It walks the chain and
collects each frame's variables, so that we can see the stack as a
vector of maps. The test file, `test/sdx/closures_test.clj`, uses it to
check the assignment rule without any interpreter at all:

```clojure
(deftest assignment-finds-the-owning-frame
  (let [outer (c/push-frame (c/make-env) {:x 1})
        inner (c/push-frame outer {})]
    (core/assign! inner :x 5)
    (core/assign! inner :local 7)
    (testing "x changed where it lived; local was created in the innermost frame"
      (is (= [{:local 7} {:x 5} {}] (c/snapshot inner))))))
```

Three frames: the global one, `outer` with `x`, and the empty `inner`.
Assigning `x` through `inner` changes `outer`, and assigning a new name
creates it in `inner`. Nothing reached the global frame.

## Function values

A function has parameters, a body, and, this is the important part, the
environment it was defined in:

```clojure
(defrecord Closure [params body env])

(defmethod print-method Closure [f ^java.io.Writer w]
  (.write w (str "#closure" (pr-str (:params f)))))

(defmethod do-op :def [env [_ name params body]]
  (assign! env name (->Closure params body env)))
```

`:def` builds a closure and binds it to a name in the current
environment, the same way `:set` binds any other value. The `env` field
is simply the frame that was current when `:def` ran. Nothing is copied:
the closure holds a reference to the frame, and the frame goes on living
its life, which turns out to matter twice before the chapter is over.

The `print-method` definition is a precaution. `print-method` is the
multimethod Clojure uses to print anything, and we add a case for
closures because the default printing of a record would print every
field, including `env`, which is a frame, which contains an atom, whose
value is a map, which (for a recursive function) contains the closure.
Printing would never finish. Our version prints just the parameter list,
so that a closure shows up at the REPL as `#closure[:n]` and a failing
test can print its environment without looping forever. It is the first
time in this book that a data structure has had a cycle in it, and it is
a direct consequence of frames being references rather than values.

## Calling

A call looks the function up, evaluates the arguments in the caller's
environment, and then runs the body in a fresh frame where the parameters
are bound to those values. We split it into two functions, and the split
is the point of the chapter:

```clojure
(defn invoke
  "Call the closure `f` with already-evaluated `args`, evaluating its
   body in a new frame whose parent is `parent`. Lexical scope passes the
   closure's own :env as the parent; dynamic scope would pass the
   caller's environment instead."
  [f args parent]
  (when-not (instance? Closure f)
    (throw (ex-info (str "not a function: " (pr-str f)) {:value f})))
  (let [{:keys [params body]} f]
    (when (not= (count params) (count args))
      (throw (ex-info (str "expected " (count params) " arguments, got " (count args))
                      {:params params :args args})))
    (evaluate (push-frame parent (zipmap params args)) body)))

(defmethod do-op :call [env [_ name & args]]
  (let [f (lookup env name)]
    (invoke f (evaluate-all env args) (:env f))))
```

`invoke` checks that it was given a closure and that the argument count
matches, builds the new frame with `zipmap` (which pairs the parameter
names with the argument values into a map), and evaluates the body in
it. When the body finishes, the frame is simply dropped. Nothing has to
"pop" it, because nothing points at it any more. The stack in this
interpreter is not a data structure we maintain; it is the chain of
parent links from the current frame outward, and it shrinks by itself
when a call returns.

The parameter that matters is `parent`. The body's frame has to have
*some* enclosing frame, so that the body can see variables other than its
parameters, and there are two candidates. `:call` passes `(:env f)`,
the frame the function was defined in. It could have passed `env`, the
frame it was called from. One line, and it decides the semantics of the
language.

## Lexical versus dynamic scope

Here is a program that tells the two apart:

```clojure
(def scope-program
  [:seq
   [:set :x :global]
   [:def :show-x [] [:get :x]]
   [:def :caller [:x] [:call :show-x]]
   [:call :caller :callers]])
```

`show-x` has no parameters and returns whatever `x` means. `caller` has a
parameter called `x` and calls `show-x`. When `caller` runs with the
argument `:callers`, there are two `x`s in the world: the global one,
which is `:global`, and the parameter, which is `:callers`. Which one
does `show-x` see?

Under **lexical scope** (also called static scope), `show-x` sees the
`x` that was visible where `show-x` was defined, which is the global one.
You can work out what a function means by reading it and the code
around it; the call site is irrelevant. Under **dynamic scope**, `show-x`
sees whichever `x` was most recently bound by any function still
running, which is the parameter of `caller`. To know what `show-x`
returns you have to know who called it, and who called them.

Nearly every language made in the last forty years chose lexical scope,
and ours does too:

```clojure
(deftest lexical-scope
  (testing "show-x sees the x where it was defined, not the caller's x"
    (is (= :global (run scope-program)))))
```

Because the choice is an argument to `invoke`, the test file can also
show the road not taken, by calling `invoke` directly with the caller's
frame as the parent:

```clojure
(deftest dynamic-scope-by-hand
  (let [env (c/make-env)]
    (core/evaluate env [:seq [:set :x :global] [:def :show-x [] [:get :x]]])
    (let [f (core/lookup env :show-x)
          caller-frame (c/push-frame env {:x :callers})]
      (testing "the parent frame passed to invoke decides which x is visible"
        (is (= :global (c/invoke f [] (:env f))))
        (is (= :callers (c/invoke f [] caller-frame)))))))
```

Same function, same arguments, different parent, different answer. The
reason dynamic scope lost is in the second assertion: the behaviour of
`show-x` depends on something that is not written anywhere near it.
Dynamic scope survives in a few places, Clojure's `binding` form among
them, where that is exactly the effect wanted, but as the default rule it
makes programs impossible to read locally.

## Recursion

With lexical scope in place, recursion works without any extra machinery:

```clojure
(def factorial
  [:seq
   [:def :fact [:n]
    [:if [:lt [:get :n] 2]
     1
     [:mul [:get :n] [:call :fact [:sub [:get :n] 1]]]]]
   [:call :fact 10]])

(deftest recursion
  (is (= 3628800 (run factorial))))
```

It is worth asking why. The body of `fact` calls `fact`, so when the body
runs it has to be able to find `fact` in its environment. The closure's
`env` is the global frame, and `:def` assigned `fact` into that frame
*after* the closure was created. If a closure captured a snapshot of its
environment's contents, it would have been taken a moment too early and
`fact` would be missing from it. It captures the frame itself, a
reference to a place that can change, so the assignment that happens next
is visible through it. This is the first place where the choice of an
atom-backed environment in the previous chapter pays off; a threaded
immutable environment would have needed a special case here.

Each recursive call pushes a frame whose parent is the global frame, not
the previous call's frame, because the parent comes from the closure, not
from the caller. So the ten frames of `(fact 10)` are all siblings
hanging off the same global frame, each with its own `n`, and the
Clojure call stack beneath them grows by a few frames per level. Ten
thousand levels would overflow it, which is a limit of this design rather
than of the idea; the exercises ask how to lift it.

## Closures that remember

The second payoff of capturing a reference is the one that gives closures
their reputation. Consider a function that makes counters:

```clojure
(def make-counter
  [:def :make-counter []
   [:seq
    [:set :count 0]
    [:def :next [] [:seq [:set :count [:add [:get :count] 1]]
                         [:get :count]]]
    [:get :next]]])
```

Calling `make-counter` pushes a frame, creates `count` in it (the
assignment rule: no enclosing frame has a `count`, so it is created
locally), defines `next` with that frame as its environment, and returns
`next`. Then the call ends and its frame is dropped, or would be, except
that the closure we just returned still points at it. The frame outlives
the call that created it.

Now every call to the returned closure pushes a new frame whose parent is
that surviving frame, and its `:set :count` finds `count` there and
updates it. The counter counts, and a second counter made by a second
call to `make-counter` has a frame of its own and counts separately:

```clojure
(deftest a-counter-remembers-its-own-state
  (let [env (c/make-env)]
    (core/evaluate env make-counter)
    (core/evaluate env [:set :a [:call :make-counter]])
    (core/evaluate env [:set :b [:call :make-counter]])
    (is (= [1 2 3] [(core/evaluate env [:call :a])
                    (core/evaluate env [:call :a])
                    (core/evaluate env [:call :a])]))
    (testing "each counter has its own frame"
      (is (= 1 (core/evaluate env [:call :b]))))
    (testing "count never reached the global frame"
      (is (thrown? clojure.lang.ExceptionInfo (core/lookup env :count))))))
```

This is why the word "stack" was slightly wrong earlier. Frames whose
calls have returned usually become garbage, so the live frames form a
stack in practice. But a frame that a closure holds on to stays alive,
and frames pushed later can hang off it, so what we really have is a
tree, with the global frame at the root and the current call somewhere
in its branches. Languages that put frames on a real stack, as C does,
cannot have closures like this one, which is why C does not have them.

A closure with private state is the smallest possible object: data (the
frame) plus behaviour (the body) with the data hidden from everyone else.
The object system of [Objects and Classes](../02-objects/) could be
rebuilt from closures alone, and in several languages it was. Clojure
itself takes the same route in miniature: a Clojure function that closes
over an atom is exactly this counter, and `make-env` in this chapter is
Clojure's version of `make-counter`.

Functions being values also means they can be arguments. `twice` below
takes a function and applies it two times, and the only thing it needs
from the interpreter is that `:call` looks the name up in the current
frame, where the parameter `f` is bound to a closure:

```clojure
(deftest functions-are-values
  (is (= 16 (run [:seq
                  [:def :twice [:f :v] [:call :f [:call :f [:get :v]]]]
                  [:def :double [:n] [:mul 2 [:get :n]]]
                  [:call :twice [:get :double] 4]]))))
```

## Shadowing and assignment

The last thing to pin down is what happens when a function's parameter
has the same name as a variable outside it. Parameters live in the call's
own frame, so they **shadow** the outer variable: inside the body the
name means the parameter, and assigning to it changes only the parameter.

```clojure
(deftest parameters-shadow-outer-variables
  (let [env (c/make-env)]
    (core/evaluate env [:seq
                        [:set :x 1]
                        [:def :f [:x] [:seq [:set :x [:add [:get :x] 10]]
                                            [:get :x]]]])
    (is (= 15 (core/evaluate env [:call :f 5])))
    (testing "the global x was shadowed, not changed"
      (is (= 1 (core/lookup env :x))))))
```

Without the parameter, the same assignment behaves differently. There is
no `x` in the call's frame, so the assignment rule walks outward, finds
the global `x`, and changes it:

```clojure
(deftest assigning-without-a-parameter-reaches-outward
  (let [env (c/make-env)]
    (core/evaluate env [:seq
                        [:set :x 1]
                        [:def :bump [] [:set :x [:add [:get :x] 1]]]])
    (core/evaluate env [:call :bump])
    (is (= 2 (core/lookup env :x)))))
```

This is the sharp edge promised earlier. The rule "update the nearest
binding, else create a local" is what makes the counter work, but it also
means that a function cannot create a local variable whose name happens
to be in use further out; it will silently update the outer one instead.
Python resolves this with the opposite default (assignment is always
local unless declared `nonlocal` or `global`), JavaScript with `let`, and
Scheme by separating definition from assignment. Each of those is a
reasonable design, and each is about three lines of change to
`assign!`; what is not reasonable is to leave the rule unstated, which is
why it is written down here and pinned by two tests.

## Running programs

The runner from the previous chapter takes an optional environment, so
running a program with functions in it means passing a frame instead of
the old flat environment:

```clojure
(defn -main
  "Run the EDN program in the named file with functions available:

       clojure -M -m sdx.closures resources/closures/fact.edn"
  [path]
  (main/run-file path (make-env))
  (flush))
```

```
$ clojure -M -m sdx.closures resources/closures/fact.edn
5! = 120
10! = 3628800
```

Nothing in `sdx.interp.main` changed; it only ever asked its environment
to `lookup` and `assign!`, and a frame answers both.

## Summary

- Every call needs its own variables. A frame holds them and points at
  the frame that encloses it; the chain of frames is the stack, and it
  shrinks by itself when nothing refers to a frame any more.
- Lookup finds the nearest frame that binds a name. Assignment updates
  the nearest binding or, if there is none, creates a local. Both rules
  are a few lines each and both deserve tests.
- A function value is a closure: parameters, a body, and the frame it
  was defined in. Capturing the frame by reference rather than copying
  its contents is what lets a function call itself and what lets it keep
  state between calls.
- Which frame becomes the parent of a call's frame decides the scope
  rule. The definer's frame gives lexical scope; the caller's gives
  dynamic scope. We chose lexical, and isolated the choice in one
  argument so a test can show the alternative.
- Because closures keep frames alive, the frames form a tree rather than
  a stack. That is the price of closures, and the reason some languages
  do not have them.
- A protocol for the environment and a multimethod for operations meant
  all of this was added without editing the interpreter's core.

## Exercises

1. **Local declarations.** Add a `[:let name expr]` operation that
   always creates the variable in the current frame, even if an outer
   frame binds the same name. Then rewrite `make-counter` to use it and
   explain why the counter still works.

2. **Anonymous functions.** Add `[:fn [params] body]`, which evaluates
   to a closure without binding a name, and change `:call` so that its
   first argument can be any expression that evaluates to a closure, not
   just a name. What does `[:call [:fn [:x] [:get :x]] 5]` do?

3. **Early return.** Add `[:return expr]` so that a function can stop
   before the end of its body. Exceptions are one way to implement it;
   is there another that does not need them?

4. **Deeper recursion.** Find the largest `n` for which
   `[:call :fact n]` succeeds, then make it larger. One approach is to
   run the evaluator in a thread with a bigger stack
   (`java.lang.Thread` has a constructor that takes a stack size).
   Another is to make `:call` in tail position not grow the stack at
   all. Which is a change to the interpreter and which is a change to
   the language?

5. **Dynamic scope as a feature.** Add `[:binding name expr body]` that
   temporarily rebinds an existing variable for the duration of `body`,
   restoring it afterwards even if `body` throws. Compare it with
   Clojure's `binding`.

6. **Frame printing.** Write a `print-method` for `Frame` that prints
   the frame's own variables and the depth of its chain, but not its
   parent. Why is printing the parent a bad idea?

7. **Counting frames.** Add an atom that counts how many frames have
   been created, and use it to compare how many frames `[:call :fact 10]`
   creates with how many a loop-based factorial from the previous
   chapter creates. What does the difference cost, and what does it buy?

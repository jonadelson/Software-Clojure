---
title: "Objects and Classes"
nav_order: 2
lede: "An object system built from maps and functions, and then the one Clojure gives you."
---

Nearly every program we write has to deal with things that are alike in
some ways and different in others. Squares and circles both have an area,
but they compute it differently. A test runner treats every test the same
way, even though every test does something different. The usual name for
the machinery that handles this is "objects", and most languages bake it
in so deeply that we never see how it works.

This chapter builds that machinery out of nothing but maps and functions.
Doing so shows that there is no magic in objects: an object is a bundle of
data and behavior, a class is a place to keep the behavior that many
objects share, and inheritance is a lookup rule. Once we have built our
own version, we look at the tools Clojure provides for the same problem,
multimethods and protocols, and see which of our ideas they keep and which
they throw away.

## Objects that carry their own behavior

A Clojure map can hold anything as a value, including a function. So the
simplest possible object is a map with some data and some functions in it:

```clojure
(defn- square-area [thing]
  (* (:side thing) (:side thing)))

(defn- square-perimeter [thing]
  (* 4 (:side thing)))

(defn square
  "Make a square with the given name and side length."
  [name side]
  {:name name
   :side side
   :area square-area
   :perimeter square-perimeter})
```

The functions take the object as their first argument, which is how they
get at its data. To "call a method" we look the function up by its key
and pass the object to it:

```clojure
(defn call
  "Look up `method` in `thing` and call it with `thing` and any extra args."
  [thing method & args]
  (if-let [f (get thing method)]
    (apply f thing args)
    (throw (ex-info (str "no method " method) {:method method :thing thing}))))
```

```clojure
(def sq (square "sq" 3))
(call sq :area)       ;; => 9
(call sq :perimeter)  ;; => 12
```

A circle is built the same way with different functions, and the payoff
is that code which needs an area or a perimeter does not have to know
which kind of shape it has:

```clojure
(defn describe
  "A one-line description of any shape that has :area and :perimeter."
  [thing]
  (format "%s: area %.2f, perimeter %.2f"
          (:name thing)
          (double (call thing :area))
          (double (call thing :perimeter))))
```

```clojure
(describe (square "sq" 3))   ;; => "sq: area 9.00, perimeter 12.00"
(describe (circle "ci" 1))   ;; => "ci: area 3.14, perimeter 6.28"
```

This is **polymorphism**: one piece of code, `describe`, working with
many kinds of values because all of them answer the same questions. We
got it for free by agreeing on a convention (every shape has an `:area`
and a `:perimeter` key) rather than by asking the language for
permission.

> **Explicit `this`.**
> In languages with built-in objects, the object a method belongs to is
> usually passed invisibly and called `this` or `self`. Our methods take
> it as an ordinary first argument. That is not a limitation; it is a
> clarification. Python does the same thing, and so, under the hood, does
> nearly every object system ever built.

## Sharing behavior with classes

The map-per-object approach has a problem: every square carries its own
copy of `square-area`. The copies are references to the same function, so
this does not cost much memory, but it costs something more important.
There is no single place that says "this is what a square is". If we want
to add a method to all squares we have to find every call to `square` and
hope we did not miss one.

The fix is to split the data from the behavior. An **object** is a map of
data with one special key, `:class`, that points at a **class**: a map
holding the methods that all objects of that kind share.

```clojure
(def square
  {:name "Square"
   :parent shape
   :methods {:new square-new
             :area (fn [thing] (* (:side thing) (:side thing)))
             :perimeter (fn [thing] (* 4 (:side thing)))}})
```

Calling a method now means looking in the object's class rather than in
the object itself:

```clojure
(defn call
  "Call `method` on `thing`, passing `thing` as the first argument."
  [thing method & args]
  (if-let [f (find-method (:class thing) method)]
    (apply f thing args)
    (throw (ex-info (str "no method " method " for " (get-in thing [:class :name]))
                    {:method method :class (get-in thing [:class :name])}))))
```

We have snuck something in. The class has a `:parent`, and `find-method`
is going to use it.

## Inheritance is a lookup rule

Squares and circles both know how to describe themselves, and the
description is built the same way for both. We would like to write that
code once. The obvious place to put it is a class that square and circle
both share:

```clojure
(def shape
  "A shape has a name and knows how to describe itself, but has no
   particular geometry. :area and :perimeter must come from a child."
  {:name "Shape"
   :parent nil
   :methods {:new shape-new
             :describe shape-describe
             :density shape-density}})
```

Now `find-method` looks in a class, and if it does not find what it wants,
looks in the class's parent, and so on until it runs out of parents:

```clojure
(defn find-method
  "Search `cls` and its ancestors for `method`. Returns nil if not found."
  [cls method]
  (when cls
    (or (get-in cls [:methods method])
        (recur (:parent cls) method))))
```

That is all **inheritance** is. When we ask a square to `:describe`
itself, lookup fails in `square`, succeeds in `shape`, and the function
it finds is called with the square as its argument. Inside
`shape-describe`, the calls to `:area` and `:perimeter` start the lookup
again from the square's class, so they find the square's versions. Methods
that a parent defines can rely on methods that only its children provide.

```clojure
(call (make square "sq" 3) :describe)   ;; => "sq: area 9.00, perimeter 12.00"
(call (make square "sq" 3) :density 18) ;; => 2
```

The `recur` in `find-method` deserves a note. Clojure does not optimize
tail calls in general, so a function that calls itself for each ancestor
would use one stack frame per level of inheritance. `recur` jumps back to
the start of the function without growing the stack. Inheritance chains
are never deep enough for this to matter, but it is the idiomatic way to
write a loop and it costs nothing to use it.

## Constructors

We have been using `make` without showing it. Constructing an object means
finding a method called `:new` and calling it with the class:

```clojure
(defn make
  "Construct an instance of `cls` by calling its :new method with `args`."
  [cls & args]
  (if-let [ctor (find-method cls :new)]
    (apply ctor cls args)
    (throw (ex-info (str "no constructor for " (:name cls)) {:class (:name cls)}))))
```

Constructors chain just like other methods. A square's constructor calls
the shape's constructor to set up the common fields and then adds its own:

```clojure
(defn- shape-new [cls name]
  {:class cls :name name})

(defn- square-new [cls name side]
  (assoc (shape-new cls name) :side side))
```

Because `make` uses `find-method`, a class that does not define `:new`
inherits its parent's, which is sometimes exactly what we want and
sometimes a subtle bug. Many languages with built-in objects make the same
choice.

One more thing falls out of the parent chain for free. Checking whether an
object is an instance of a class is a walk up the chain looking for a
match:

```clojure
(defn instance-of?
  "True if `thing` belongs to `cls` or one of its descendants."
  [thing cls]
  (loop [c (:class thing)]
    (cond
      (nil? c) false
      (= (:name c) (:name cls)) true
      :else (recur (:parent c)))))
```

We compare names rather than the class maps themselves because comparing
two large nested maps for equality means walking both of them, while
comparing two strings is cheap. It also means that two classes with the
same name are the same class as far as `instance-of?` is concerned, which
is a design decision we would have to revisit if this grew into a real
system.

## What Clojure gives you instead

Our object system is about sixty lines long and does most of what the
object systems of popular languages do. Clojure does not come with one
like it, and the reason is instructive: in a language where data is
immutable, the main thing classes are used for in other languages, hiding
mutable state behind methods, is unnecessary. What remains is the need for
polymorphism, and Clojure provides two tools for that.

### Multimethods: dispatch on a value

A **multimethod** is a function that chooses its implementation by
calling a dispatch function on its arguments. The simplest dispatch
function is a keyword, which looks itself up in the first argument:

```clojure
(defmulti area
  "Area of a shape, chosen by the shape's :kind."
  :kind)

(defmethod area :square [{:keys [side]}] (* side side))
(defmethod area :circle [{:keys [radius]}] (* Math/PI radius radius))
```

```clojure
(area {:kind :square :side 3})   ;; => 9
```

This is our first object system with the roles reversed. Instead of the
object pointing at a map of methods, each method has a map from kinds to
implementations, and the "class" is just a keyword in the data. Two things
make this more flexible than what we built:

- The dispatch function can be anything. It could look at two arguments,
  or at the size of a collection, or at the phase of the moon.
- Anyone can add a case for a new kind, in any file, without touching the
  code that defined the multimethod. The test for this chapter does
  exactly that, adding `:triangle` and then removing it again.

What multimethods do not give us is a way to say "these three functions
belong together". Nothing stops someone from defining `area` for
`:triangle` and forgetting `perimeter`, and we will not find out until the
missing call fails at runtime.

### Protocols: dispatch on a type

A **protocol** is a named group of functions that a type can promise to
implement, and a **record** is a map-like type with named fields that can
make that promise:

```clojure
(defprotocol Shape
  "Operations every shape supports."
  (shape-area [this] "Area of the shape.")
  (shape-perimeter [this] "Perimeter of the shape."))

(defrecord Square [name side]
  Shape
  (shape-area [_] (* side side))
  (shape-perimeter [_] (* 4 side)))
```

```clojure
(def sq (->Square "sq" 3))
(shape-area sq)      ;; => 9
(:name sq)           ;; => "sq"  (records are still maps)
(assoc sq :side 4)   ;; => #sdx.objects.builtin.Square{:name "sq", :side 4}
```

Protocols dispatch on the type of their first argument rather than on a
value inside it, which makes them very fast, and they group related
functions so that a type which implements a protocol has to implement all
of it. A record is a lot like one of our class-and-object pairs, with one
important difference: there is no `:parent`. Clojure deliberately does not
let one record inherit from another. If two records share behavior, you
write a function that both of them call, which is what we did with
`describe` at the start of the chapter.

> **Which should I use?**
> Use a plain map and plain functions until you have more than one kind
> of thing that needs to answer the same question. Then use a multimethod
> if the choice depends on the data, or a protocol if it depends on the
> type and you want the speed and the all-or-nothing contract. Reach for
> records when you want a type with a name, usually for the same reason.
> Build your own object system only if you are writing a book chapter
> about it.

## Summary

- An object is data plus behavior. In a language where functions are
  values, a map can be both.
- Polymorphism means one piece of code working on many kinds of value. It
  needs only an agreed set of keys or function names, not a language
  feature.
- A class is a place to keep the behavior that all objects of one kind
  share; an object points at its class rather than carrying the behavior
  itself.
- Inheritance is a lookup rule: look in the class, then in its parent,
  and keep going until something is found or there is nowhere left to
  look.
- Constructors are ordinary methods that happen to be called `:new`.
- Clojure replaces classes with multimethods (dispatch on any function of
  the arguments) and protocols (dispatch on type with a grouped contract),
  and leaves out inheritance on purpose.

## Exercises

1. **Triangles.** Add a `triangle` class to the class-based system with
   `:area` and `:perimeter` methods. What does `describe` do with it
   without any changes?

2. **Default methods.** Make `shape` provide a default `:area` that
   throws an informative error. Why might that be better or worse than
   having `find-method` return `nil`?

3. **Super.** Add a function `(call-parent thing method & args)` that
   calls the version of `method` defined in the parent of the object's
   class, skipping the object's own. What goes wrong if a grandparent
   method calls `call-parent`?

4. **Multiple parents.** Change `:parent` to `:parents`, a vector of
   classes, and update `find-method` to search them depth first. Write a
   test that shows the order in which methods are found when two parents
   both define the same method.

5. **Counting instances.** Add an atom to the class-based system that
   counts how many objects of each class have been created. Where does it
   have to live, and what does that tell you about why Clojure
   programmers avoid state when they can?

6. **Protocol on a map.** Clojure lets you `extend-protocol` to existing
   types, including `clojure.lang.PersistentArrayMap`. Extend `Shape` to
   plain maps so that `(shape-area {:kind :square :side 3})` works by
   delegating to the multimethods. Is this a good idea?

7. **Equality.** Two records with the same fields are `=` to each other.
   Are two of our class-based objects? Should they be?

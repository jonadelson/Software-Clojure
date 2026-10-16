---
title: Glossary
---

**class**: a place to keep the behavior that all objects of one kind share. In the object system of [Chapter 2](../02-objects/), a map of methods with an optional parent.

**closure**: a function value that carries with it the variables that were in scope when it was created.

**constructor**: the function that builds a new object; in our object system, a method called `:new`.

**homoiconic**: a property of languages whose programs are written as the language's own data structures. Clojure code is lists, vectors, maps, and symbols.

**inheritance**: the lookup rule that lets an object use methods defined on its class's parent, grandparent, and so on.

**multimethod**: a Clojure function that chooses its implementation by applying a dispatch function to its arguments.

**object**: a bundle of data and behavior; in Clojure, often just a map whose values may be functions.

**polymorphism**: one piece of code working with many kinds of values because they all answer the same questions.

**protocol**: a named group of functions that a type can promise to implement; Clojure dispatches protocol functions on the type of their first argument.

**record**: a map-like Clojure type with named fields, created with `defrecord`, which can implement protocols.

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

**O(n)**: a cost that grows in proportion to the size of the input.

**O(n²)**: a cost that grows with the square of the input size, so ten times the input means a hundred times the work.

**hash function**: a function that maps data to a small fixed-size label such that equal inputs always get equal labels.

**collision**: two different inputs that receive the same hash.

**pigeonhole principle**: if there are more items than labels, some label must be shared.

**SHA-256**: a cryptographic hash function producing a 256-bit value, for which collisions are considered impossible in practice.

**lazy sequence**: a sequence whose elements are computed only when something asks for them.

**Java interop**: calling Java classes and methods directly from Clojure.

**type hint**: an annotation such as `^bytes` that tells the compiler which class a value has so that method calls need no run-time lookup.

**test oracle**: a simple, trusted implementation used to check the output of a faster or cleverer one.

**glob**: a pattern language for file names using `*`, `?`, and `{a,b}`.

**chain of responsibility**: a design in which each piece handles its own part of a job and passes the remainder to the next piece.

**null object**: an implementation that does nothing, placed where `nil` would otherwise go so that callers never need to check.

**backtracking**: trying each possible choice in turn and, when one leads to a dead end, returning to try the next.

**table-driven test**: one test that loops over a table of cases, so that adding a case means adding a row.

**tagged vector**: a vector whose first element is a keyword naming what kind of node it is, such as `[:lit "abc"]`.

**data-oriented design**: representing a structure as plain values and writing operations as functions over them rather than as methods on objects.

**parser**: a program that turns text into the structure a program wants.

**token**: the smallest meaningful chunk of input, such as a literal, a star, or a brace.

**tokenizer**: the layer that groups characters into tokens; also called a lexer.

**grammar**: a written description of what well-formed input looks like, one rule per kind of thing.

**recursive-descent parser**: a parser with one function per grammar rule, where rules that refer to each other become functions that call each other.

**round-trip test**: checking that printing the parsed form of an input gives back the input, which tests the parser against an independent printer.

**separation of concerns**: giving each layer one kind of input and one job so that each changes for its own reasons and can be tested alone.

**virtual machine**: a program that imitates a processor by repeatedly fetching, decoding, and executing instructions stored as numbers in memory.

**instruction set**: the fixed list of operations a machine understands; in [Chapter 25](../25-vm/), a data table shared by the machine, assembler, disassembler, and debugger.

**register**: one of a small number of named storage slots inside a machine that instructions operate on directly.

**program counter**: the address of the next instruction to execute, advanced before each instruction runs so that branches can overwrite it.

**op code**: the number in the low byte of an instruction word that identifies which instruction it is.

**fetch-decode-execute cycle**: the loop a processor runs: read the word at the program counter, unpack it, carry it out.

**assembler**: a translator from readable text with mnemonics and labels into the integer words a machine runs.

**label**: a name given to a memory address in assembly source, resolved to a number by the assembler's first pass.

**two-pass assembly**: assembling in two sweeps so that a label can be referred to before the line that defines it.

**disassembler**: a translator from words back into assembly text, which cannot by itself tell code from data.

**bit packing**: storing several small fields in one integer using shifts and masks.

**debugger**: a tool that runs a program under the user's control, stopping it so that its state can be inspected.

**breakpoint**: an address at which a debugger stops the machine whenever execution reaches it.

**single-stepping**: executing exactly one instruction and then stopping.

**dependency injection**: passing the things a function needs, such as input and output functions, as arguments instead of having it reach out for them.

**command table**: a map from the text a user can type to a description and the function that handles it, which can print its own help and be extended with `assoc`.

**scripted session**: a test that drives an interactive program with a fixed list of input lines and compares everything it wrote.

**atom**: a mutable reference to an immutable value, changed with `swap!` or `reset!`; the usual way to hold state that must change in place.

**forward declaration**: telling the compiler with `declare` that a var will be defined later so that it can be referenced earlier in the file.

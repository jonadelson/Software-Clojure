---
title: "Introduction"
nav_order: 1
lede: "Why we learn design by building the tools we use."
---

Most programmers learn their craft the way most writers do: by reading a
lot of other people's work and imitating the parts they like. The trouble
is that the programs we read are usually either too small to teach us
anything about structure or too large to hold in our heads. A to-do list
app has no design problems worth the name; a web browser has thousands of
them, but they are buried under years of history and nobody can see where
one decision ends and the next begins.

This book takes a middle path. Each chapter builds a small version of a
tool programmers use every day: a test runner, a pattern matcher, an
interpreter, a version-control system, a build manager, a web server, a
virtual machine, a debugger. Each one is a few hundred lines long. Each
one actually works. And each one is built in stages, so that you can see
the design decisions as they are made and understand why the first
attempt is rarely the last.

The tools are not the point. The point is the handful of ideas that keep
showing up no matter what we are building: separating what is done from
how it is done, representing programs as data, keeping state in one place
so that everything else can be a pure function, and designing so that
things can be tested in isolation. By the end of the book you will have
seen each of these ideas several times in several guises, which is the
only reliable way to learn them.

## Why Clojure

This book is a port of Greg Wilson's
[*Software Design by Example: A Tool-Based Introduction with Python*](https://third-bit.com/sdxpy/).
The chapter plan follows the original closely, but the code and the
discussion have been rewritten from scratch, because the choice of
language changes what the natural design is.

Clojure makes a few things easy that are hard in most languages, and the
chapters lean on them:

- **Data is immutable by default.** A map or a vector cannot be changed
  after it is made; operations that "modify" it return a new value and
  leave the old one intact. This removes an entire category of bugs and
  makes it easy to reason about code that would otherwise need careful
  locking or copying.
- **Code is data.** A Clojure program is made of lists, vectors, maps,
  and symbols, and the reader that turns text into those structures is
  available to any program. Several chapters (the interpreter, the
  linter, the debugger) are much shorter than they would be in a language
  where the parser is hidden inside the compiler.
- **Functions are the main unit of design.** Where the Python original
  reaches for a class, this book usually reaches for a function or a map
  of functions, and explains the tradeoff when it does. Clojure's own
  tools for polymorphism, multimethods and protocols, appear when they
  earn their place.
- **The JVM is underneath.** We can open sockets, hash files, and read
  bytes without any library beyond the Java standard library, which means
  nothing in the book is hidden behind a dependency.

You do not need to be a Clojure expert to read this book, but you should
be comfortable with the basics: defining functions with `defn`, binding
names with `let`, working with maps and vectors, and destructuring. When a
chapter uses something beyond that (a protocol, a macro, an atom, a piece
of Java interop) it explains the idea when it first appears.

## How to read this book

Each chapter stands on its own, with three exceptions: the parser in
[Chapter 5](../05-parser/) builds on the matcher from
[Chapter 4](../04-glob/), the closures of [Chapter 8](../08-closures/)
extend the interpreter from [Chapter 7](../07-interp/), and the debugger
of [Chapter 26](../26-debugger/) extends the virtual machine of
[Chapter 25](../25-vm/). Otherwise you can read in whatever order you
like, though the early chapters introduce ideas that later ones assume.

Every chapter ends with a summary and a set of exercises. Some of the
exercises are quick checks; others ask you to extend the tool in a
direction the chapter did not take, and those are where most of the
learning happens. All of the code is in the
[repository]({{ site.repo_url }}) along with tests, and we encourage you
to run it, break it, and change it as you go.

> **Running the code.**
> The code needs Java and the Clojure command-line tools. Running
> `clojure -M:test` at the root of the repository runs every chapter's
> tests; `clojure -M:test sdx.glob-test` runs the tests for one chapter.
> Nothing beyond the Clojure standard library is required.

## Acknowledgments

This book exists because Greg Wilson wrote the original and released it
under a license that permits exactly this kind of adaptation. Readers who
find the ideas here useful should know that his version, and its earlier
JavaScript sibling, are where they came from.

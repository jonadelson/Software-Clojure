---
title: "Conclusion"
nav_order: 27
lede: "What the tools have in common, and what to build next."
---

We have built two dozen tools. None of them is production quality, and
none was meant to be; a real test runner, build manager, or web server has
to cope with a hundred cases ours ignore. But each one works, each one is
small enough to hold in your head, and each one was built in stages so
that the design decisions were visible as they were made. This closing
chapter steps back to ask what those decisions had in common.

## The ideas that kept coming back

**Programs are data.** The interpreter in [Chapter 7](../07-interp/)
evaluated vectors. The template expander in [Chapter 12](../12-template/)
walked an HTML tree and treated some of its attributes as instructions.
The linter in [Chapter 13](../13-lint/) read Clojure source with the same
reader the compiler uses and then simply looked at the result. The
assembler in [Chapter 25](../25-vm/) turned text into integers that the
virtual machine then treated as instructions. In every case the hard
part turned out to be deciding on a representation; once the program was
data, the rest was ordinary code. Clojure makes this unusually easy
because its own programs are already lists and vectors, but the idea does
not depend on the language.

**Keep the core pure and push effects to the edge.** The file viewer in
[Chapter 23](../23-viewer/) is a function from a state and a key to a new
state; the terminal is a thin shell around it. The build manager in
[Chapter 19](../19-build/) decides what to rebuild from a map of
timestamps rather than from the filesystem. The debugger in
[Chapter 26](../26-debugger/) reads commands through a function we can
replace. Every time we did this, two things happened: the core became
easier to test, and it became easier to reuse in a context we had not
planned for. Immutable data makes this style natural, since a function
that cannot change its arguments has to return its result, and a result
is something a test can inspect.

**Separate what from how.** The glob matcher of [Chapter 4](../04-glob/)
started as a chain of records and ended as a tree of data interpreted by
one function; the pattern stayed the same while its representation
changed. The database in [Chapter 18](../18-db/) put its storage behind a
protocol so that a vector in memory and a file on disk could be swapped
without touching the code that used them. The web server in
[Chapter 22](../22-http/) turned a request into a map and a response into
a map so that handlers could be ordinary functions and middleware could be
functions that wrap them. The common thread is an interface narrow enough
to have more than one implementation.

**Design for testing.** This is less a separate idea than a consequence
of the others, but it deserves its own name because it is the one most
often skipped. The archiver in [Chapter 10](../10-archive/) took its clock
as a parameter so that a test could freeze time. The servers in
[Chapters 21](../21-ftp/) and [22](../22-http/) bound to port zero so that
tests could run anywhere. The layout engine in [Chapter 14](../14-layout/)
rendered to a grid of characters so that a test could compare pictures.
None of these choices was forced by the problem; each was made so that we
could check our work, and each made the design better in other ways too.

**Hashing, graphs, and search are everywhere.** Content-addressed
storage in the duplicate finder and the archiver, topological sorting in
the build manager, backtracking in the package manager, identity tables
in the persistence framework: a small number of data structures and
algorithms did a surprising amount of work. They are worth knowing well.

## What we left out

Each chapter ends with exercises, and many of them point at things a real
tool would need. Some larger gaps are worth naming:

- **Concurrency.** Apart from the file server's use of futures, the tools
  are single-threaded. Clojure's story for concurrency (atoms, refs,
  agents, `core.async`) is one of its strengths, and a second volume could
  rebuild several of these tools around it.
- **Error handling.** We mostly threw `ex-info` and moved on. Deciding
  what to do when a build rule fails halfway, or a client disconnects
  mid-transfer, is most of the work in a tool people depend on.
- **Performance.** [Chapter 15](../15-perf/) showed how to measure, but
  nothing else in the book was tuned. That was deliberate: clarity first,
  and measure before changing anything.
- **Specs and types.** Clojure's `spec` library and the various typed
  dialects offer ways to describe data more precisely than a docstring.
  We used docstrings. Adding specs to a few of the tools would be a good
  way to learn them.

## Where to go next

The best way to consolidate what you have learned is to build something.
Pick a tool you use and do not understand, work out what its smallest
useful version would be, and build that in stages, writing tests as you
go. If you want suggestions, the original book this one is adapted from
sketches a dozen more: a file cache, a publish-subscribe message queue, a
work-stealing scheduler, a conflict-free replicated data type, and
others. Each is a few hundred lines in the style of this book, and each
will teach you something you cannot learn by reading.

Finally, read other people's code. The tools we built here are simplified
versions of real programs whose source is freely available, and comparing
your version with theirs is the fastest way to find out which of your
decisions were good ones.

---
title: Glossary
---
Short definitions of the terms the chapters introduce, with a link to the chapter where one would help.

**accept loop**: a server's loop that waits for a connection, hands it off, and goes back to waiting.

**accumulator**: the value threaded through every step of a walk or a reduce so that the walk has no state of its own.

**action**: a map such as `{:op :insert :row 2 :col 5 :char "x"}` that completely describes one change to a document, separate from the code that performs it.

**aliasing**: one object referred to from more than one place in a structure; a persistence format preserves aliasing by writing the object once and referring back to it.

**append-only log**: a store to which records are only ever added at the end, so that an update is a new version after the old one and nothing is overwritten.

**assembler**: a translator from readable text with mnemonics and labels into the integer words a machine runs.

**atom**: a mutable reference to an immutable value, changed with `swap!` or `reset!`; the usual way to hold state that must change in place.

**auto-gensym**: a symbol ending in `#` inside a syntax-quoted macro template that Clojure replaces with a unique name to avoid capturing the caller's variables.

**automatic variable**: a placeholder in a rule such as `$@` or `$<` that stands for the target or its dependencies when the rule is instantiated.

**backtracking**: trying each possible choice in turn and, when one leads to a dead end, returning to try the next.

**benchmark**: a repeatable timing of a piece of code under controlled conditions, used to compare alternatives.

**big-endian**: a byte order in which the most significant byte of a number is stored first; the default for `java.nio.ByteBuffer`.

**bit**: a single binary digit, 0 or 1; a **byte** is eight bits, and is signed in Java and Clojure.

**bit packing**: storing several small fields in one integer using shifts and masks.

**block**: a node in a layout tree: a leaf of fixed size, a row, or a column.

**blocking I/O**: reading or writing in a way that stops the calling thread until the operation can complete.

**bottom-up pass**: a tree traversal that computes each node from its children, such as sizing.

**breakpoint**: an address at which a debugger stops the machine whenever execution reaches it.

**build manager**: a tool that reads a description of how targets depend on each other and runs the rules needed to bring requested targets up to date.

**call stack**: the chain of frames from the current call outward; because closures can keep frames alive, the live frames really form a tree.

**candidate**: an assignment of exactly one version to every package, which may or may not satisfy all constraints.

**cartesian product**: every way of picking one element from each of several collections; the set of all candidates.

**chain of responsibility**: a design in which each piece handles its own part of a job and passes the remainder to the next piece.

**character encoding**: a rule for turning characters into bytes, such as UTF-8 or UTF-16; the same text has different bytes and lengths under each.

**checksum**: a small value computed from a sequence of bytes and sent with it so that a reader can detect damage by recomputing and comparing.

**class**: a place to keep the behavior that all objects of one kind share. In the object system of [Chapter 2](../02-objects/), a map of methods with an optional parent.

**closure**: a function value that carries with it the variables that were in scope when it was created.

**collision**: two different inputs that receive the same hash.

**combinatorial explosion**: the exponential growth of the number of combinations as the number of independent choices grows, which makes exhaustive search infeasible.

**command pattern**: representing an operation as a value that can be stored, inverted, logged, and replayed.

**command table**: a map from the text a user can type to a description and the function that handles it, which can print its own help and be extended with `assoc`.

**constraint**: a rule about which versions of a dependency a package version can work with, given as a set of versions or as an expression such as `>=1.2,<3`.

**constructor**: the function that builds a new object; in our object system, a method called `:new`.

**content-addressed storage**: storing data under a name derived from a hash of its contents rather than from where it came from.

**context manager**: a construct that runs setup before a block of code and cleanup after it, whether the block returns or throws; in Clojure, usually a macro built around `try`/`finally`.

**dataframe**: a rectangular table of named columns, every column the same length.

**data-oriented design**: representing a structure as plain values and writing operations as functions over them rather than as methods on objects.

**debugger**: a tool that runs a program under the user's control, stopping it so that its state can be inspected.

**deftype**: defines a new class with named fields and only the interfaces you choose to implement.

**dependency injection**: passing the things a function needs, such as input and output functions, as arguments instead of having it reach out for them.

**derived value**: a value that is a cache of information already present elsewhere, kept for speed rather than as a source of truth.

**differential testing**: running two implementations of the same behavior on the same inputs and requiring identical results, each acting as the other's oracle.

**directed acyclic graph (DAG)**: a graph whose edges have direction and which contains no cycles; the shape a dependency graph must have for a build to be possible.

**directive**: an instruction embedded in a template, such as a `z-if` or `z-loop` attribute, that tells the expander to do something to its element.

**disassembler**: a translator from words back into assembly text, which cannot by itself tell code from data.

**dry run**: running a build tool so that it prints the commands it would execute without executing them.

**durability**: the guarantee that data acknowledged as written survives a crash; it requires syncing to disk.

**dynamic scope**: the rule that a function sees the variables of whoever is currently calling it.

**dynamic var**: a var marked `^:dynamic` whose value `binding` can replace for the current thread only, for the extent of a block.

**EDN**: Clojure's data notation, read with `clojure.edn/read-string` without evaluating anything.

**element**: a node in a parsed HTML tree, `{:tag :attrs :children}`; text nodes are plain strings and the root's tag is `:document`.

**endianness**: the order of the bytes of a multi-byte number; big-endian puts the most significant byte first, little-endian puts it last.

**environment**: the place variable values are looked up; often a stack of maps, one per scope.

**ephemeral port**: a port the operating system chooses when a program binds to port 0.

**escape sequence**: a short string beginning with the escape character that a terminal interprets as a command, such as moving the cursor, rather than printing.

**escaping**: replacing characters such as `&`, `<`, and `>` in inserted text so that data cannot become markup.

**expression problem**: the tension between adding new types easily (protocols) and adding new operations easily (multimethods).

**extension point**: a place, such as a protocol or multimethod, where code outside a library can add behavior for new types.

**fetch-decode-execute cycle**: the loop a processor runs: read the word at the program counter, unpack it, carry it out.

**finding**: one reported problem from a linter, a map of `{:line n :message s}`.

**fixed-size block**: a record encoded into a constant number of bytes, so that the position of record *n* in a file is *n* times the block size.

**form**: one unit of Clojure code as data: a list, vector, map, symbol, or literal.

**format string**: a tiny language such as `"<2i s f"` that describes the layout of values in a byte array for `pack` and `unpack`.

**forward declaration**: telling the compiler with `declare` that a var will be defined later so that it can be referenced earlier in the file.

**frame**: one map in an environment stack, pushed when a scope such as a loop body or function call begins.

**framing**: marking where one message ends and the next begins in a byte stream that has no boundaries of its own.

**future**: a computation run on another thread whose result is obtained by dereferencing it.

**glob**: a pattern language for file names using `*`, `?`, and `{a,b}`.

**grammar**: a written description of what well-formed input looks like, one rule per kind of thing.

**handler**: a function that takes a request map and returns a response map.

**hash function**: a function that maps data to a small fixed-size label such that equal inputs always get equal labels.

**header**: a name/value pair after an HTTP request or status line; names are case-insensitive.

**headless screen**: an implementation of a device protocol that records what was drawn in memory and answers input from a script, so that interactive programs can be driven by ordinary tests.

**heuristic**: a rule that is right often enough to be useful without being right every time.

**homoiconic**: a property of languages whose programs are written as the language's own data structures. Clojure code is lists, vectors, maps, and symbols.

**HTTP**: the text-based request/response protocol spoken by web browsers and servers.

**idempotent**: an operation whose effect is the same whether it is performed once or many times.

**identity**: whether two references point at the very same object in memory, tested with `identical?`, as opposed to `=`, which asks whether they have the same content.

**index**: a map from a record's key to its position in a log; derived data that can always be rebuilt by scanning the log.

**inheritance**: the lookup rule that lets an object use methods defined on its class's parent, grandparent, and so on.

**instruction set**: the fixed list of operations a machine understands; in [Chapter 25](../25-vm/), a data table shared by the machine, assembler, disassembler, and debugger.

**integrity check**: confirming that a stored file's contents still hash to the name it was stored under.

**interpreter**: a program that walks a representation of another program and carries out its instructions.

**invariant**: a property that must hold after every state change, such as "the cursor is always on screen", best re-established in one place after every action.

**inverse action**: the action that exactly reverses another; insert and delete are each other's inverses.

**iterator**: a stateful cursor over a collection with "is there more?" and "give me the next" operations.

**Java interop**: calling Java classes and methods directly from Clojure.

**JIT compiler**: the part of the JVM that compiles frequently run bytecode to machine code while the program runs, making early measurements unrepresentative.

**Kahn's algorithm**: a topological sort that repeatedly takes a node with no unvisited dependencies, and detects a cycle when nodes remain but none is ready.

**label**: a name given to a memory address in assembly source, resolved to a number by the assembler's first pass.

**lazy sequence**: a sequence whose elements are computed only when something asks for them.

**length prefix**: a framing scheme in which every message is preceded by a count of the bytes that follow it.

**lexical scope**: the rule that a function sees the variables visible where it was written; implemented by making a call frame's parent the closure's captured frame.

**linter**: a tool that reads source code and reports likely mistakes that the compiler accepts.

**loopback address**: `127.0.0.1`, an address that only programs on the same machine can reach.

**LRU cache**: a least-recently-used cache: a bounded store that, when full, discards the entry that has gone longest without being touched.

**macro**: a function that runs at compile time, receives its arguments as unevaluated code, and returns code to compile in its place.

**manifest**: a dated text file recording which path had which content hash when a backup was made.

**median**: the middle value of a sorted set of measurements; preferred over the mean for timings because it ignores the long tail of slow outliers.

**metadata**: a map attached to a value without affecting its equality, read back with `meta`.

**middleware**: a function that takes a handler and returns a new handler with added behavior.

**MIME type**: a label such as `text/html` that tells a client what kind of data a body holds.

**mock**: a predictable stand-in for a real dependency, such as a clock or a network service, swapped in while a test runs.

**multimethod**: a Clojure function that chooses its implementation by applying a dispatch function to its arguments.

**mutual recursion**: two functions that call each other, which in Clojure requires `declare` for whichever is defined second.

**ns-publics**: the function that returns a namespace's public vars as a map from symbol to var, which is how a program inspects code it has never seen.

**null object**: an implementation that does nothing, placed where `nil` would otherwise go so that callers never need to check.

**object**: a bundle of data and behavior; in Clojure, often just a map whose values may be functions.

**observer effect**: the distortion a measurement tool introduces into the thing it measures, largest for the smallest functions.

**O(n)**: a cost that grows in proportion to the size of the input.

**O(n²)**: a cost that grows with the square of the input size, so ten times the input means a hundred times the work.

**op code**: the number in the low byte of an instruction word that identifies which instruction it is.

**open dispatch**: the property of multimethods that a `defmethod` in any namespace can add a case, so that a core can be extended without being edited.

**package manager**: a tool that selects one version of every package in a set of dependencies such that every package's constraints on the others hold.

**page**: a fixed group of consecutive blocks read from or written to a file as a unit.

**parser**: a program that turns text into the structure a program wants.

**path**: the vector of tags from the top of a document down to the current element, which tells a callback where it is.

**path traversal**: using `..` in a request path to reach files outside the directory being served.

**pattern rule**: a rule whose target contains a wildcard so that one rule describes how to make every file matching it.

**persistence**: turning an in-memory value into bytes or text that outlives the process, and back again.

**persistent data structure**: an immutable collection whose "updates" return new versions that share structure with the old ones.

**pigeonhole principle**: if there are more items than labels, some label must be shared.

**polymorphism**: one piece of code working with many kinds of values because they all answer the same questions.

**profiler**: a tool that reports where a program's time goes, for example by counting and timing calls to wrapped functions.

**program counter**: the address of the next instruction to execute, advanced before each instruction runs so that branches can overwrite it.

**promise**: a one-shot container that one thread fills with `deliver` and another waits on with `deref`.

**protocol**: a named group of functions that a type can promise to implement; Clojure dispatches protocol functions on the type of their first argument.

**pruning**: cutting off a branch of a search tree once it is known that nothing below it can be a solution.

**pure model**: program state as an immutable map plus a step function from state and input to state, with all device I/O kept at the edge.

**raw mode**: a terminal setting in which each key is delivered to the program as soon as it is pressed, without line buffering or echo.

**reader**: the function `read` that turns text into Clojure forms without evaluating them.

**record**: a map-like Clojure type with named fields, created with `defrecord`, which can implement protocols.

**recursive-descent parser**: a parser with one function per grammar rule, where rules that refer to each other become functions that call each other.

**reductions**: like `reduce`, but returning every intermediate result; it turns a list of sizes into a list of positions.

**register**: one of a small number of named storage slots inside a machine that instructions operate on directly.

**reify**: creates an anonymous instance of one or more interfaces or protocols with method bodies given inline.

**request line**: the first line of an HTTP request: method, target, and protocol version separated by spaces.

**root binding**: the value a var holds for every thread, changed by `alter-var-root` or `with-redefs`.

**round-trip test**: checking that printing the parsed form of an input gives back the input, which tests the parser against an independent printer.

**routing**: choosing a handler by the request's method and path.

**row-major**: storing a table as a sequence of rows, which makes row-at-a-time operations cheap; **column-major** stores it as a map from column name to a vector of values, which makes column-at-a-time operations cheap.

**schema**: a data description of a record's fields, their order, and their types, from which packing and unpacking code is driven.

**scope**: the region of a program in which a variable binding is visible.

**scripted session**: a test that drives an interactive program with a fixed list of input lines and compares everything it wrote.

**search order**: the sequence in which choices are decided during backtracking; it does not change the solutions but changes how much work is done to find them.

**self-closing tag**: a tag like `<br/>` that is an element with no children and needs no closing tag.

**self-describing format**: a format in which each piece of data announces its own type and size, so that a reader never has to look ahead or guess.

**separation of concerns**: giving each layer one kind of input and one job so that each changes for its own reasons and can be tested alone.

**seq abstraction**: the single `seq` operation every collection function is written against; a type that implements `clojure.lang.Seqable` gets the whole library.

**server socket**: a socket that listens on a port and hands back a new socket for each client that connects.

**setup and teardown**: code run before and after each test, with teardown guaranteed by `finally`.

**SHA-256**: a cryptographic hash function producing a 256-bit value, for which collisions are considered impossible in practice.

**shadowing**: an inner binding hiding an outer one of the same name until the inner scope ends.

**sign extension**: widening a two's complement value by copying its top bit so that a negative number stays negative.

**single-stepping**: executing exactly one instruction and then stopping.

**snapshot**: a saved copy of the parts of a state a change can affect, restored wholesale by undo.

**socket**: one end of a network connection, offering an input stream to read what the peer wrote and an output stream to write to it.

**special form**: an operation that must see its arguments before they are evaluated, such as `:if` or `:while`, as opposed to one that is a plain function of its evaluated arguments.

**stale**: the state of a target that must be rebuilt because it is missing, a dependency is newer, or a dependency was rebuilt in the same run.

**state threading**: passing an explicit state value into every function and returning the updated state, instead of mutating a shared variable.

**status code**: the three-digit number on an HTTP response's first line saying how the request fared.

**structural sharing**: the property of persistent data structures that a modified copy shares all unchanged parts with the original, making old versions cheap to keep.

**table-driven test**: one test that loops over a table of cases, so that adding a case means adding a row.

**tagged vector**: a vector whose first element is a keyword naming what kind of node it is, such as `[:lit "abc"]`.

**target**: a named thing a build produces, with the targets it depends on and the rule that makes it; a **source** is a target with no rule that must already exist.

**template**: a document that is mostly fixed, with directives marking where data is filled in.

**test oracle**: a simple, trusted implementation used to check the output of a faster or cleverer one.

**test runner**: a program that finds tests by a convention, runs each one so that no test can stop the run, and reports the outcomes.

**thunk**: a function of no arguments, used to package up work so that something else can run it later.

**token**: the smallest meaningful chunk of input, such as a literal, a star, or a brace.

**tokenizer**: the layer that groups characters into tokens; also called a lexer.

**top-down pass**: a tree traversal that computes each node from its parent, such as placement.

**topological sort**: an ordering of a DAG's nodes in which every node comes after all the nodes it depends on.

**transducer**: a composable transformation such as `(remove pred)` that `into` can apply while pouring one collection into another without an intermediate sequence.

**tree-seq**: a function returning a lazy depth-first seq of every node in nested data.

**two-pass assembly**: assembling in two sweeps so that a label can be referred to before the line that defines it.

**two's complement**: the representation of signed integers in which a value with its top bit set is read as the unsigned value minus two to the width.

**type hint**: an annotation such as `^bytes` that tells the compiler which class a value has so that method calls need no run-time lookup.

**undo stack**: a vector of actions (or states) that have been done; a **redo stack** holds those that have been undone. A new action clears the redo stack to keep history linear.

**var**: the box a global definition lives in; a call looks inside the box at run time, which is why replacing its contents changes what callers do.

**violation**: a map `{:rule :path :message}` describing one problem found by a check.

**virtual machine**: a program that imitates a processor by repeatedly fetching, decoding, and executing instructions stored as numbers in memory.

**Visitor pattern**: separating the code that walks a tree from the code that acts at each node; in [Chapter 11](../11-html/), a map of `:enter`, `:exit`, and `:text` callbacks.

**warm-up**: running code before timing it so that the JIT compiler has compiled the hot paths and the measurement reflects steady state.

**wrapping**: rewriting a row that is wider than the page into a column of rows.

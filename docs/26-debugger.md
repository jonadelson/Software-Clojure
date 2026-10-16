---
title: "A Debugger"
nav_order: 26
lede: "An interactive debugger for the virtual machine, built so that a whole session can be scripted and tested."
---

Sooner or later a program for the [virtual machine](../25-vm/) does the
wrong thing, and the only tools we have for finding out why are reading
the assembly very carefully and sprinkling it with `prr`. A **debugger**
is the better tool: it runs the program under our control, stopping
where we ask so that we can look at the registers and memory, moving one
instruction at a time when we want to watch closely, and running freely
when we do not.

The hard part of a debugger is not the debugging. The virtual machine
already gives us a `step` function that moves a state forward by one
instruction and leaves the old state intact, so stopping, inspecting,
and resuming are all trivial. The hard part is the interaction: reading
commands, printing replies, and doing it in a way that we can test
without sitting at a keyboard. This chapter is therefore mostly about a
design question that comes up in every interactive program, namely how
to keep the part that talks to a person separate from the part that
does the work. The answer has three parts: every command is a pure
function of the debugger's state, output is queued as data rather than
printed, and the one loop that does read and write is handed its input
and output functions by whoever calls it, so a test can hand it a
script.

## The shape of the problem

The obvious way to write a debugger is a loop that reads a line from
standard input, does what the line says, and prints something. That
works, and it is how many debuggers started out, but it has a problem
that only shows up when we try to test it. A test would have to feed
text into standard input, capture standard output, and compare, and it
could never check anything about the state in between commands because
the state would be trapped inside the loop.

We will instead keep the debugger's state in a map, like the machine's:

```clojure
(declare commands)

(defn new-debugger
  "Wrap a machine state for debugging. `commands` maps each command
   name to {:doc ... :fn ...}; it is kept in the state so that `help`
   can describe it and so that later stages can extend it."
  ([machine] (new-debugger machine commands))
  ([machine commands]
   {:vm machine
    :commands commands
    :shown 0        ; how many lines of program output we have displayed
    :messages []    ; lines waiting to be written
    :done? false}))

(defn say
  "Queue one or more lines to be displayed after the current command."
  [dbg & lines]
  (update dbg :messages into lines))
```

The map holds the machine itself under `:vm`, the table of commands the
user can type (more on that shortly), and `:messages`, a vector of
lines that want to be shown to the user. That last key is the trick
that keeps everything else pure. A command that wants to print
something does not print; it calls `say`, which appends to the vector
and returns a new state. The loop, later, will write the queued lines
out and empty the vector. Until then a test can look at `:messages` the
same way it would look at a return value.

`declare` is new. It tells the compiler that `commands` will be defined
later in the file, so that `new-debugger` can refer to it now. We need
it because the command table has to mention the command functions, and
the command functions are defined after `new-debugger`. Clojure
compiles a file top to bottom and insists that every symbol be known
when it is used, so a forward reference has to be announced.

## Showing the machine

Every debugger spends most of its lines turning state into text. Ours
has a function for the registers, one for "where are we", and one for a
listing of several instructions:

```clojure
(defn format-registers
  "All four registers on one line."
  [vm]
  (str/join "  " (map-indexed (fn [i v] (str "R" i " = " v)) (:regs vm))))

(defn format-location
  "Where the machine is: the address and text of the next instruction,
   or a note that it has halted."
  [vm]
  (let [pc (:pc vm)]
    (cond
      (:halted? vm) "halted"
      (>= pc isa/ram-size) (str "pc off the end of memory: " pc)
      :else (format "%3d: %s" pc (asm/disassemble-word (nth (:ram vm) pc))))))

(defn format-listing
  "Disassembly of addresses `from` to `to` inclusive. `mark` is called
   with each address and returns the margin to print before it."
  [vm from to mark]
  (for [addr (range from (inc to))]
    (format "%s%3d: %s" (mark addr) addr (asm/disassemble-word (nth (:ram vm) addr)))))
```

`format-location` is what the user sees every time the machine stops,
and it uses the disassembler from the previous chapter to show the
instruction that will run next rather than a bare number. This is the
first repayment on the effort of writing the disassembler: a debugger
without one would show `65542` and leave the user to decode it.

`format-listing` takes a function, `mark`, that is given each address
and returns whatever should appear in the left margin. In this chapter
the margin shows an arrow at the program counter, and later it will
also show a star at each breakpoint. Passing the margin in as a
function means the listing does not need to know what the marks mean,
and the second stage of the debugger can change them without changing
the listing.

The machine keeps everything its program has printed in its `:output`
vector, which raises a question the previous chapter deferred: when
should the user see it? Showing all of it after every command would be
noise. We show only what is new, by remembering how much we have shown
so far:

```clojure
(defn show-output
  "Queue any lines the program has printed since the last time we looked."
  [dbg]
  (let [out (get-in dbg [:vm :output])
        fresh (subvec out (:shown dbg))]
    (-> (apply say dbg fresh)
        (assoc :shown (count out)))))

(defn after-running
  "What every command that moves the machine does afterwards: show new
   program output, then say where the machine is now."
  [dbg]
  (let [dbg (show-output dbg)]
    (say dbg (format-location (:vm dbg)))))
```

This is only possible because the machine records output instead of
printing it. Had `prr` called `println`, the program's output and the
debugger's would be interleaved on the console in whatever order they
happened, and a test would have no way to separate them. A decision
made in one chapter for the sake of testing turns out to be exactly
what the next chapter needs for a different reason, which happens more
often than you might expect: code that is easy to test is usually code
that is easy to reuse, because both properties come from the same
source, not hiding effects.

## Commands

Commands take arguments such as addresses, and users type things wrong,
so before the commands themselves we need a careful way to read what
they were given:

```clojure
(defn parse-address
  "A memory address typed by the user, or nil if it is not one."
  [text]
  (when-let [n (and text (re-matches #"\d+" text) (parse-long text))]
    (when (< n isa/ram-size) n)))

(defn address-range
  "Turn the arguments of a command like `m 10 20` into [from to], using
   `default` when no arguments were given. Returns nil for bad input."
  [[from to] default]
  (cond
    (nil? from) default
    (nil? to) (when-let [a (parse-address from)] [a a])
    :else (let [a (parse-address from) b (parse-address to)]
            (when (and a b (<= a b)) [a b]))))
```

Both return `nil` for anything they cannot make sense of, and the
commands turn that `nil` into a usage message. Throwing an exception
would be the wrong choice here: a typo is not an error in the debugger,
it is a normal thing that users do, and the response should be a polite
line of text rather than a stack trace and a dead session.

Now the commands. Each is a function of the debugger state and a vector
of argument strings, and each returns a new state:

```clojure
(defn step-cmd
  "Execute one instruction."
  [dbg _args]
  (if (get-in dbg [:vm :halted?])
    (say dbg "machine is halted")
    (after-running (update dbg :vm vm/step))))

(defn continue-cmd
  "Run until the machine halts."
  [dbg _args]
  (if (get-in dbg [:vm :halted?])
    (say dbg "machine is halted")
    (after-running (update dbg :vm vm/run))))

(defn registers-cmd
  "Show the registers."
  [dbg _args]
  (say dbg (format-registers (:vm dbg))))

(defn memory-cmd
  "Show one word of memory, or a range of them."
  [dbg args]
  (if-let [[from to] (address-range args nil)]
    (apply say dbg (for [addr (range from (inc to))]
                     (format "%3d: %d" addr (nth (get-in dbg [:vm :ram]) addr))))
    (say dbg "usage: m <address> [<address>]")))

(defn pc-mark
  "The margin for a listing: an arrow at the program counter."
  [vm]
  (fn [addr] (if (= addr (:pc vm)) ">" " ")))

(defn listing-range
  "The addresses a listing command should show: the ones the user asked
   for, or a window starting at the program counter."
  [dbg args]
  (let [pc (get-in dbg [:vm :pc])]
    (address-range args [pc (min (+ pc 7) (dec isa/ram-size))])))

(defn list-cmd
  "Show the instructions around the program counter, or a range."
  [dbg args]
  (if-let [[from to] (listing-range dbg args)]
    (apply say dbg (format-listing (:vm dbg) from to (pc-mark (:vm dbg))))
    (say dbg "usage: d [<address> [<address>]]")))

(defn help-cmd
  "List the commands."
  [dbg _args]
  (apply say dbg (for [[name {:keys [doc]}] (sort (:commands dbg))]
                   (format "%-4s %s" name doc))))

(defn quit-cmd
  "Leave the debugger."
  [dbg _args]
  (assoc dbg :done? true))
```

`step-cmd` is the whole reason the debugger is easy to write: it is
`vm/step` applied to the machine inside the state, followed by the
standard "show what happened" of `after-running`. `continue-cmd` is the
same with `vm/run`. Neither knows anything about instructions or
registers; they reuse the machine's own functions, which is what we
meant by keeping the step function separate from the loop. Even
`quit-cmd` does nothing but set a flag. No command reads input, and no
command writes output.

The commands are gathered into a table that maps what the user types to
a description and a function:

```clojure
(def commands
  "The command table: what each thing the user can type does."
  {"s" {:doc "step one instruction" :fn step-cmd}
   "c" {:doc "continue until the machine halts" :fn continue-cmd}
   "r" {:doc "show the registers" :fn registers-cmd}
   "m" {:doc "show memory: m <addr> or m <from> <to>" :fn memory-cmd}
   "d" {:doc "disassemble: d, d <addr>, or d <from> <to>" :fn list-cmd}
   "h" {:doc "this help" :fn help-cmd}
   "q" {:doc "quit" :fn quit-cmd}})
```

A `cond` or `case` on the command name would have worked, but a table is
data, and data can be examined and extended. `help-cmd` is the examining
part: it prints the table, so the help text can never fall out of step
with the commands. The extending part comes when we add breakpoints.

## The loop and the injected I/O

One function turns a line of input into a change of state:

```clojure
(defn dispatch
  "Apply one line of user input to the debugger state."
  [dbg line]
  (let [[name & args] (str/split (str/trim line) #"\s+")]
    (cond
      (str/blank? line) dbg
      (contains? (:commands dbg) name) ((get-in dbg [:commands name :fn]) dbg args)
      :else (say dbg (str "unknown command " name " (h for help)")))))
```

`dispatch` is still pure, and it is the function a test should reach
for when it wants to check one command in isolation. Everything impure
is in the loop below it:

```clojure
(defn- flush-messages
  "Write every queued line with `write` and clear the queue."
  [dbg write]
  (doseq [line (:messages dbg)]
    (write line))
  (assoc dbg :messages []))

(defn debug-loop
  "Run a session from `dbg`, reading lines with (:read io), which returns
   nil at end of input, and writing lines with (:write io). Returns the
   final debugger state."
  [dbg {:keys [read write]}]
  (loop [dbg (flush-messages (say dbg (format-location (:vm dbg))) write)]
    (if (:done? dbg)
      dbg
      (if-let [line (read)]
        (recur (flush-messages (dispatch dbg line) write))
        dbg))))

(defn debug
  "Debug `machine` interactively using the io map's :read and :write."
  [machine io]
  (debug-loop (new-debugger machine) io))
```

The loop does four things: say where the machine is, read a line,
dispatch it, and write whatever got queued. It stops when a command sets
`:done?` or when `read` returns `nil`, which is the convention
`read-line` uses for the end of input and the one our scripted reader
will copy. What it does *not* do is decide where lines come from or go
to. Those are the `:read` and `:write` functions in the `io` map, and
the caller supplies them.

This is **dependency injection**, a grand name for passing in the
things a function would otherwise have to reach out and find for
itself. For a terminal session the caller passes `read-line` and
`println`. For a test, it passes functions built from a script:

```clojure
(defn scripted-io
  "An io map whose :read hands out `lines` one at a time and whose
   :write collects what is written. Deref (:written io) to see it."
  [lines]
  (let [remaining (atom (seq lines))
        written (atom [])]
    {:read (fn []
             (let [line (first @remaining)]
               (swap! remaining next)
               line))
     :write (fn [line] (swap! written conj line))
     :written written}))

(defn run-script
  "Debug `machine` with a vector of command lines and return everything
   the debugger wrote."
  [machine lines]
  (let [io (scripted-io lines)]
    (debug machine io)
    @(:written io)))
```

Here, for the first time in two chapters, we need atoms. An **atom** is
a mutable reference to an immutable value: `@remaining` reads the
current value, and `(swap! remaining next)` replaces it with the result
of calling `next` on it. We need them because `:read` is called many
times and has to hand out a different line each time, which means it
has to remember how far it has got between calls, and a pure function
cannot remember anything. The same goes for `:write`, which has to
accumulate. The atoms are confined to the edges, in the two functions
that stand in for the keyboard and the screen; the debugger itself
never sees them and remains a chain of pure state transformations.

With `run-script`, a test for an entire session is a vector of commands
and a vector of expected lines. This is the test from
`test/sdx/debugger/core_test.clj` that runs the countdown program for
two steps, looks at a word of memory, and lets it finish:

```clojure
(deftest a-scripted-session
  (is (= ["  0: ldc R0 5"      ; where we start
          "  1: ldc R1 1"      ; after s
          "  2: prr R0"        ; after s
          " 10: 0"             ; m 10
          "5" "4" "3" "2" "1"  ; the program's output, after c
          "halted"]
         (dbg/run-script (machine "count_down.as") ["s" "s" "m 10" "c"]))))
```

And this is a test that bypasses the loop entirely, because the command
it is checking is a pure function and can be called like one:

```clojure
(deftest commands-are-pure-functions
  (let [d (dbg/new-debugger (machine "count_down.as"))
        d' (dbg/dispatch d "s")]
    (testing "the result carries the new machine and the lines to show"
      (is (= 1 (get-in d' [:vm :pc])))
      (is (= ["  1: ldc R1 1"] (:messages d'))))
    (testing "the input is untouched"
      (is (= 0 (get-in d [:vm :pc])))
      (is (= [] (:messages d))))))
```

> **Why not rebind `*in*` and `*out*`?**
> Clojure has another way to redirect input and output: `read-line` and
> `println` use the dynamic variables `*in*` and `*out*`, and
> `with-in-str` and `with-out-str` rebind them for the duration of a
> block, as the [chapter on protocols](../09-protocols/) discussed. We
> could have written the loop against `read-line` and `println` and
> tested it that way. It would have been shorter. But the rebinding is
> invisible: nothing in the signature of `debug-loop` would say that it
> reads and writes, and a reader would have to know about the dynamic
> variables to understand how the tests work. Passing the functions in
> makes the dependency part of the interface, and it also means a
> debugger could be driven over a socket, or from a GUI, by passing
> different functions, with no rebinding at all.

## Breakpoints

Stepping one instruction at a time through a loop that runs ten
thousand times is not debugging, it is penance. A **breakpoint** is an
address at which the machine should stop whenever it gets there, so
that we can run freely up to the interesting part and then look around.
We add breakpoints in a second namespace, `sdx.debugger.breakpoints`,
which starts by extending the state:

```clojure
(defn new-debugger
  "A debugger for `machine` that knows the program's `labels` and
   starts with no breakpoints."
  [machine labels]
  (-> (core/new-debugger machine)
      (assoc :breakpoints #{}
             :labels labels)))
```

The breakpoints are a set of addresses. We also keep the assembler's
label table, because typing `b loop` is much nicer than counting
instructions to work out that the loop starts at address 2.

Continuing now means running until the machine halts *or* reaches a
breakpoint. The machine's `run` does not know about breakpoints and
should not, so we write a more general loop over `step`:

```clojure
(defn run-until
  "Step the machine until it halts or `stop?` is true of its state.
   Always takes at least one step, so that continuing from a breakpoint
   moves past it rather than stopping on it again."
  [machine stop?]
  (loop [m (vm/step machine)]
    (if (or (:halted? m) (stop? m))
      m
      (recur (vm/step m)))))
```

The docstring records a subtlety that every debugger has to get right.
When the machine is stopped at a breakpoint and the user says
"continue", the program counter is *at* the breakpoint. If `run-until`
checked `stop?` before stepping, it would stop immediately without
executing anything, and the user would be stuck. Taking one step before
the first check solves it. The same trick is what makes a breakpoint
inside a loop stop once per iteration rather than forever.

The breakpoint command toggles an address, listing them all when given
no argument, and the new `continue` uses `run-until`:

```clojure
(defn resolve-address
  "A number or a label name typed by the user, as an address, or nil."
  [dbg text]
  (or (core/parse-address text)
      (get (:labels dbg) text)))

(defn break-cmd
  "Set or clear a breakpoint; with no argument, list them."
  [dbg [arg]]
  (let [bps (:breakpoints dbg)]
    (cond
      (nil? arg)
      (if (empty? bps)
        (core/say dbg "no breakpoints")
        (core/say dbg (str "breakpoints: " (str/join " " (sort bps)))))

      (resolve-address dbg arg)
      (let [addr (resolve-address dbg arg)]
        (if (contains? bps addr)
          (-> dbg (update :breakpoints disj addr)
              (core/say (str "cleared breakpoint at " addr)))
          (-> dbg (update :breakpoints conj addr)
              (core/say (str "breakpoint at " addr)))))

      :else
      (core/say dbg (str "no such address or label: " arg)))))

(defn continue-cmd
  "Run until the machine halts or reaches a breakpoint."
  [dbg _args]
  (if (get-in dbg [:vm :halted?])
    (core/say dbg "machine is halted")
    (let [bps (:breakpoints dbg)
          at-breakpoint? (fn [m] (contains? bps (:pc m)))]
      (core/after-running (update dbg :vm run-until at-breakpoint?)))))
```

Notice that `continue-cmd` builds the `stop?` predicate from the set of
breakpoints and then forgets about them. `run-until` could be reused
for "run until register 0 is zero" or "run until something is written
to address 40" by passing a different predicate, which is the second
exercise. We have separated the policy (where to stop) from the
mechanism (stepping until a condition holds), and it cost us nothing.

The listing should show where the breakpoints are, which is what the
`mark` function on `format-listing` was for:

```clojure
(defn breakpoint-mark
  "The margin for a listing: a star for a breakpoint, an arrow for the
   program counter."
  [dbg]
  (let [pc (get-in dbg [:vm :pc])
        bps (:breakpoints dbg)]
    (fn [addr]
      (str (if (contains? bps addr) "*" " ")
           (if (= addr pc) ">" " ")))))

(defn list-cmd
  "Show the instructions around the program counter, marking breakpoints."
  [dbg args]
  (if-let [[from to] (core/listing-range dbg args)]
    (apply core/say dbg (core/format-listing (:vm dbg) from to (breakpoint-mark dbg)))
    (core/say dbg "usage: d [<address> [<address>]]")))
```

Finally, the command table. Because the first stage kept its commands
as data, the second stage's table is the first stage's with one entry
added and two replaced:

```clojure
(def commands
  "The core command table with breakpoints added, and continue and
   disassemble replaced by versions that know about them."
  (assoc core/commands
         "b" {:doc "breakpoint: b <addr or label> toggles, b lists" :fn break-cmd}
         "c" {:doc "continue until a breakpoint or the machine halts" :fn continue-cmd}
         "d" {:doc "disassemble: d, d <addr>, or d <from> <to>" :fn list-cmd}))

(defn debug
  "Debug `machine` interactively, with breakpoints, using the io map's
   :read and :write. `labels` maps label names to addresses."
  [machine labels io]
  (core/debug-loop (assoc (new-debugger machine labels) :commands commands) io))

(defn run-script
  "Debug `machine` with a vector of command lines and return everything
   the debugger wrote."
  [machine labels lines]
  (let [io (core/scripted-io lines)]
    (debug machine labels io)
    @(:written io)))
```

Nothing in `sdx.debugger.core` was edited to make this work, and its
tests still pass against its own table. The loop, `dispatch`, `say`,
`show-output`, the formatting functions, and the scripted I/O are all
reused as they are. The tests in `test/sdx/debugger/breakpoints_test.clj`
use the same scripting approach, with a helper that assembles a sample
program and keeps its label table:

```clojure
(deftest stopping-at-a-breakpoint
  (is (= ["  0: ldc R0 5"
          "breakpoint at 3"
          "5"                  ; printed by prr before we stop
          "  3: sub R0 R1"
          "4"
          "  3: sub R0 R1"
          "R0 = 4  R1 = 1  R2 = 0  R3 = 0"]
         (session "count_down.as" ["b 3" "c" "c" "r"]))))

(deftest breakpoints-by-label
  (is (= ["  0: ldc R0 5"
          "breakpoint at 2"
          "  2: prr R0"
          "breakpoints: 2"
          "cleared breakpoint at 2"
          "no breakpoints"
          "5" "4" "3" "2" "1"
          "halted"]
         (session "count_down.as" ["b loop" "c" "b" "b 2" "b" "c"]))))
```

The first test shows the machine stopping at the same address twice,
with one line of program output between the stops and the register
display confirming that the counter went down by one. Reading the
expected output top to bottom is reading a transcript of a debugging
session, which is as good a specification of what a debugger does as
any prose.

## At the terminal

For actual use, the input and output functions are the console:

```clojure
(def console-io
  "Read commands from the keyboard, after a prompt, and print replies."
  {:read (fn [] (print "> ") (flush) (read-line))
   :write println})

(defn -main
  "Assemble one program and start a debugging session on it."
  [& [path]]
  (if-not path
    (binding [*out* *err*]
      (println "usage: clojure -M -m sdx.debugger.main program.as"))
    (let [items (asm/parse (slurp path))
          labels (asm/label-addresses items)
          machine (vm/new-machine (asm/assemble-items items))]
      (bp/debug machine labels console-io)
      nil)))
```

Even the prompt is part of the injected `:read` rather than the loop:
a scripted session has no use for one, and a session over a socket
might want a different one. Here is the countdown program being
debugged from the shell, with a breakpoint set by label:

```
$ clojure -M -m sdx.debugger.main resources/vm/count_down.as
  0: ldc R0 5
> b loop
breakpoint at 2
> c
  2: prr R0
> r
R0 = 5  R1 = 1  R2 = 0  R3 = 0
> s
5
  3: sub R0 R1
> d
 >  3: sub R0 R1
    4: bne R0 2
    5: hlt
    6: .word 0
    7: .word 0
    8: .word 0
    9: .word 0
   10: .word 0
> c
  2: prr R0
> q
```

The program printed `5` when we stepped over `prr`, the listing shows
the arrow on the next instruction, and continuing went once round the
loop and stopped at the breakpoint again. Everything the terminal shows
is a line that some command queued with `say` and that `console-io`
printed, which is why the same session, with the same expected lines,
appears in the test file.

## Summary

- A debugger is a loop that reads commands and moves a machine; if the
  machine's `step` is a pure function, the debugger's commands can be
  pure functions too.
- Output that is queued as data in the state can be inspected by tests
  and written out by whoever owns the loop; output that is printed
  directly can only be captured.
- Injecting input and output functions keeps the debugger ignorant of
  where lines come from, so one loop serves a terminal, a test script,
  or anything else.
- Atoms belong at the edges: the scripted reader and writer need them,
  the debugger itself does not.
- A command table is data: it can print its own help, and a later stage
  can extend it with `assoc` instead of editing the original.
- Separating "run until a condition holds" from "the condition is a
  breakpoint" makes both halves reusable, and taking one step before
  checking the condition is what lets a session continue from a
  breakpoint.
- Recording output in the virtual machine, a decision made for
  testability, is also what lets the debugger show new output and
  nothing else: hidden effects are the common enemy of testing and
  reuse.

## Exercises

1. **Watching memory.** Add a command `w <addr>` that stops the
   machine whenever the word at that address changes. You will need to
   compare states; which two?

2. **Conditional stops.** Using `run-until` directly, add `cz <reg>`:
   run until the named register is zero. What should it do if the
   register is already zero when the command is given?

3. **Repeat on enter.** Many debuggers repeat the previous command when
   the user presses enter on an empty line. Add this. Where does the
   previous command have to be remembered, and what does that do to
   `dispatch`?

4. **Labels in listings.** The debugger has the label table but the
   listing does not show it. Make `d` print `loop:` on its own line
   before address 2, and show `bne R0 @loop` instead of `bne R0 2`.

5. **Stepping backwards.** Because states are immutable, the debugger
   could keep every state it has passed through. Add a `back` command
   that returns to the previous state, and decide what should happen to
   the output that the undone instructions produced. (Compare the
   [undo chapter](../24-undo/).)

6. **Breakpoints that count.** Change a breakpoint from an address to
   a map with an address and a count, and make `c` stop only on the
   n-th arrival. What tests from this chapter need to change?

7. **A different front end.** Write a `socket-io` that reads commands
   from a `java.net.Socket` and writes replies back to it, and run the
   debugger over `telnet` or `nc`. What, if anything, in the two
   debugger namespaces had to change?

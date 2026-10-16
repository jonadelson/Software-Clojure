---
title: "Matching Patterns"
nav_order: 4
lede: "A glob matcher built first from a chain of records and then from plain data, and why the second is better."
---

Shells have let people say `*.txt` to mean "every file whose name ends in
`.txt`" for fifty years, and the notation has spread to build tools,
editors, and configuration files. These patterns are called **globs**,
and they are a good first pattern language to implement because they are
small enough to finish in a chapter and rich enough to have one real
problem in them: a `*` can stand for any number of characters, and the
matcher has to find out how many.

We build the matcher twice. The first version is made of records, each
of which knows how to match one kind of pattern piece and how to pass the
rest of the job to the piece after it. This is a well-known arrangement
called a chain of responsibility, and it teaches two ideas that are worth
having, the null object and backtracking. The second version throws the
records away and represents a pattern as plain data: a vector of small
vectors. Everything the first version could do, the second does in fewer
lines, and several things that were awkward become trivial. Seeing why is
the point of the chapter, and the parser in [the next
chapter](../05-parser/) builds on the data version.

Our patterns have four kinds of piece: literal text, which matches itself;
`?`, which matches exactly one character; `*`, which matches any number
of characters including none; and `{a,b}`, which matches whichever of its
alternatives fits. A pattern matches a text only if it accounts for the
whole text, so `*.txt` matches `notes.txt` but `txt` does not.

## One piece at a time

The first design starts from a question: what is the smallest thing a
matcher could be responsible for? The answer is one piece of a pattern.
A matcher for the literal `abc` should check that the text has `abc` at
the current position, and nothing more. But if that is all it does, who
checks the rest of the text? The chain of responsibility answer is: the
next matcher. Each piece holds a reference to the piece after it, called
`rest`, and after matching its own part it asks `rest` to match from
where it left off.

We write this contract down as a protocol. As
[Chapter 2](../02-objects/) explained, a **protocol** is a named set of
functions that different types can implement in their own way, and a
**record** is a map-like type with named fields that can implement one:

```clojure
(defprotocol Matcher
  "One link in a chain of pattern pieces."
  (match-at [this text pos]
    "True if this piece matches `text` starting at `pos` and every piece
     after it matches the remainder of the text."))
```

The docstring is the whole design. `match-at` does not answer "does this
piece match here?" but "does this piece *and everything after it* match
from here to the end?". The difference is what lets the pieces be
combined without any of them knowing about the whole pattern.

A literal is the simplest piece:

```clojure
(defrecord Lit [chars rest]
  Matcher
  (match-at [_ text pos]
    (let [end (+ pos (count chars))]
      (and (<= end (count text))
           (= chars (subs text pos end))
           (match-at rest text end)))))
```

`subs` extracts a substring between two positions, so the `and` reads:
there is room for the literal, the text has the literal there, and the
rest of the chain is happy with what comes after. `and` stops at the
first false value, so we never take a substring that would run off the
end.

> **`rest` is a field here.**
> Inside the body of a `defrecord`, the field names are ordinary local
> names, and this one shadows the core function `rest`. That is harmless
> as long as we do not need `clojure.core/rest` inside the record, which
> we do not, but it is the kind of thing to notice: a record's fields
> are bindings, not special syntax.

## Matching nothing at all, and matching anything

Every chain has to end somewhere, and the end has to answer the question
`match-at` asks. We could make `rest` optional and check for `nil` in
every record, but there is a tidier way. A **null object** is an
implementation of an interface that does nothing, placed where a `nil`
would otherwise go so that the code using it never has to check. Our null
object is the terminator of the chain, and "doing nothing" means
succeeding exactly when there is no text left:

```clojure
(defrecord Null []
  Matcher
  (match-at [_ text pos]
    (= pos (count text))))
```

This is also where the rule "a pattern must account for the whole text"
lives. No other record checks it; they all pass the buck to `rest`, and
the buck stops here. A chain built from these two records already
matches literals:

```clojure
(match (->Lit "abc" (->Null)) "abc")    ;; => true
(match (->Lit "abc" (->Null)) "abcd")   ;; => false
```

`->Lit` and `->Null` are constructor functions that `defrecord` generates
for us, taking the fields in order. Notice that the chain is built
inside out: the terminator first, then the piece before it, and so on
back to the start of the pattern. We will come back to that.

The `?` piece consumes exactly one character, whatever it is:

```clojure
(defrecord One [rest]
  Matcher
  (match-at [_ text pos]
    (and (< pos (count text))
         (match-at rest text (inc pos)))))
```

The `*` piece is where the chapter's real problem shows up. A `*` can
stand for zero characters, or one, or all of the text that remains, and
which is right depends on what comes after it. The pattern `*.txt`
matched against `notes.txt.txt` needs the `*` to take nine characters,
not five, even though `.txt` appears at position five too. The `*` cannot
know this on its own. What it can do is try every possibility and ask the
rest of the chain which one works:

```clojure
(defrecord Any [rest]
  Matcher
  (match-at [_ text pos]
    (boolean (some #(match-at rest text %)
                   (range pos (inc (count text)))))))
```

`(range pos (inc (count text)))` is every position the `*` could end at,
from `pos` (taking nothing) to the end of the text (taking everything).
`some` calls the function on each position in turn and returns the first
truthy result, or `nil` if there is none; `boolean` turns that `nil` into
`false` so that every matcher returns a real boolean. Because `range` is
lazy and `some` stops as soon as it succeeds, we do no more work than we
have to.

This is **backtracking**: making a choice, following it as far as it
goes, and if it leads to a dead end, coming back and trying the next
choice. Tracing `*.txt` against `notes.txt.txt` shows it at work. The
`*` first tries ending at position 0; the `Lit` for `.txt` looks at
`notes` and fails. Positions 1 through 4 fail the same way. At position
5 the `Lit` finds `.txt`, so it asks `Null` whether position 9 is the end
of the text; it is not, so `Null` says no, `Lit` says no, and the `*`
tries again. Positions 6, 7, and 8 fail at the `Lit`. At position 9 the
`Lit` finds `.txt` again, `Null` is asked about position 13, which is the
end, and everyone says yes:

```clojure
(match (->Any (->Lit ".txt" (->Null))) "notes.txt.txt")   ;; => true
```

The chain of responsibility made this easy to write. `Any` does not know
what comes after it or how to tell whether a choice was good; it only
knows how to generate choices and ask. The cost is that a pattern with
several stars can try a lot of combinations. `*a*a*a` against a long
string of `a`s with no final match tries every way of splitting the
string three times, which grows quickly. Real glob implementations and
regular expression engines spend most of their cleverness on avoiding
this, and we do not, because the patterns people type at a shell prompt
are short.

## Choosing between alternatives

The last piece is `{pdf,txt}`, which matches if either of its
alternatives does. Each alternative is a pattern in its own right, which
means that with records, each alternative is a chain ending in its own
`Null`. That creates a puzzle. The alternative `pdf` must match some of
the text, and then the piece *after the braces* must match the rest, but
the alternative's `Null` insists on being at the end of the text. We
cannot re-point the alternative's terminator at our `rest`; the chain was
sealed when it was built.

The way out is the same trick `*` used. We do not know how much text the
alternative will consume, so we try every possible amount, hand the
alternative exactly that much text, and ask our own `rest` about what is
left:

```clojure
(defrecord Either [left right rest]
  Matcher
  (match-at [_ text pos]
    (boolean (some (fn [end]
                     (let [piece (subs text pos end)]
                       (and (or (match-at left piece 0)
                                (match-at right piece 0))
                            (match-at rest text end))))
                   (range pos (inc (count text)))))))
```

The alternative sees only `piece`, a copy of the text from `pos` to
`end`, so its `Null` is satisfied when it reaches the end of the piece.
This works, and it works for alternatives that contain stars and even
nested braces. It is also plainly a workaround. We copy substrings we do
not need, we try split points that could not possibly work, and the
reason is nothing to do with the problem of matching: it is that each
record's `rest` is baked in at construction time.

The function that starts a chain is the only piece of plain code in the
namespace:

```clojure
(defn match
  "True if the whole of `text` matches the chain starting at `matcher`."
  [matcher text]
  (match-at matcher text 0))
```

## Testing with a table

The tests for this stage are in `test/sdx/glob/first_test.clj`, and they
have a shape that is worth copying whenever you test something with many
small cases. Instead of one `deftest` per case, there is one table of
cases and one loop:

```clojure
(def cases
  "Each row is a name, a matcher, a text, and the expected answer."
  [["empty pattern matches empty text"   (->Null) "" true]
   ["empty pattern rejects text"         (->Null) "a" false]
   ["literal matches itself"             (->Lit "abc" (->Null)) "abc" true]
   ["literal rejects a prefix"           (->Lit "abc" (->Null)) "abcd" false]
   ["literal rejects a shorter text"     (->Lit "abc" (->Null)) "ab" false]
   ["literal rejects different text"     (->Lit "abc" (->Null)) "abd" false]
   ["? matches exactly one character"    (->Lit "a" (->One (->Lit "c" (->Null)))) "abc" true]
   ["? rejects zero characters"          (->Lit "a" (->One (->Lit "c" (->Null)))) "ac" false]
   ["* matches nothing"                  (->Any (->Null)) "" true]
   ["* matches everything"               (->Any (->Null)) "anything at all" true]
   ["* at the end"                       (->Lit "a" (->Any (->Null))) "abc" true]
   ["* at the start"                     (->Any (->Lit "c" (->Null))) "abc" true]
   ["* in the middle"                    (->Lit "a" (->Any (->Lit "c" (->Null)))) "abc" true]
   ["* backtracks to find the suffix"    (->Any (->Lit ".txt" (->Null))) "notes.txt.txt" true]
   ["* cannot invent characters"         (->Lit "a" (->Any (->Lit "c" (->Null)))) "ab" false]
   ["two stars"                          (->Any (->Lit "b" (->Any (->Null)))) "abc" true]
   ["either takes the left branch"       (->Either (->Lit "a" (->Null)) (->Lit "b" (->Null)) (->Null)) "a" true]
   ["either takes the right branch"      (->Either (->Lit "a" (->Null)) (->Lit "b" (->Null)) (->Null)) "b" true]
   ["either rejects a third option"      (->Either (->Lit "a" (->Null)) (->Lit "b" (->Null)) (->Null)) "c" false]
   ["either followed by more"            (->Either (->Lit "a" (->Null)) (->Lit "b" (->Null)) (->Lit ".txt" (->Null))) "b.txt" true]
   ["either with a star inside"          (->Either (->Any (->Lit "a" (->Null))) (->Lit "b" (->Null)) (->Lit "!" (->Null))) "xxa!" true]
   ["either branches differ in length"   (->Lit "x" (->Either (->Lit "pdf" (->Null)) (->Lit "txt" (->Null)) (->Null))) "xtxt" true]])

(deftest table-of-matches
  (doseq [[name matcher text expected] cases]
    (testing name
      (is (= expected (g/match matcher text))))))
```

`doseq` runs its body once per row, destructuring each row into four
names, and `testing` labels the assertion so that a failure reports which
row broke. **Table-driven tests** like this have two advantages over a
pile of separate tests. Adding a case is adding one line, so people add
them. And the table is readable as a specification: scanning the names
down the left tells you what the matcher is supposed to do, including the
cases you had not thought about, such as whether `*` can match nothing.

Look at the table for a moment, though, and notice how much of it is
scaffolding. Every row ends in `(->Null)`, every alternative in `Either`
needs its own `(->Null)`, and a pattern as short as `x{pdf,txt}` takes
most of a line to write down. The table is telling us that our
representation of patterns is clumsy.

## The pattern as data

Here is the observation that leads to the second version. In the record
version, the structure of a pattern is a chain: each piece points at the
next, and the last points at `Null`. But a chain of things where each
points at the next is just a sequence, and a sequence is something
Clojure already has. If we write a pattern as a vector of pieces, the
"next piece" is the next element, and the terminator is the empty
sequence. The records were a hand-made linked list.

So a pattern becomes a vector of nodes, and each node a small vector
whose first element says what kind of node it is:

```clojure
[[:lit "2023-"] [:any] [:lit "."] [:either [[:lit "pdf"]] [[:lit "txt"]]]]
```

This is the pattern `2023-*.{pdf,txt}`. The `:either` node holds its
alternatives, and each alternative is itself a vector of nodes, which is
to say a pattern. Nothing in this is a record or a protocol; it is a
literal you could write in a test or read from a file.

Matching such a pattern is one function. There is no dispatch on type
because there are no types, just a `case` on the node's tag:

```clojure
(defn match-at
  "True if `nodes` match `text` from `pos` to the end of the text."
  [nodes text pos]
  (if (empty? nodes)
    (= pos (count text))
    (let [[node & more] nodes
          n (count text)]
      (case (first node)
        :lit (let [[_ chars] node
                   end (+ pos (count chars))]
               (and (<= end n)
                    (= chars (subs text pos end))
                    (match-at more text end)))
        :one (and (< pos n)
                  (match-at more text (inc pos)))
        :any (boolean (some #(match-at more text %)
                            (range pos (inc n))))
        :either (let [[_ & alternatives] node]
                  (boolean (some #(match-at (concat % more) text pos)
                                 alternatives)))
        (throw (ex-info (str "unknown node type " (first node))
                        {:node node}))))))
```

Read it against the records. The `(empty? nodes)` branch is `Null`. The
destructuring `[node & more]` splits the sequence into the first node and
the rest, so `more` plays the part that the `rest` field played, except
that nobody had to store it. The `:lit`, `:one`, and `:any` branches are
the bodies of `Lit`, `One`, and `Any` with `more` in place of `rest`.
`case` compares its argument against literal keywords and runs the
matching branch; the final expression with no keyword in front of it is
the default, which we use to complain about a node we do not recognize
rather than silently failing to match.

The `:either` branch is the one to study. The puzzle from the record
version, that an alternative's terminator was sealed in, has vanished.
An alternative is a vector of nodes, and "match this alternative and then
whatever follows the braces" is `(concat alternative more)`: splice the
alternative in front of the rest of the pattern and carry on matching.
No substrings, no guessing where the alternative ends, and it works for
any number of alternatives rather than exactly two. `concat` returns a
lazy sequence rather than a vector, which is why `match-at` is written in
terms of `empty?` and destructuring, which work on any sequence, rather
than vector indexing.

```clojure
(match [[:lit "a"] [:any] [:lit ".txt"]] "abc.txt")                    ;; => true
(match [[:lit "a"] [:either [[:lit "pdf"]] [[:lit "txt"]]]] "atxt")   ;; => true
(match [] "")                                                          ;; => true
```

The public entry point is as small as before:

```clojure
(defn match
  "True if the whole of `text` matches the pattern `nodes`."
  [nodes text]
  (match-at nodes text 0))
```

The table in `test/sdx/glob/data_test.clj` is the same list of cases
with patterns written as data, and it is easier to read than the record
version in exactly the way the previous section predicted:

```clojure
   ["* backtracks to find the suffix"    [[:any] [:lit ".txt"]] "notes.txt.txt" true]
   ["either followed by more"            [[:either [[:lit "a"]] [[:lit "b"]]] [:lit ".txt"]] "b.txt" true]
   ["either with three alternatives"     [[:either [[:lit "a"]] [[:lit "b"]] [[:lit "c"]]]] "c" true]
   ["either with an empty alternative"   [[:lit "a"] [:either [] [[:lit "-x"]]]] "a" true]
```

The last two rows could not be written for the records at all without
changing `Either`. An empty alternative, which makes the braces optional,
falls out of the data version for free: `(concat [] more)` is `more`.

## A second reading of the same data

Because a pattern is now a value rather than a bundle of behavior, other
code can read it too. Here is a function that turns a pattern back into
glob text. It was written after `match` and without touching it:

```clojure
(defn pattern->string
  "The glob text that a pattern stands for. A second interpretation of
   the same data, written without touching `match`."
  [nodes]
  (apply str
         (map (fn [[kind & args]]
                (case kind
                  :lit (first args)
                  :one "?"
                  :any "*"
                  :either (str "{" (str/join "," (map pattern->string args)) "}")))
              nodes)))
```

```clojure
(pattern->string [[:lit "2023-"] [:any] [:lit "."] [:either [[:lit "pdf"]] [[:lit "txt"]]]])
;; => "2023-*.{pdf,txt}"
```

Adding this to the record version would have meant adding a method to the
protocol and an implementation to all five records, or defining a second
protocol and extending each record to it. Both are possible in Clojure,
and in a language where protocols cannot be extended from outside, the
second is not. Either way the behavior for one kind of node is scattered
across the places where that node's type is defined, while in the data
version each operation is one function you can read top to bottom.

The two stages agree with each other, and the test that checks it is
also a small lesson in why the records were awkward. To build a record
chain from the data form, we have to start at the end and wrap each
earlier node around the chain built so far, which is a `reduce` over the
reversed pattern:

```clojure
(defn- build
  "Turn a pattern in data form into a chain of sdx.glob.first records."
  [nodes]
  (reduce (fn [chain [kind & args]]
            (case kind
              :lit (f/->Lit (first args) chain)
              :one (f/->One chain)
              :any (f/->Any chain)
              :either (let [[left right] args]
                        (f/->Either (build left) (build right) chain))))
          (f/->Null)
          (reverse nodes)))

(deftest the-two-stages-agree
  (doseq [[name pattern text expected] cases
          :when (not (#{"either with three alternatives"} name))]
    (testing name
      (is (= expected (f/match (build pattern) text))))))
```

The `:when` clause skips the one row the record version cannot express.

## Why data won here

It would be wrong to conclude that records and protocols are bad; the
next chapters use them where they fit. The lesson is narrower. The record
version encoded two different things in the same structure: what each
piece of the pattern *is*, and how the pieces are *connected*. The
connection was a field in every record, set at construction and never
changed, and every awkwardness we met traced back to it: building chains
inside out, sealing terminators into alternatives, writing a null object
to stand in for "nothing follows". Once the connection became the order
of a vector, each node only had to say what it was, and the sequence
functions Clojure already has did the connecting.

A few more things came for free, and they are the usual signs that data
was the right choice:

- **Patterns are values.** Two patterns built separately are `=` if
  they have the same contents, they print as something you can paste
  back into the REPL, and they can be stored in a file or sent over a
  wire. Records are values too, but a chain of five nested records
  prints as a thicket.
- **Patterns can be built by other programs.** The parser in [the next
  chapter](../05-parser/) produces exactly these vectors from text, and
  did not need to know anything about how matching works to do it.
- **Several functions can interpret the same data.** `match` and
  `pattern->string` are two; a function that estimates how expensive a
  pattern is to match, or that simplifies `**` to `*`, would be two more.

The rule of thumb this suggests: when the things you are modelling are
mostly structure, with a small fixed set of kinds and operations that
walk the whole structure, use data and write the operations as functions.
When they are mostly behavior, with an open set of kinds that each need
their own implementation of a few operations, protocols and records earn
their keep. Pattern matching is the first kind of problem, and it took
building it the wrong way first to see that clearly.

## Summary

- A chain of responsibility splits a job into pieces, each of which
  handles its own part and passes the remainder to the next piece.
- A null object stands in for "nothing here" so that the code using it
  never has to check for `nil`; our terminator also enforced that a
  pattern must match the whole text.
- Backtracking means trying each choice in turn and asking the rest of
  the computation whether it worked. `some` over a lazy `range` is
  Clojure's natural way to write it.
- When each object's only link to the others is a fixed "next" field,
  the objects are a hand-made list, and a vector of plain data will do
  the same job with less code.
- Representing a pattern as data makes it a value that can be printed,
  compared, generated by other code, and interpreted by more than one
  function.
- Table-driven tests make cases cheap to add and turn the test file into
  a readable specification. They also reveal when a representation is
  clumsy to write.

## Exercises

1. **Character classes.** Add a node `[:class "aeiou"]` that matches one
   character from the given set to `sdx.glob.data`, and a `Class` record
   to `sdx.glob.first`. Which version was less work? Extend
   `pattern->string` too.

2. **Greedy stars.** `Any` tries the shortest match first. Change it to
   try the longest first, and show with a test that the answer never
   changes. Can you construct a pattern and text for which one order is
   much faster than the other?

3. **Counting the work.** Wrap `sdx.glob.data/match-at` so that it counts
   how many times it is called (you will need an atom, and should say
   why). Compare the counts for `*a*a*a*b` against strings of twenty and
   forty `a`s.

4. **Sharing the tail.** The record version's `Either` could have avoided
   the substring trick if both alternatives had been built to end in the
   same `rest` instead of in their own `Null`. Implement that version,
   write the test table for it, and say in a sentence what it costs the
   person constructing patterns.

5. **Validation.** `match-at` discovers an unknown node only when
   matching reaches it, so `[[:lit "a"] [:bogus]]` against `"b"` fails
   without complaint. Write `valid?`, a function that checks every node
   of a pattern up front, including those inside alternatives.

6. **Simplifying.** Two stars in a row mean the same as one, and two
   literals in a row can be joined. Write `simplify`, a function from
   patterns to patterns, and test that `(match (simplify p) t)` always
   equals `(match p t)`.

7. **Maps instead of vectors.** Rewrite the data version with nodes like
   `{:type :lit :chars "a"}` instead of `[:lit "a"]`. Which version is
   easier to read in the test table, and which is easier to extend with
   new fields?

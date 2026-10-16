---
title: "Parsing Text"
nav_order: 5
lede: "A tokenizer and a recursive-descent parser that turn glob text into the matcher's data."
---

The matcher in [the previous chapter](../04-glob/) works, but nobody
wants to type `[[:lit "2023-"] [:any] [:lit "."] [:either [[:lit "pdf"]]
[[:lit "txt"]]]]` when they mean `2023-*.{pdf,txt}`. Every tool that
accepts a little language, whether it is a glob, a date format, a query,
or a programming language, has the same job to do first: turn the text
people write into the structure the program wants. The program that does
this is a **parser**, and this chapter builds one.

Parsers have a reputation for being hard, and the full-featured ones
are. The one here is about sixty lines, and it is built the way most
hand-written parsers are: in two layers. The first layer, the
**tokenizer**, groups characters into meaningful chunks called tokens.
The second, the parser proper, groups tokens into the nested structure
the matcher expects, following a short written grammar. Separating the
two is the chapter's main design idea, and we spend some time on why it
pays off. Along the way we meet recursion as a way of handling nesting,
and the question of what to do when the input is wrong.

## Tokens

A pattern like `a*.txt` is a sequence of six characters, but it is more
useful to see it as three things: the literal `a`, a star, and the
literal `.txt`. Those things are **tokens**, and the tokenizer's job is
to find them. In our language the characters `*`, `?`, `{`, `,`, and `}`
each mean something; every other character stands for itself, and a run
of such characters forms one literal.

A token is a map. It has a `:type`, the `:pos` of the character it
started at, and, for literals, the `:text`:

```clojure
(def ^:private special
  "Characters that mean something other than themselves, and the kind
   of token each one produces."
  {\* :any, \? :one, \{ :open, \, :comma, \} :close})

(defn- add-literal
  "Add a :lit token for the characters of `s` from `start` to `end` to
   `tokens`, unless that range is empty."
  [tokens s start end]
  (if (< start end)
    (conj tokens {:type :lit :text (subs s start end) :pos start})
    tokens))

(defn tokenize
  "Split pattern text into a vector of tokens. Each token is a map with
   a :type, the :pos of its first character, and for literals the :text."
  [s]
  (let [n (count s)]
    (loop [i 0, start 0, tokens []]
      (if (= i n)
        (add-literal tokens s start n)
        (if-let [kind (special (nth s i))]
          (recur (inc i) (inc i)
                 (conj (add-literal tokens s start i) {:type kind :pos i}))
          (recur (inc i) start tokens))))))
```

```clojure
(tokenize "a*.txt")
;; => [{:type :lit, :text "a", :pos 0} {:type :any, :pos 1} {:type :lit, :text ".txt", :pos 2}]
(tokenize "{pdf,txt}")
;; => [{:type :open, :pos 0} {:type :lit, :text "pdf", :pos 1} {:type :comma, :pos 4}
;;     {:type :lit, :text "txt", :pos 5} {:type :close, :pos 8}]
(tokenize "")
;; => []
```

The loop walks the string one character at a time with three pieces of
state: the current index `i`, the index `start` where the current run of
ordinary characters began, and the tokens found so far. A special
character ends the current run, so we add the run as a literal (if it is
not empty), add a token for the special character, and start a new run
just after it. An ordinary character just moves `i` along. Reaching the
end of the string flushes whatever run is in progress.

Two small decisions are worth pointing out. First, the tokenizer does
not build literals character by character. It remembers where the run
started and cuts the string once with `subs` when the run ends, which is
both faster and simpler than appending to a growing string. Second, the
`special` map does two jobs at once: it tells us whether a character is
special, since looking up an ordinary character returns `nil`, and it
tells us what token to make. `if-let` binds the result and takes the
first branch only when it is truthy. A table like this is easier to
extend than a chain of `cond` clauses, and the parser will use it in
reverse to print tokens in error messages.

The tokenizer has no idea what the tokens mean. It does not know that a
`{` should be matched by a `}`, or that a `,` is only meaningful inside
braces, or that two stars in a row are redundant. It will cheerfully
produce `[:any :any :one]` for `**?` and `[:close]` for a lone `}`. That
is not an oversight; it is the point.

## Why two layers

We could write a single function that walks the characters and builds
the matcher's data directly, and for a language this small it would not
be much longer than the two layers together. We separate them anyway,
for reasons that have nothing to do with size.

The tokenizer deals with characters, and the problems that live at the
level of characters are their own kind of fiddly: where a run of literal
text begins and ends, which characters are special, what an escape
sequence like `\*` should mean, whether whitespace matters. The parser
deals with structure: which pieces belong inside which braces, what a
comma separates, when a group is finished. If one function did both, it
would have to carry the state of both at once, and the two kinds of
concern would be interleaved line by line. When something went wrong
with escaping, you would be reading about brace matching to find it.

Separating them also gives each layer a simple, testable interface. The
tokenizer is a function from a string to a vector of maps, and we can
check that it produces the right tokens, with the right positions,
without any parser at all. The parser is a function from a vector of
maps to the matcher's data, and we could test it by handing it tokens
directly, without any text. The first part of the test file does exactly
the former:

```clojure
(deftest tokenizing
  (testing "nothing in, nothing out"
    (is (= [] (p/tokenize ""))))
  (testing "a run of ordinary characters is one literal"
    (is (= [{:type :lit :text "abc" :pos 0}] (p/tokenize "abc"))))
  (testing "special characters are tokens of their own"
    (is (= [{:type :any :pos 0}] (p/tokenize "*")))
    (is (= [{:type :one :pos 0}] (p/tokenize "?"))))
  (testing "literals and specials interleave, with positions"
    (is (= [{:type :lit :text "a" :pos 0}
            {:type :any :pos 1}
            {:type :lit :text ".txt" :pos 2}]
           (p/tokenize "a*.txt"))))
  (testing "braces and commas"
    (is (= [{:type :open :pos 0}
            {:type :lit :text "a" :pos 1}
            {:type :comma :pos 2}
            {:type :lit :text "b" :pos 3}
            {:type :close :pos 4}]
           (p/tokenize "{a,b}"))))
  (testing "adjacent specials produce no empty literals"
    (is (= [:any :any :one] (map :type (p/tokenize "**?"))))))
```

Finally, the layers change for different reasons. Adding an escape
character touches only the tokenizer. Allowing `{a|b}` as well as
`{a,b}` touches only the tokenizer. Adding a new kind of group touches
only the parser. The boundary between the two is the token, and tokens
are plain maps, so neither layer has to know anything about the other's
internals.

## The grammar

Before writing the parser we write down what a well-formed pattern looks
like, in a notation called a **grammar**. Each line names a kind of
thing and says what it is made of; `*` after something means "zero or
more of these", and `|` separates alternatives:

```
pattern := item*
item    := LIT | ANY | ONE | either
either  := OPEN pattern (COMMA pattern)* CLOSE
```

In words: a pattern is any number of items; an item is a literal token,
a star token, a question-mark token, or a brace group; and a brace group
is an open brace, a pattern, any number of comma-and-pattern pairs, and a
close brace. The names in capitals are token types, which is where the
two layers meet. The grammar is **recursive**, since `either` contains
`pattern` and `pattern` contains `either`, and that is what lets a group
nest inside another group to any depth without the grammar having to say
anything about depth.

A **recursive-descent parser** is a parser with one function per
grammar rule, each of which recognizes one instance of its rule by
calling the functions for the rules it is made of. The structure of the
code mirrors the structure of the grammar, which is why this style is
the usual choice for a parser written by hand.

Every parse function here takes the same two arguments, the vector of
tokens and the index of the token to start at, and returns the same
shape, a two-element vector of the thing it parsed and the index of the
first token it did not consume. Keeping the position in a return value
rather than in a mutable counter means the parser is a set of pure
functions, and that a test can call any one of them with any starting
index. The function for `item` shows the convention:

```clojure
(defn- parse-item
  "Parse one item starting at index `i`. Returns the node and the next index."
  [tokens i]
  (let [token (nth tokens i)]
    (case (:type token)
      :lit [[:lit (:text token)] (inc i)]
      :any [[:any] (inc i)]
      :one [[:one] (inc i)]
      :open (parse-either tokens i))))
```

Three of the four cases turn a single token into a node and step past
it. The fourth hands the job to `parse-either`, which will consume as
many tokens as the group takes and report back where it stopped. The
nodes are exactly the vectors that `sdx.glob.data/match` understands;
the parser is the first "other program" that builds patterns, and it
needed to know nothing about matching to do it.

A pattern is a loop that collects items until it meets something that
cannot start one:

```clojure
(def ^:private stops
  "Token types that end a pattern without being part of it."
  #{:comma :close})

(defn- parse-pattern
  "Parse items starting at index `i` until a comma, a closing brace, or
   the end of the tokens. Returns the vector of nodes and the next index."
  [tokens i]
  (loop [i i, nodes []]
    (let [kind (:type (get tokens i))]
      (if (or (nil? kind) (stops kind))
        [nodes i]
        (let [[node i] (parse-item tokens i)]
          (recur i (conj nodes node)))))))
```

`get` on a vector returns `nil` for an index past the end, where `nth`
would throw, so `(get tokens i)` followed by `(:type ...)` gives `nil`
both for "no more tokens" and nothing else, and the `nil?` check handles
the end of the input. A comma or a closing brace also stops the loop, but
is left for the caller to deal with: `parse-pattern` does not know
whether it is parsing the whole input or one alternative inside a group,
and it does not need to.

The group rule is the one that calls back up into `parse-pattern`, which
is why `parse-pattern` has to be declared before `parse-either` is
defined even though it is defined afterwards. `declare` creates the name
with no value yet, so that the compiler accepts a call to it:

```clojure
(declare parse-pattern)

(defn- parse-either
  "Parse a {...} group whose :open token is at index `i`. Returns the
   :either node and the index just past the :close token."
  [tokens i]
  (let [open (nth tokens i)]
    (loop [i (inc i), alternatives []]
      (let [[nodes i] (parse-pattern tokens i)
            alternatives (conj alternatives nodes)]
        (case (:type (get tokens i))
          :comma (recur (inc i) alternatives)
          :close [(into [:either] alternatives) (inc i)]
          (fail "unclosed '{' opened" (:pos open)))))))
```

Starting just after the `{`, it parses one alternative, looks at the
token that stopped the alternative, and either steps over a comma and
parses the next alternative or steps over the closing brace and returns
the finished node. `(into [:either] alternatives)` builds
`[:either alt1 alt2 ...]`, the shape the matcher wants. Nesting costs
nothing extra. If an alternative contains another group,
`parse-pattern` calls `parse-item`, which calls `parse-either`, which
calls `parse-pattern`, and the inner group is finished and returned
before the outer one looks at its next token:

```clojure
(parse "{a,{b,c}}")
;; => [[:either [[:lit "a"]] [[:either [[:lit "b"]] [[:lit "c"]]]]]]
```

That is the recursion in "recursive descent" doing its job. The depth of
the call stack is the depth of the nesting, and the parser never has to
count braces, because every open brace is handled by a function call
that does not return until it has seen the matching close brace.

## Saying what went wrong

Parsers see bad input more often than most code does, because the input
is typed by people. A parser that reports "syntax error" and nothing
else is barely better than one that crashes. Ours reports what it
expected and where, and does it through `ex-info`:

```clojure
(defn- fail
  "Throw a parse error that says what went wrong and where."
  [message pos]
  (throw (ex-info (str message " at position " pos) {:pos pos})))

(defn- describe
  "The characters that a token stood for, for use in error messages."
  [token]
  (case (:type token)
    :lit (:text token)
    :any "*"
    :one "?"
    :open "{"
    :comma ","
    :close "}"))
```

`ex-info` creates an exception that carries a message for people and a
map of data for programs. The message is what a user sees; the map is
what a test, or an editor that wants to underline the offending
character, reads with `ex-data`. Keeping both means we do not have to
parse our own error messages to find out where the error was.

Two things can go wrong. A group can be opened and never closed, which
`parse-either` detects when it runs out of tokens without seeing a `}`.
Notice that it reports the position of the `{` that was opened rather
than the end of the input, because that is where the fix goes. The other
error is a comma or closing brace outside any group. `parse-pattern`
stops at those without complaint, which is correct inside a group and
wrong at the top level, so the top-level function checks that everything
was consumed:

```clojure
(defn parse
  "Turn glob text into matcher data for sdx.glob.data/match. Throws
   ex-info with a :pos if the text is not a well-formed pattern."
  [s]
  (let [tokens (tokenize s)
        [nodes i] (parse-pattern tokens 0)]
    (if (= i (count tokens))
      nodes
      (let [token (nth tokens i)]
        (fail (str "unexpected '" (describe token) "'") (:pos token))))))
```

The tests check both the message and the position by catching the
exception and pulling both out:

```clojure
(defn- error-of
  "The message and :pos of the parse error that `s` causes."
  [s]
  (try
    (p/parse s)
    nil
    (catch clojure.lang.ExceptionInfo e
      [(ex-message e) (:pos (ex-data e))])))

(deftest parsing-malformed-patterns
  (testing "an unclosed group reports where it was opened"
    (is (= ["unclosed '{' opened at position 3" 3] (error-of "abc{d,e")))
    (is (= ["unclosed '{' opened at position 1" 1] (error-of "a{{b}"))))
  (testing "a stray closing brace or comma is an error where it appears"
    (is (= ["unexpected '}' at position 1" 1] (error-of "a}b")))
    (is (= ["unexpected ',' at position 2" 2] (error-of "ab,c"))))
  (testing "well-formed patterns do not throw"
    (is (nil? (error-of "{a,b}c")))))
```

The second case is the kind that is easy to get wrong by hand. In
`a{{b}`, the inner group is closed and the outer one is not, so the
right position to report is 1, not 2. The first draft of that test
expected 2; the parser was right and the test was wrong, which is a
reminder that tests are code and can have bugs of their own.

> **Where positions come from.**
> The parser never looks at the original string. It can report
> character positions only because the tokenizer recorded `:pos` on
> every token it made. Information that an error message will need has
> to be carried along from the layer that had it, which is a good reason
> to decide what your error messages should say before deciding what
> your tokens should contain.

## End to end

With both layers in place, matching a name against a pattern typed by a
person is a composition of the two chapters:

```clojure
(defn matches?
  "True if `text` matches the glob `pattern`."
  [pattern text]
  (glob/match (parse pattern) text))
```

```clojure
(parse "2023-*.{pdf,txt}")
;; => [[:lit "2023-"] [:any] [:lit "."] [:either [[:lit "pdf"]] [[:lit "txt"]]]]
(matches? "2023-*.{pdf,txt}" "2023-notes.txt")   ;; => true
(matches? "2023-*.{pdf,txt}" "2023-notes.doc")   ;; => false
```

The namespace also has a `-main`, so that the matcher can be used from a
shell to filter a list of names. It parses the pattern once and matches
each name against the result:

```clojure
(defn -main
  "Print each NAME that matches PATTERN, one per line."
  [& [pattern & names]]
  (if pattern
    (let [nodes (parse pattern)]
      (doseq [name names
              :when (glob/match nodes name)]
        (println name)))
    (println "usage: clojure -M -m sdx.parser PATTERN NAME...")))
```

```
$ clojure -M -m sdx.parser '2023-*.{pdf,txt}' 2023-report.pdf 2022-report.pdf 2023-notes.txt 2023-notes.doc
2023-report.pdf
2023-notes.txt
```

The quotes around the pattern matter: without them the shell would
expand the glob itself, using its own matcher, before our program ever
saw it.

The end-to-end tests in `test/sdx/parser_test.clj` are a table of
pattern, name, and expected answer, like the tables in the previous
chapter but with patterns written as text, which is the most readable
form of all:

```clojure
(def cases
  "Pattern text, a name, and whether the name should match."
  [["abc" "abc" true]
   ["abc" "abd" false]
   ["a*.txt" "abc.txt" true]
   ["a*.txt" "abc.md" false]
   ["*.txt" ".txt" true]
   ["a?c" "abc" true]
   ["a?c" "ac" false]
   ["2023-*.{pdf,txt}" "2023-report.pdf" true]
   ["2023-*.{pdf,txt}" "2023-notes.txt" true]
   ["2023-*.{pdf,txt}" "2022-notes.txt" false]
   ["2023-*.{pdf,txt}" "2023-notes.doc" false]
   ["{*.c,Makefile}" "main.c" true]
   ["{*.c,Makefile}" "Makefile" true]
   ["{*.c,Makefile}" "main.h" false]
   ["a{,-draft}.md" "a.md" true]
   ["a{,-draft}.md" "a-draft.md" true]
   ["{a,{b,c}}x" "cx" true]
   ["" "" true]
   ["" "x" false]])

(deftest matching-parsed-patterns
  (doseq [[pattern text expected] cases]
    (testing (str pattern " against " text)
      (is (= expected (glob/match (p/parse pattern) text)))
      (is (= expected (p/matches? pattern text))))))
```

One more test costs three lines and catches a surprising number of
mistakes. The previous chapter wrote `pattern->string`, which turns
matcher data back into glob text. If parsing and printing are inverses,
then printing what we parsed should give back what we started with:

```clojure
(deftest round-trip
  (doseq [[pattern] cases]
    (is (= pattern (glob/pattern->string (p/parse pattern))))))
```

A **round-trip test** like this checks the parser against an independent
piece of code rather than against hand-written expected output, so it
cannot share a misunderstanding with the person who wrote the expected
values. It also reuses the table of cases, which is a second reason to
keep test cases as data: the same table can drive several different
checks.

## Summary

- A tokenizer turns characters into tokens, which are the smallest
  units that mean something; a parser turns tokens into structure. Each
  layer has one job, one kind of input, and its own tests.
- A grammar is a short written description of what well-formed input
  looks like. Writing it down first makes the parser almost mechanical
  to write.
- A recursive-descent parser has one function per grammar rule. Rules
  that refer to each other become functions that call each other, and
  nesting in the input becomes recursion in the parser.
- Returning `[result next-index]` from every parse function keeps the
  parser pure and lets each function be tested on its own.
- Errors should say what was expected and where. `ex-info` carries a
  message for people and a data map for programs, and positions must be
  recorded by the layer that has them.
- When a parser has a printer to go with it, a round-trip test checks
  one against the other for every case in the table.

## Exercises

1. **Escaping.** Make a backslash cause the next character to be treated
   as ordinary, so that `\*` matches a literal star. Which layer changes?
   Update `pattern->string` so that the round-trip test still passes.

2. **Lenient commas.** Outside of braces, a comma or closing brace could
   reasonably be taken as ordinary text rather than an error. Change the
   parser to do so without changing the tokenizer. What has to be passed
   down to `parse-pattern` for it to know whether it is inside braces?

3. **Merging stars.** `**` behaves the same as `*` but does more work in
   the matcher. Have the parser emit a single `[:any]` for a run of
   stars, and decide whether that belongs in `parse-item`,
   `parse-pattern`, or the tokenizer.

4. **Error recovery.** Instead of throwing on the first error, make
   `parse` collect every error it can find and return them all with
   their positions. What does the parser have to do after an error to
   carry on sensibly?

5. **A pretty-printer for tokens.** Write a function that takes the
   output of `tokenize` and produces a string with a caret under the
   position of a given token, like a compiler's error display, and use it
   to improve the messages from `fail`.

6. **Parsing tokens directly.** Write a test that calls the private
   `parse-either` function (through `#'sdx.parser/parse-either`) with a
   hand-built vector of tokens, to show that the parser can be tested
   without the tokenizer.

7. **Character classes.** Add `[abc]` to the language: the tokenizer
   needs two more special characters and the grammar needs a new rule.
   Produce the `[:class "abc"]` node from the previous chapter's first
   exercise.

8. **Counting depth.** Add a `max-depth` argument to `parse` and throw
   an error with the position of the brace that goes too deep. Why might
   a real tool want such a limit?

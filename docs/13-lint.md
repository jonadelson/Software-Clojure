---
title: "A Code Linter"
nav_order: 13
lede: "A tool that reads Clojure source as data and runs a list of small checks over it."
---

A **linter** reads source code and points out things that are probably
mistakes, or at least probably unwise: a function with eleven
parameters, a variable that is bound and never used, a name defined
twice in the same file. A compiler will accept all of these; a linter
complains about them, because experience says they are where bugs hide.
Every serious language has one, and in most languages a linter is a
substantial program, because before it can check anything it has to
parse the source into a tree, and parsers are hard.

This chapter builds a linter for Clojure in about two hundred lines, and
the reason it is so short is the most important idea in the chapter.
Clojure is **homoiconic**: its source code is written in the notation of
its own data structures, and the function that turns text into those
structures, the reader, is available to any program. A Clojure file is
a sequence of lists, vectors, maps, symbols, and literals, and we can
read it with `read`, walk it with the same functions we use for any
other nested data, and never write a parser at all.

The design has one other idea worth taking away. Each check is an
ordinary function from the forms in a file to a sequence of findings, and
the linter is nothing more than a list of such functions. Adding a check
means writing a function; configuring a check means closing over a
number; running a different set of checks means passing a different list.

## Reading code as data

Here is the whole front end of our linter:

```clojure
(defn read-forms
  "Read every top-level form in `text` into a vector. Lists carry
   :line and :column metadata saying where they began."
  [text]
  (let [reader (LineNumberingPushbackReader. (StringReader. text))
        eof (Object.)]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof eof :read-cond :allow} reader)]
          (if (identical? form eof)
            forms
            (recur (conj forms form))))))))
```

`read` is the function the compiler itself uses to turn text into forms.
It reads one form at a time, so we call it in a loop until it reports the
end of the input. By default it throws at end of input; passing an `:eof`
value in the options map tells it to return that value instead. We make
the sentinel a fresh `Object` and compare with `identical?`, so that no
value that could legitimately appear in a source file, not even `nil` or
`:eof`, could be mistaken for the end.

The reader we hand it is a `LineNumberingPushbackReader`, which is what
makes this a linter rather than a pretty printer. As it reads, this
reader counts lines, and `read` attaches the current line and column to
every list it produces, as **metadata**. Metadata is a map that rides
along with a Clojure value without being part of it: two lists with
different metadata are still `=`, and `meta` reads the map back.

```clojure
(def forms (read-forms "(defn f [x]\n  x)\n\n(def y\n  2)"))
forms
;; => [(defn f [x] x) (def y 2)]
(meta (first forms))
;; => {:line 1, :column 1}
(meta (second forms))
;; => {:line 4, :column 1}
```

The forms look exactly like the source, minus the whitespace, and they
know where they came from. A small accessor hides the detail:

```clojure
(defn line-of
  "The line on which `form` starts, or nil if the reader did not record one."
  [form]
  (:line (meta form)))
```

The qualifier "if the reader did not record one" matters. Only lists get
position metadata; vectors, maps, symbols, and literals do not, so
`(meta (first (read-forms "[1 2]")))` is `nil`. Every check below reports
its finding at the line of the nearest enclosing list, which in practice
is always close enough.

Two more details of `read-forms` are about safety. The reader has a
feature, `#=`, that evaluates an expression *while reading*. That is
occasionally useful at a REPL and never acceptable in a tool that reads
files it did not write, so we bind `*read-eval*` to `false` for the
duration, and the reader refuses:

```clojure
(read-forms "#=(System/exit 1)")
;; => RuntimeException: EvalReader not allowed when *read-eval* is false.
```

The `:read-cond :allow` option tells the reader to accept reader
conditionals (`#?(:clj ... :cljs ...)`) and keep the Clojure branch.
Without it, a file that uses them cannot be read at all.

The tests for this chapter are in `test/sdx/lint_test.clj`. The first
two pin down the reader's behavior before anything is built on it:

```clojure
(deftest reading-keeps-line-numbers
  (let [forms (lint/read-forms "(defn f [x]\n  x)\n\n(def y\n  2)")]
    (is (= 2 (count forms)))
    (is (= '(defn f [x] x) (first forms)))
    (is (= 1 (lint/line-of (first forms))))
    (is (= 4 (lint/line-of (second forms))))))

(deftest reading-does-not-evaluate
  (is (= '[(System/exit 1)] (lint/read-forms "(System/exit 1)")))
  (is (thrown? Exception (lint/read-forms "#=(System/exit 1)"))))
```

The first assertion in the second test is worth a pause. Reading
`(System/exit 1)` produces a list containing a symbol and a number. It
does not exit, because reading is not evaluating. That distinction is
what makes it safe to point this tool at any file.

## Walking a form

Every check needs to look inside forms, and most need to look all the
way down. Since a form is just nested collections, `tree-seq` does the
job:

```clojure
(defn subforms
  "`form` together with every form nested anywhere inside it, outermost first."
  [form]
  (tree-seq coll? seq form))
```

`tree-seq` takes a predicate that says which nodes have children, a
function that returns them, and a root, and returns a lazy depth-first
sequence of every node. Here every collection is a branch and its
elements are its children:

```clojure
(subforms '(a [b {:c d}]))
;; => ((a [b {:c d}]) a [b {:c d}] b {:c d} [:c d] :c d)
```

Notice that the map `{:c d}` yields its entry `[:c d]` before the key and
value. That is right for a generic walk, but when counting nesting depth
we would rather not count the entry as a level, so one helper gets the
children of a collection with maps flattened:

```clojure
(defn- children
  "The forms directly inside a collection. A map's children are its
   keys and values, not its entries."
  [form]
  (if (map? form)
    (mapcat identity form)
    (seq form)))
```

Most checks are looking for calls to particular special forms or macros,
which means lists whose first element is one of a few symbols:

```clojure
(defn calls?
  "True if `form` is a list whose first element is one of the symbols in `heads`."
  [heads form]
  (and (seq? form) (contains? heads (first form))))

(defn find-calls
  "Every list anywhere inside `forms` whose head is in `heads`."
  [heads forms]
  (filter #(calls? heads %) (mapcat subforms forms)))
```

```clojure
(find-calls #{'let} (read-forms "(defn f [x] (let [a 1] (when x (let [b 2] b))))"))
;; => ((let [a 1] (when x (let [b 2] b))) (let [b 2] b))
```

> **`tree-seq` versus `clojure.walk`.**
> Clojure has a second tool for nested data, the `clojure.walk`
> namespace, whose `prewalk` and `postwalk` apply a function to every
> node and *rebuild* the structure from the results. `tree-seq` is for
> looking; `walk` is for transforming. Most of this chapter only looks,
> so it uses `tree-seq`, but one check needs to transform a form before
> looking at it and uses `prewalk` for that.

## The first check: too many parameters

A function with many parameters is hard to call correctly, and usually a
sign that some of them belong together in a map. To count parameters we
have to find the parameter vectors of a `defn`, which is slightly fiddly
because `defn` has optional parts: a docstring, an attribute map, and
either one parameter vector or several arities each wrapped in a list.

```clojure
(def defn-heads '#{defn defn-})

(defn arities
  "The parameter vectors of a defn form, one per arity. Skips the name,
   the docstring and the attribute map if they are present."
  [form]
  (let [body (drop-while #(or (symbol? %) (string? %) (map? %)) (rest form))
        head (first body)]
    (cond
      (vector? head) [head]
      (seq? head)    (map first (filter seq? body))
      :else          [])))

(defn param-count
  "How many parameters a parameter vector binds, not counting the & marker."
  [params]
  (count (remove #{'&} params)))
```

```clojure
(arities '(defn f [a b] a))
;; => [[a b]]
(arities '(defn f "doc" {:private true} ([] 0) ([x] x) ([x y] y)))
;; => ([] [x] [x y])
```

`drop-while` skips past the name, the docstring, and the attribute map
in whatever combination they appear, because each is a different type
and none of them is a vector or a list. What comes next is either the
single parameter vector or the first of the arity lists.

With that, the check is a `for` comprehension:

```clojure
(defn too-many-params
  "Findings for every top-level defn with more than `limit` parameters
   in any of its arities."
  [limit forms]
  (for [form (filter #(calls? defn-heads %) forms)
        params (arities form)
        :let [n (param-count params)]
        :when (> n limit)]
    {:line (line-of form)
     :message (format "%s takes %d parameters (limit %d)" (second form) n limit)}))
```

```clojure
(too-many-params 4 (read-forms "(defn ok [a b] a)\n(defn wide [a b c d e] a)"))
;; => ({:line 2, :message "wide takes 5 parameters (limit 4)"})
```

This sets the pattern for every check: take the forms, return a seq of
maps with a `:line` and a `:message`, and nothing else. A check does not
print, does not sort, and does not know what other checks exist. The
`limit` comes first in the argument list so that `(partial
too-many-params 4)` is a check with the limit baked in, which is how the
linter will configure it.

The test tries two limits to make sure the number really is a parameter
and that every arity is examined:

```clojure
(deftest too-many-params
  (let [text "(defn ok [a b] a)\n(defn wide [a b c d e] a)\n(defn multi ([a] a) ([a b c] c))"]
    (is (= [{:line 2 :message "wide takes 5 parameters (limit 4)"}]
           (lint/too-many-params 4 (lint/read-forms text))))
    (testing "the limit is a parameter, and every arity is checked"
      (is (= [{:line 2 :message "wide takes 5 parameters (limit 2)"}
              {:line 3 :message "multi takes 3 parameters (limit 2)"}]
             (lint/too-many-params 2 (lint/read-forms text)))))))
```

## Nesting depth

Deeply nested code is hard to read because the reader has to hold every
enclosing condition in mind at once. This check walks down through a
form counting levels and stops at the first collection that lies past
the limit:

```clojure
(defn- too-deep-in
  "Findings for collections inside `form` nested more than `limit`
   levels deep. `level` is the nesting level of `form` itself and
   `line` is the nearest known line number above it."
  [limit level line form]
  (when (coll? form)
    (let [line (or (line-of form) line)]
      (if (> level limit)
        [{:line line
          :message (format "nesting is %d levels deep (limit %d)" level limit)}]
        (mapcat #(too-deep-in limit (inc level) line %) (children form))))))

(defn too-deep
  "One finding per top-level form that nests more than `limit` levels
   deep, placed at the first sub-form that crosses the limit. A
   top-level form is level 1."
  [limit forms]
  (for [form forms
        :let [found (first (too-deep-in limit 1 nil form))]
        :when found]
    found))
```

Two decisions are embedded here. The first is how to get a line number
for a vector or a map, which carry none: `too-deep-in` passes down the
line of the nearest enclosing list, and each level replaces it with its
own line if it has one. The second is how much to report. A function
that nests too deeply usually does so in several branches, and reporting
every branch would bury the one message that matters under copies of
itself, so `too-deep` keeps only the first finding per top-level form.
The user fixes that function, reruns the linter, and sees whether
anything remains.

```clojure
(too-deep 4 (read-forms "(defn f [x]\n  (when x\n    (let [y 1]\n      (if y\n        (do (println y))))))"))
;; => ({:line 5, :message "nesting is 5 levels deep (limit 4)"})
```

What counts as a level is a matter of taste. We count every collection,
so `[x]` and `(when ...)` each add one, and the default limit of six
reflects that. A linter that counted only lists, or only certain forms,
would have a different natural limit; the point is that the threshold is
a number someone chose, and the code should make that choice easy to
change.

## Missing docstrings

The simplest check in the file. A `defn`, `defn-`, or `defmacro` whose
third element is not a string has no docstring:

```clojure
(def documented-heads '#{defn defn- defmacro})

(defn missing-docstring
  "Findings for every top-level defn or defmacro whose third element
   is not a string."
  [forms]
  (for [form (filter #(calls? documented-heads %) forms)
        :when (not (string? (nth form 2 nil)))]
    {:line (line-of form)
     :message (format "%s has no docstring" (second form))}))
```

`(nth form 2 nil)` uses the three-argument form of `nth` so that a
`defn` with nothing after its name, which is not valid but is something
a linter should survive, gives `nil` rather than an exception. A linter
runs on code that may be half-written, and must never fall over because
of it.

## Unused bindings

This is the check that earns its keep. A `let` binding that is never
mentioned again is dead code at best and a typo at worst, and the
compiler says nothing about it. Finding it needs three pieces: the names
a binding introduces, the names that are used afterwards, and a
comparison.

A binding target can be a symbol or a destructuring form, and a
destructuring form can be arbitrarily nested, so the names it introduces
are simply every symbol inside it, apart from the `&` marker:

```clojure
(defn binding-names
  "Every symbol a binding target introduces, including those inside
   destructuring vectors and maps."
  [target]
  (->> (subforms target)
       (filter symbol?)
       (remove #{'&})))
```

```clojure
(binding-names '{:keys [x y] :as whole})
;; => (x y whole)
(binding-names '[a [b] & rest])
;; => (a b rest)
```

Finding the symbols that are *used* is almost as easy: every symbol in
the body. Almost, because a quoted symbol is data, not a reference. In
`(let [a 1] 'a)`, the `a` in the body is the symbol `a` as a value and
does not use the binding. So before collecting symbols we blank out every
quoted form, and this is where `clojure.walk` comes in:

```clojure
(defn- without-quoted
  "`form` with every (quote ...) replaced by nil, so that a quoted
   symbol is not mistaken for a use of a local."
  [form]
  (walk/prewalk (fn [x] (if (calls? #{'quote} x) nil x)) form))

(defn used-symbols
  "The set of symbols that appear anywhere in `forms`, ignoring quoted data."
  [forms]
  (->> forms
       (map without-quoted)
       (mapcat subforms)
       (filter symbol?)
       set))
```

`prewalk` visits each node *before* its children and replaces it with
whatever the function returns. When the function turns a `(quote ...)`
list into `nil`, there are no children left to visit, which is exactly
what we want. (The reader turns `'a` into `(quote a)`, so this one case
covers both spellings.)

```clojure
(used-symbols '[(inc a) 'b])
;; => #{a inc}
```

The check itself walks every `let` in the file. For each binding it
computes the symbols used in the *later* bindings and the body, because
`(let [a 1 b (inc a)] b)` uses `a` in a binding rather than the body,
and reports any name that does not appear. Names beginning with an
underscore are skipped, since that is the convention for a value one is
deliberately ignoring:

```clojure
(defn- placeholder?
  "True for names like _ or _unused that signal an intentionally ignored value."
  [sym]
  (str/starts-with? (name sym) "_"))

(defn unused-bindings
  "Findings for every let binding, anywhere in `forms`, whose name is
   not mentioned in a later binding or in the body."
  [forms]
  (for [form (find-calls #{'let} forms)
        :let [[_ bindings & body] form]
        :when (vector? bindings)
        :let [pairs (partition 2 bindings)]
        [i [target _]] (map-indexed vector pairs)
        :let [later (used-symbols (concat (map second (drop (inc i) pairs)) body))]
        name (binding-names target)
        :when (and (not (placeholder? name))
                   (not (contains? later name)))]
    {:line (line-of form)
     :message (format "%s is bound but never used" name)}))
```

The `for` here is doing a lot, and it reads top to bottom as a
description of the search: for each `let`, for each binding in it, for
each name that binding introduces, if the name is not used later, report
it. The `:when (vector? bindings)` guard again protects the linter from
malformed code.

```clojure
(unused-bindings (read-forms "(defn f []\n  (let [a 1 b 2] a))"))
;; => ({:line 2, :message "b is bound but never used"})
(unused-bindings (read-forms "(let [{:keys [x y]} m] x)"))
;; => ({:line 1, :message "y is bound but never used"})
```

The test for this check is a list of the cases that were considered
while writing it, which is the best kind of test to leave behind for
whoever touches the code next:

```clojure
(deftest unused-bindings
  (testing "a binding used in the body or a later binding is fine"
    (is (= [] (lint/unused-bindings (lint/read-forms "(let [a 1 b (inc a)] b)")))))
  (testing "a binding nobody mentions is reported with the let's line"
    (is (= [{:line 2 :message "b is bound but never used"}]
           (lint/unused-bindings (lint/read-forms "(defn f []\n  (let [a 1 b 2] a))")))))
  (testing "destructured names are checked one by one"
    (is (= [{:line 1 :message "y is bound but never used"}]
           (lint/unused-bindings (lint/read-forms "(let [{:keys [x y]} m] x)")))))
  (testing "underscore names are deliberately ignored"
    (is (= [] (lint/unused-bindings (lint/read-forms "(let [_ 1 _tmp 2] 3)")))))
  (testing "a quoted symbol is data, not a use"
    (is (= [{:line 1 :message "a is bound but never used"}]
           (lint/unused-bindings (lint/read-forms "(let [a 1] 'a)")))))
  (testing "lets nested inside other forms are found"
    (is (= [{:line 1 :message "z is bound but never used"}]
           (lint/unused-bindings (lint/read-forms "(defn f [x] (when x (let [z x] 1)))"))))))
```

This check is a **heuristic**, and it is worth being honest about where
it is wrong. If an inner `let` shadows an outer binding with the same
name, a use of the inner one counts as a use of the outer. A symbol that
appears inside a macro call may or may not be a reference, depending on
what the macro does with it. And bindings introduced by `loop`,
`if-let`, `for`, and friends are not checked at all. A linter does not
have to be perfect to be useful; it has to be right often enough that
people keep reading its output. Every one of these gaps is a place where
a few more lines would make it right more often.

## Duplicate names

Defining the same name twice in one file is nearly always an accident,
and a nasty one, because the second definition silently replaces the
first. The check collects every top-level definition and groups them by
name:

```clojure
(defn- defined-name
  "The name a top-level form defines, if it is a def of some kind.
   defmethod is excluded because it adds to a name rather than defining one."
  [form]
  (when (and (seq? form)
             (symbol? (first form))
             (str/starts-with? (name (first form)) "def")
             (not= 'defmethod (first form))
             (symbol? (second form)))
    (second form)))

(defn duplicate-names
  "Findings for every top-level name that is defined more than once,
   reported at each definition after the first."
  [forms]
  (let [defs (keep (fn [form]
                     (when-let [n (defined-name form)]
                       {:name n :line (line-of form)}))
                   forms)]
    (for [[name occurrences] (group-by :name defs)
          :when (> (count occurrences) 1)
          later (rest occurrences)]
      {:line (:line later)
       :message (format "%s is already defined on line %s" name (:line (first occurrences)))})))
```

Rather than list every defining form (`def`, `defn`, `defmacro`,
`defmulti`, `defrecord`, `deftest`, and whatever else a project has
invented), `defined-name` treats any list whose head starts with `def`
and whose second element is a symbol as a definition. The one exception
it has to make is `defmethod`, which *looks* like a definition of its
second element but actually adds a case to an existing multimethod, and
a file with several `defmethod`s for the same name is the normal state
of affairs. The test includes two of them to make sure they are left
alone:

```clojure
(deftest duplicate-names
  (let [text "(def a 1)\n(defn b [] 2)\n(def a 3)\n(defmethod area :square [s] s)\n(defmethod area :circle [c] c)\n(defn a [] 4)"]
    (is (= [{:line 3 :message "a is already defined on line 1"}
            {:line 6 :message "a is already defined on line 1"}]
           (lint/duplicate-names (lint/read-forms text))))))
```

`group-by` keeps each group's members in the order they were encountered,
so `(first occurrences)` is the earliest definition and the message can
point back at it.

## The linter is a list

With five checks written, the linter itself is almost nothing:

```clojure
(def default-checks
  "The checks run when the caller does not supply any."
  [(partial too-many-params 4)
   (partial too-deep 6)
   missing-docstring
   unused-bindings
   duplicate-names])

(defn lint
  "Read `text` and run every check over it. Returns findings sorted by line."
  ([text]
   (lint text default-checks))
  ([text checks]
   (let [forms (read-forms text)]
     (->> checks
          (mapcat #(% forms))
          (sort-by (juxt :line :message))))))
```

`lint` reads the text once, hands the forms to every check, concatenates
what comes back, and sorts it. `(juxt :line :message)` builds a sort key
of `[line message]` so that findings on the same line come out in a
stable order. The two configurable checks appear in `default-checks` as
partial applications; the three that take no configuration appear as
themselves. To the linter they are indistinguishable, which is the
point: a check is anything that can be called with the forms.

```clojure
(lint "(defn f [a b c d e]\n  (let [unused 1] a))")
;; => ({:line 1, :message "f has no docstring"}
;;     {:line 1, :message "f takes 5 parameters (limit 4)"}
;;     {:line 2, :message "unused is bound but never used"})
(lint "(defn f [a b c d e]\n  (let [unused 1] a))" [missing-docstring])
;; => ({:line 1, :message "f has no docstring"})
```

Because a check is just a function, anyone can write one without
touching this file. The test suite proves it with a check that was not
in the plan, written inline:

```clojure
(deftest a-check-can-be-written-by-anyone
  (let [no-tabs (fn [forms]
                  (for [form forms
                        :when (some #(and (string? %) (.contains ^String % "\t")) (lint/subforms form))]
                    {:line (lint/line-of form) :message "string contains a tab"}))]
    (is (= [{:line 1 :message "string contains a tab"}]
           (lint/lint "(def s \"a\tb\")" [no-tabs])))))
```

Compare this with a design in which the linter is a single function
containing a `cond` with a clause per check. That version would need to
be edited for every new check, could not be configured without more
parameters, and could not run a subset. Representing the set of checks as
a value we pass around gives all three for free.

The command-line layer formats findings the way compilers do, so that
editors which already understand `file:line: message` can jump straight
to them:

```clojure
(defn lint-file
  "Lint the file at `path`."
  [path]
  (lint (slurp path)))

(defn format-finding
  "Render a finding the way compilers do: path:line: message."
  [path {:keys [line message]}]
  (str path ":" line ": " message))

(defn -main
  "Lint each file named on the command line and print the findings.
   Exits with status 1 if there were any."
  [& paths]
  (let [lines (for [path paths
                    finding (lint-file path)]
                (format-finding path finding))]
    (doseq [line lines]
      (println line))
    (println (count lines) "finding(s)")
    (System/exit (if (seq lines) 1 0))))
```

A small file with one of everything lives in `resources/lint/messy.clj`,
and the last test in the suite lints it end to end. Run from the command
line:

```
$ clojure -M -m sdx.lint resources/lint/messy.clj
resources/lint/messy.clj:8: report has no docstring
resources/lint/messy.clj:8: report takes 6 parameters (limit 4)
resources/lint/messy.clj:9: unused is bound but never used
resources/lint/messy.clj:13: area is already defined on line 3
resources/lint/messy.clj:23: nesting is 7 levels deep (limit 6)
5 finding(s)
```

The non-zero exit status is for build scripts, which treat any findings
as a failed step.

## Why this was easy

Step back and compare what we wrote with what a linter for a language
with conventional syntax would need. There, the first job is parsing:
either writing a parser, which for a real language is thousands of
lines, or depending on a library that exposes the compiler's. The
result is an abstract syntax tree with dozens of node types, one for
each kind of statement and expression, and every check has to know which
node types it cares about and how each one stores its children. A walk
over the tree needs a visitor with a method per node type, or a
reflection-based generic walker. Line numbers are fields on nodes.
Adding a check means learning the tree's API.

Here, parsing was one call to `read`. The "tree" has four node types,
list, vector, map, and atom, and `tree-seq` walks all of them with one
line of code. Line numbers are metadata, attached by the reader, read by
`meta`. Each check is a `for` comprehension over plain data, using the
same `filter`, `partition`, `group-by`, and destructuring we would use on
any other data. None of this is because the linter is doing less; it is
because the language's syntax *is* its data structures, so every tool
for one is a tool for the other. The interpreter in [Chapter
7](../07-interp/) benefited from the same fact in the same way.

Homoiconicity has a cost too, and a linter is a good place to see it.
The reader throws away comments and whitespace, so a check that cares
about formatting cannot be written on top of `read`. Reader macros are
expanded before we see them: `'a` becomes `(quote a)` and `#(inc %)`
becomes an `fn*` with a generated parameter name, so a message that
quoted the user's original text would have to be reconstructed. And
ordinary macros are *not* expanded, which cuts both ways. We can
recognize `let` and `defn` by name because they arrive as written, but
a macro from some library that expands into a `let` is invisible to our
unused-binding check. A linter that wanted to see through macros would
call `macroexpand` on each form first, at the price of seeing generated
code instead of the user's. Every linter chooses where on that line to
stand.

## Summary

- Clojure source is written in the notation of its own data structures,
  and `read` turns text into those structures. A tool that needs to
  look at code gets its parser for free.
- A `LineNumberingPushbackReader` makes the reader attach `:line` and
  `:column` metadata to every list it reads. Vectors, maps, and symbols
  carry none, so report at the nearest enclosing list.
- Reading is not evaluating. Bind `*read-eval*` to `false` when reading
  files you did not write.
- `tree-seq` enumerates every node of nested data; `clojure.walk`
  rebuilds nested data with every node transformed. Look with the first,
  transform with the second.
- A check is a function from forms to a seq of `{:line :message}`
  findings. It does not print or sort, and it does not know about other
  checks. Configuration is a closed-over argument via `partial`.
- The linter is a vector of checks. Adding, removing, and configuring
  checks are all operations on that vector, not edits to the linter.
- Linters are heuristics. Be explicit about the cases a check gets
  wrong, write tests for the cases it gets right, and make the thresholds
  easy to change.

## Exercises

1. **More binding forms.** Extend `unused-bindings` to cover `loop`,
   `if-let`, `when-let`, and `for`. Which of them need special handling,
   and why does `loop` in particular make the "used later" rule
   insufficient?

2. **Shadowing.** Write a test in which an inner `let` rebinds a name
   from an outer `let`, and the outer binding is genuinely unused. Then
   fix the check so that the test passes. You will need to track scope
   as you walk.

3. **Unused parameters.** Write a check that reports `defn` parameters
   that are never mentioned in the function body. Should the same
   underscore convention apply?

4. **Long functions.** Write a check for functions whose source spans
   more than *n* lines. The reader only records where a form *starts*;
   where can you get the end line from?

5. **Names and severity.** Change the shape of a finding to include
   `:check` (a keyword naming the check) and `:severity` (`:warning` or
   `:error`), and make `-main` exit non-zero only for errors. What has to
   change in `default-checks` for partially applied checks to know their
   own names?

6. **Seeing through macros.** Add an option to `lint` that runs
   `clojure.walk/macroexpand-all` on each form before checking it.
   Which checks produce better findings on expanded code, and which
   produce worse ones?

7. **Column numbers.** The reader records `:column` as well as `:line`.
   Include it in findings and in `format-finding`, and check whether your
   editor can use it.

8. **Auto-fix.** For the missing-docstring check, write a function that
   inserts a placeholder docstring into the *source text* at the right
   place. Why is this much harder than finding the problem was?

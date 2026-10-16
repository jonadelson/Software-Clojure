---
title: "A Template Expander"
nav_order: 12
lede: "A tiny language hidden in HTML attributes, and the interpreter that runs it."
---

Most web pages are made from a template: a page that is mostly fixed,
with a few holes where a name, a total, or a list of items is filled in
from data. Every web framework has a template language of its own, and
they all look different on the surface, but under the surface they are
all the same thing: a small programming language with variables,
conditionals, and loops, embedded in a document.

In this chapter we build one. Our templates are ordinary HTML, parsed
with the [parser from the last chapter](../11-html/), in which some
elements carry attributes whose names start with `z-`. A `z-var` fills an
element with the value of a variable, a `z-if` keeps or drops an element,
and a `z-loop` repeats one. The expander walks the tree, acts on those
attributes, and produces HTML text with the attributes gone.

The design idea that runs through the chapter is that this is an
**interpreter**, exactly like the one in the [interpreter chapter](../07-interp/).
The tree is the program, the `z-` attributes are its instructions, and
the map of variables is its environment. Seeing a template engine as an
interpreter tells us what parts it needs, environments with scope,
dispatch on the instruction, and an evaluation function over the tree,
and tells us how each should behave, because interpreters are a
well-understood kind of program.

## A tiny language inside HTML

Here is a template. It is valid HTML that happens to carry some extra
attributes:

```html
<html lang="en">
<head><title z-var="title">...</title></head>
<body>
<h1 z-var="title">...</h1>
<p z-if="paid">Thank you for your payment of $<span z-num="total">0</span>.</p>
<p z-if="overdue">This invoice is overdue.</p>
<ul>
  <li z-loop="line:lines"><span z-var="line">...</span></li>
</ul>
</body>
</html>
```

And here is the data it will be filled from, as a map of keywords to
values:

```clojure
{:title "Invoice 42"
 :paid true
 :overdue false
 :total 1234.5
 :lines ["Widgets <small>" "Shipping & handling"]}
```

Expanding the first with the second produces this:

```html
<html lang="en">
<head><title>Invoice 42</title></head>
<body>
<h1>Invoice 42</h1>
<p>Thank you for your payment of $<span>1,234.50</span>.</p>

<ul>
  <li><span>Widgets &lt;small&gt;</span></li><li><span>Shipping &amp; handling</span></li>
</ul>
</body>
</html>
```

Four things happened. `z-var` replaced the content of an element with a
variable's value, with `<` and `&` turned into entities so that data
cannot smuggle in markup. `z-num` did the same but formatted the value
as a number people would want to read. `z-if` dropped the overdue
paragraph because `overdue` was false, leaving only the newline that
followed it. And `z-loop` repeated the `li` once per line, binding
`line` to each value in turn for the elements inside it.

The directives are:

| Attribute | Meaning |
|-----------|---------|
| `z-if="name"` | Keep the element only if the variable is truthy. |
| `z-loop="item:items"` | Repeat the element once per value in `items`, with `item` bound to each. |
| `z-var="name"` | Replace the element's content with the variable's value as text. |
| `z-num="name"` | The same, formatted as a number. |

Why put the language in attributes rather than inventing a syntax like
`{% raw %}{{ title }}{% endraw %}`? Because a template that is valid HTML can be opened in a
browser, checked by the [validator](../11-html/), and parsed by the
parser we already have. The designer of the page and the author of the
expander never have to agree on anything except a prefix.

## Environments

The expander needs to look up variables. The simplest environment is a
single map, but a loop introduces a new variable that must be visible
inside the loop and invisible outside it, and two nested loops may use
the same name. The [closures chapter](../08-closures/) solved exactly
this problem with a **stack of frames**: a vector of maps, where lookup
searches from the last map pushed (the innermost scope) back to the
first.

```clojure
(defn lookup
  "Find `name` in `env`, a vector of maps, searching from the last map
   (the innermost scope) back to the first. Throws if it is not bound
   anywhere; a variable bound to nil or false is still bound."
  [env name]
  (if-let [entry (some #(find % name) (rseq env))]
    (val entry)
    (throw (ex-info (str "unknown variable " name) {:name name}))))
```

`rseq` gives the frames in reverse order without copying the vector, and
`some` returns the first truthy result of `find`. We use `find` rather
than `get` for a reason that is easy to miss: `get` returns `nil` both
when a key is absent and when it is present with the value `nil`, so a
variable deliberately set to `nil` or `false` would be reported as
unknown and the search would wrongly continue to an outer frame. `find`
returns the map entry itself, which is truthy even when its value is
not, so a variable bound to `false` in an inner scope correctly hides one
bound to `true` outside. The test for lookup checks all of this:

```clojure
(deftest looking-up-variables
  (let [env [{:a 1 :b 2} {:b 3 :c nil}]]
    (is (= 1 (t/lookup env :a)))
    (testing "the innermost frame wins"
      (is (= 3 (t/lookup env :b))))
    (testing "bound to nil is still bound"
      (is (nil? (t/lookup env :c))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown variable :d"
                          (t/lookup env :d)))))
```

An unknown variable is an error rather than an empty string. Templating
systems that silently print nothing for a misspelled name are a reliable
source of pages that look fine and are wrong.

## Values as text

Before any directive can run, we need two ways of turning a value into
text. The first is escaping. The values in a `z-var` come from data, and
data that happens to contain `<script>` must not become a script when the
page is shown. Escaping the three characters that have meaning in HTML
text is enough:

```clojure
(defn escape
  "Make a value safe to put inside HTML text."
  [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))
```

```clojure
(escape "a <b> & c")   ;; => "a &lt;b&gt; &amp; c"
```

The ampersand must go first; if it went last it would re-escape the `&`
in the `&lt;` we had just produced. The expander escapes every value it
inserts, and there is no directive that inserts raw markup. That is a
deliberate omission. A template language whose default is unsafe needs
every author to remember to be careful; one whose default is safe needs
only the rare author who wants raw HTML to ask for it.

The second is formatting numbers. People reading an invoice want
`1,234.50`, not `1234.5`. Inserting the separators is a small exercise in
sequence functions: reverse the digits, cut them into groups of three,
put each group back the right way round, and join with commas.

```clojure
(defn- group-digits
  "Insert a comma every three digits from the right: \"1234567\" -> \"1,234,567\"."
  [digits]
  (->> (reverse digits)
       (partition-all 3)
       (map #(apply str (reverse %)))
       reverse
       (str/join ",")))

(defn format-number
  "Render a number for people: thousands separators, and two decimal
   places for anything that is not a whole number."
  [n]
  (let [text (if (integer? n)
               (str n)
               (str (.setScale (bigdec n) 2 java.math.RoundingMode/HALF_UP)))
        [sign text] (if (str/starts-with? text "-") ["-" (subs text 1)] ["" text])
        [whole fraction] (str/split text #"\." 2)]
    (str sign (group-digits whole) (when fraction (str "." fraction)))))
```

```clojure
(format-number 1234567)   ;; => "1,234,567"
(format-number 1234.5)    ;; => "1,234.50"
(format-number -1000)     ;; => "-1,000"
```

The one piece of **Java interop** here is `.setScale`. A form that
starts with a dot calls a method on the Java object that follows:
`(.setScale x 2 mode)` is `x.setScale(2, mode)`. We convert the number to
a `java.math.BigDecimal` with `bigdec` and ask it for two decimal places
with round-half-up, because the obvious alternative, `(format "%.2f" n)`,
uses the machine's locale and prints `3,14` on a computer set up for
German. Doing the grouping ourselves rather than asking a formatter keeps
the output the same everywhere, which is what tests need.

## Dispatching on the directive

An interpreter needs a way to go from an instruction to the code that
runs it. The interpreter chapter used a map from operation name to
function; here we use a **multimethod**, introduced in the
[objects chapter](../02-objects/), which does the same thing but lets
cases be added from anywhere:

```clojure
(defmulti directive
  "Expand `node`, which carries the directive attribute `attr` with the
   given `value`, in environment `env`. Returns HTML text. Dispatches on
   the attribute name, so a new directive is one new defmethod."
  (fn [attr env node value] attr))

(defmethod directive :default
  [attr _ node _]
  (throw (ex-info (str "unknown directive " attr " on <" (:tag node) ">")
                  {:attr attr :tag (:tag node)})))
```

The dispatch function returns the attribute name, so `(directive "z-if"
...)` runs the method registered for `"z-if"`. The `:default` method is
called when no other matches, and it throws. Any attribute starting with
`z-` that we do not recognize is almost certainly a typo, and a typo that
silently does nothing is the worst kind.

An element can carry more than one directive. `<li z-loop="x:xs"
z-var="x">` is the natural way to write a list, and `z-if` on the same
element is reasonable too. That raises the question of order. It would
be wrong to fill in `z-var="x"` before the loop had bound `x`, and it
would be wasteful to run a loop whose element `z-if` was about to drop.
So the structural directives, the ones that decide whether and how often
the element appears, run first, in a fixed order, and anything else runs
after them:

```clojure
(def ^:private structural
  "Directives that decide whether and how often an element appears.
   They run before any others on the same element, in this order."
  ["z-if" "z-loop"])

(defn- next-directive
  "The name of the directive to apply to `node` first, or nil if it has none."
  [node]
  (let [names (filter #(str/starts-with? % "z-") (keys (:attrs node)))]
    (when (seq names)
      (or (some (set names) structural)
          (first (sort names))))))

(defn- without
  "The element without one of its attributes."
  [node attr]
  (update node :attrs dissoc attr))
```

`(some (set names) structural)` uses a set as a function: it walks the
`structural` list in order and returns the first name that the element
actually has. If there is none, the remaining directives are sorted so
that the choice is at least predictable. `next-directive` picks only
*one* directive, and that is the key to the whole design: each directive
will remove its own attribute from the element and hand what is left back
to the expander, which will then pick the next one. Several directives on
one element compose without any directive knowing about the others.

## Expanding the tree

The evaluation function is a recursion over the tree that returns text.
Strings are already text. The document root is just its children. An
element either has a directive, in which case the directive is in charge
of what text it becomes, or it does not, in which case it is printed as
an opening tag, its expanded children, and a closing tag:

```clojure
(declare expand-node)

(defn- expand-children
  "Expand every child of `node` and join the results."
  [env node]
  (apply str (map #(expand-node env %) (:children node))))

(defn expand-node
  "Expand one node of the tree in `env` to HTML text."
  [env node]
  (cond
    (string? node)
    node

    (= :document (:tag node))
    (expand-children env node)

    :else
    (if-let [attr (next-directive node)]
      (directive attr env node (get-in node [:attrs attr]))
      (str "<" (:tag node) (p/attrs->html (:attrs node)) ">"
           (expand-children env node)
           "</" (:tag node) ">"))))

(defn expand
  "Expand `template` (HTML text, or an already parsed tree) using `vars`,
   a map from keyword to value. Returns HTML text."
  [template vars]
  (expand-node [vars] (if (string? template) (p/parse template) template)))
```

`expand-children` calls `expand-node`, and `expand-node` calls
`expand-children`. Clojure compiles a file from top to bottom and will
not let a function refer to a var that does not exist yet, so one of the
two has to be announced before it is defined. `(declare expand-node)`
creates the var with no value, which is enough for the compiler; the
`defn` later fills it in.

The public entry point, `expand`, wraps the map of variables in a vector
to make the initial environment with a single frame, and parses the
template if it was given as text. Accepting an already parsed tree as
well costs one `if` and means a program that expands the same template
many times can parse it once.

```clojure
(expand "<p z-var=\"word\">placeholder</p>" {:word "Hello"})
;; => "<p>Hello</p>"
(expand "<p z-if=\"show\">yes</p><p>always</p>" {:show false})
;; => "<p>always</p>"
```

There is a choice buried in `expand-node` worth making explicit. It
could have returned a new *tree* with the directives applied, leaving it
to `->html` from the parser to produce text. That would be a
tree-to-tree transformation, like `clojure.walk/postwalk`, and it would
let us run the validator over the result. We chose tree-to-text instead
because it is simpler: a `z-loop` that produces three elements where
there was one would have to splice a sequence into a parent's children,
whereas three strings simply concatenate. The exercises ask you to try
the other way.

## The directives themselves

With the machinery in place, each directive is a few lines. All four
follow the same pattern: compute something, remove their own attribute
from the element, and call `expand-node` on what is left.

```clojure
(defmethod directive "z-if"
  [_ env node value]
  (if (lookup env (keyword value))
    (expand-node env (without node "z-if"))
    ""))
```

A `z-if` whose variable is truthy expands the element without the
`z-if`, so that any other directives on it get their turn; one whose
variable is falsy produces the empty string, and nothing inside the
element is ever looked at. Clojure's notion of truthy is used directly,
so an empty vector keeps the element and only `nil` and `false` drop it.
That is the right choice for a language that wants to be small; a
language that wanted `[]` to count as false would have to define its own
truthiness and document it.

```clojure
(defmethod directive "z-loop"
  [_ env node value]
  (let [[item-name coll-name] (str/split value #":" 2)]
    (when-not coll-name
      (throw (ex-info (str "z-loop needs item:items, got " value) {:value value})))
    (let [body (without node "z-loop")]
      (apply str
             (for [item (lookup env (keyword coll-name))]
               (expand-node (conj env {(keyword item-name) item}) body))))))
```

The loop is where the environment stack earns its keep. For each value
in the collection, `(conj env {...})` pushes a new one-entry frame onto
the environment and expands the body in it. Nothing is popped
afterwards, because nothing was mutated: the `env` seen by the next
iteration, and by whatever comes after the loop, is the original vector.
Scopes come and go for free when environments are immutable.

```clojure
(defmethod directive "z-var"
  [_ env node value]
  (let [text (escape (lookup env (keyword value)))]
    (expand-node env (-> node (without "z-var") (assoc :children [text])))))

(defmethod directive "z-num"
  [_ env node value]
  (let [text (format-number (lookup env (keyword value)))]
    (expand-node env (-> node (without "z-num") (assoc :children [text])))))
```

`z-var` and `z-num` replace the element's children with a single string
and expand the result. Because strings pass through `expand-node`
untouched, the escaped text ends up in the output exactly as computed,
and the element's tag and remaining attributes are printed by the
ordinary path.

The tests in `test/sdx/template_test.clj` have one `deftest` per
directive and then a few for how they combine. The combination tests are
the important ones, since each directive on its own is nearly trivial and
the bugs live in the interactions:

```clojure
(deftest directives-nest
  (let [template "<table><tr z-loop=\"row:rows\"><td z-loop=\"cell:row\" z-var=\"cell\">?</td></tr></table>"]
    (is (= "<table><tr><td>1</td><td>2</td></tr><tr><td>3</td><td>4</td></tr></table>"
           (t/expand template {:rows [[1 2] [3 4]]}))))
  (testing "a loop body can see variables from outside the loop"
    (is (= "<p><span>x</span>-<span>1</span></p><p><span>x</span>-<span>2</span></p>"
           (t/expand "<p z-loop=\"n:ns\"><span z-var=\"prefix\">?</span>-<span z-var=\"n\">?</span></p>"
                     {:prefix "x" :ns [1 2]})))))

(deftest several-directives-on-one-element
  (testing "z-if is applied before z-loop, which is applied before z-var"
    (let [template "<li z-var=\"x\" z-loop=\"x:xs\" z-if=\"show\">?</li>"]
      (is (= "<li>a</li><li>b</li>" (t/expand template {:show true :xs ["a" "b"]})))
      (is (= "" (t/expand template {:show false :xs ["a" "b"]}))))))
```

The inner loop in `directives-nest` iterates over `row`, a variable that
the outer loop bound. That works because `lookup` searches the whole
stack, and it is the same mechanism that lets a loop body see `prefix`
from the top-level frame. The shadowing test, not shown here, checks
that a loop variable named `x` hides an outer `x` inside the loop and
that the outer one is back afterwards.

## Adding a directive

Because dispatch is a multimethod, a new directive is one `defmethod`
and no change to anything else. The test `adding-a-directive` defines a
`z-upper` that upper-cases a variable, uses it, and removes it again so
that other tests are unaffected:

```clojure
(deftest adding-a-directive
  (testing "a new directive is one defmethod, with no change to the expander"
    (defmethod t/directive "z-upper" [_ env node value]
      (t/expand-node env (-> node
                             (update :attrs dissoc "z-upper")
                             (assoc :children [(clojure.string/upper-case (t/lookup env (keyword value)))]))))
    (is (= "<p>LOUD</p>" (t/expand "<p z-upper=\"x\">?</p>" {:x "loud"})))
    (remove-method t/directive "z-upper")))
```

The new directive follows the same contract as the built-in ones: take
the attribute's value, do something, strip the attribute, and expand the
rest. Nothing enforces that contract except convention. A directive that
forgot to remove its attribute would be picked again by
`next-directive`, and the expander would recurse until the stack ran
out. That is the price of the open design: the multimethod will accept
any function, and only a test will tell you whether it behaves.

> **Open systems and closed ones.**
> The structural directives live in a private vector, and a new
> directive cannot join it. That is on purpose. Anyone can add a
> directive that transforms an element's content, but changing *whether*
> an element appears is a decision we want to keep in one place. Deciding
> which parts of a system are open to extension and which are closed is
> one of the most consequential choices in a design, and it is rarely
> written down.

## On the command line

A template engine is usually called from a program, but a command-line
version is handy for trying things out. The entry point reads a template
file and an EDN file of variables, expands one with the other, and prints
the result:

```clojure
(defn expand-files
  "Expand the template in `template-file` with the variables in `vars-file`."
  [template-file vars-file]
  (t/expand (slurp template-file) (edn/read-string (slurp vars-file))))

(defn -main
  "Print the expansion of a template file using an EDN file of variables."
  [template-file vars-file]
  (print (expand-files template-file vars-file))
  (flush))
```

The template and data at the start of this chapter are in
`resources/template/`, so the output shown there is what this command
prints:

```
$ clojure -M -m sdx.template.main resources/template/report.html resources/template/report.edn
```

EDN is Clojure's data notation, and `clojure.edn/read-string` reads it
without evaluating anything, which is what we want from a file that
could have come from anywhere. The test in
`test/sdx/template/main_test.clj` writes both files into a temporary
directory, calls `expand-files`, and deletes them.

## Summary

- A template language is an interpreter: the document is the program,
  the directives are its instructions, and the variables are its
  environment. Knowing that tells you what parts to build.
- Putting the language in attributes keeps every template valid HTML, so
  the parser and validator we already have apply to it.
- A stack of maps gives nested scopes for free when it is immutable:
  push a frame with `conj`, and there is nothing to pop.
- Use `find`, not `get`, when a key bound to `nil` or `false` must be
  told apart from a key that is absent.
- Escape by default. A system that is unsafe unless the author remembers
  something will eventually be unsafe.
- Dispatching with a multimethod lets anyone add a directive without
  touching the expander.
- When one element carries several directives, let each one remove
  itself and re-expand what is left; then they compose without knowing
  about each other.
- Format output without relying on the machine's locale, or tests that
  pass on one computer will fail on another.

## Exercises

1. **z-attr.** Add a directive `z-attr="href:url"` that sets the `href`
   attribute from the variable `url`. Does it need escaping, and if so,
   is it the same escaping as `z-var`?

2. **Dotted names.** Let `z-var="user.name"` look up `:user` and then
   `:name` inside it, so that loops over maps are useful. Change
   `lookup`, not the directives.

3. **Index variables.** Make `z-loop` also bind a variable holding the
   current index. What should it be called, and what happens if the
   template author already uses that name?

4. **Tree to tree.** Rewrite the expander so that it returns a new tree
   rather than text, and produce the text with `->html` from the parser.
   What has to change in `z-loop`? Then run the validator from
   [the previous chapter](../11-html/) on the result.

5. **Raw output.** Add a `z-raw` directive that inserts a variable
   without escaping it. Write the docstring that warns people about it.
   Would you rather not have it at all?

6. **Default values.** Let `z-var="name|fallback"` use the fallback text
   when the variable is unbound. Where is the right place to catch the
   exception that `lookup` throws, and is an exception the right
   mechanism for this at all?

7. **Including files.** Add `z-include="file"` that parses another
   template and expands it in the current environment. How do you stop
   a file that includes itself from running forever?

8. **Checking templates.** Write a validator check, in the style of the
   previous chapter, that reports a `z-loop` whose value does not have
   the form `item:items`, so that the mistake is found before the
   template is expanded.

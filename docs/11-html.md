---
title: "An HTML Validator"
nav_order: 11
lede: "A parser that turns HTML into a tree of maps, a visitor that walks it, and checks that report what is wrong."
---

Web pages are trees. A paragraph sits inside a body, which sits inside an
html element; a list item sits inside a list. Browsers are forgiving about
pages that break the rules, which is convenient for readers and terrible
for anyone trying to find out why a page looks wrong. In this chapter we
build a small validator that reads HTML, builds a tree, and reports
elements that carry attributes they should not have, elements nested
where they do not belong, and ids that are used twice.

Along the way we meet three ideas that show up far beyond HTML. The first
is that a parser can be split into a **tokenizer** that recognizes the
pieces and a **parser** that arranges them, which we also did when
[parsing glob patterns](../05-parser/). The second is that building a
tree from a flat stream of opening and closing tags needs nothing more
than a stack. The third is the **Visitor** pattern: separating the code
that knows how to walk a tree from the code that knows what to do at each
node, so that many different tools can share one walk. In object-oriented
languages the visitor is a class with one method per node type; in
Clojure it is a function that takes a map of callbacks.

The parser we write here is reused by the [template expander](../12-template/)
and the [page layout engine](../14-layout/), so it is deliberately small.
It handles opening tags with double-quoted attributes, closing tags,
self-closing tags like `<br/>`, and text. It does not handle comments,
entities, or the many tags that HTML lets you leave unclosed; the
exercises point at some of those.

## Tokens

The tokenizer turns a string into a vector of **tokens**, each a map with
a `:type` of `:open`, `:close`, or `:text`, and a `:pos` giving the
character offset where it started. Keeping the position lets every error
message say where the problem is, which is the difference between a tool
people use and a tool people curse.

Attributes are the fiddliest part, so they get their own function. One
attribute is a name followed optionally by `="value"`; the regular
expression below captures both, and `re-seq` finds every match in the
text after the tag name:

```clojure
(def ^:private attr-pattern
  "One attribute: a name, optionally followed by =\"value\"."
  #"([^\s=\"/>]+)(?:=\"([^\"]*)\")?")

(defn- parse-attrs
  "Turn the text after a tag name into a map of attribute name -> value.
   An attribute written without a value gets the empty string."
  [text]
  (into {}
        (map (fn [[_ name value]] [name (or value "")]))
        (re-seq attr-pattern text)))
```

`re-seq` returns a lazy sequence of matches, and because the pattern has
groups, each match is a vector of the whole match followed by the groups.
The second group is `nil` when the attribute has no value, so `(or value
"")` turns `<input hidden>` into `{"hidden" ""}`, which is how browsers
treat it too.

The inside of a tag, between `<` and `>`, is either a closing tag (it
starts with `/`) or an opening tag (a name followed by attributes, with an
optional trailing `/` for self-closing elements):

```clojure
(defn- parse-tag
  "Turn the inside of <...> into an :open or :close token."
  [body pos]
  (if (str/starts-with? body "/")
    {:type :close :tag (str/trim (subs body 1)) :pos pos}
    (let [self-closing? (str/ends-with? body "/")
          body (if self-closing? (subs body 0 (dec (count body))) body)
          [_ tag attr-text] (re-matches #"(?s)\s*([^\s/>]+)(.*)" body)]
      (when-not tag
        (throw (ex-info "tag has no name" {:pos pos})))
      (cond-> {:type :open :tag tag :attrs (parse-attrs attr-text) :pos pos}
        self-closing? (assoc :self-closing true)))))
```

The `(?s)` at the start of the pattern makes `.` match newlines, so a tag
whose attributes are spread over several lines still parses. `cond->` is
a threading macro that applies each step only when its test is true; here
it adds `:self-closing true` to the token only for tags like `<br/>`,
which keeps the common case free of a key that is almost always absent.

With those two helpers, the tokenizer itself is a loop over the string.
At each position it is either looking at a `<`, in which case the token
runs to the next `>`, or at text, which runs to the next `<` or to the end:

```clojure
(defn tokenize
  "Split HTML text into a vector of tokens. Each token is a map with a
   :type of :open, :close, or :text, and the :pos where it started."
  [text]
  (loop [pos 0
         tokens []]
    (cond
      (>= pos (count text))
      tokens

      (= \< (nth text pos))
      (let [end (str/index-of text ">" pos)]
        (when-not end
          (throw (ex-info "tag is never closed" {:pos pos})))
        (recur (inc end)
               (conj tokens (parse-tag (subs text (inc pos) end) pos))))

      :else
      (let [end (or (str/index-of text "<" pos) (count text))]
        (recur end
               (conj tokens {:type :text :text (subs text pos end) :pos pos}))))))
```

```clojure
(tokenize "<p class=\"note\">Hi <em>there</em></p>")
;; => [{:type :open, :tag "p", :attrs {"class" "note"}, :pos 0}
;;     {:type :text, :text "Hi ", :pos 16}
;;     {:type :open, :tag "em", :attrs {}, :pos 19}
;;     {:type :text, :text "there", :pos 23}
;;     {:type :close, :tag "em", :pos 28}
;;     {:type :close, :tag "p", :pos 33}]
```

Notice what the tokenizer does not do: it does not care whether tags
match, whether `<em>` is allowed inside `<p>`, or whether `blink` is a
real tag. Its only job is to find the boundaries between pieces. That
narrowness is what makes it easy to test: `tokenizing-attributes` in
`test/sdx/html/parser_test.clj` checks a single tag's attribute map
without building a document around it.

```clojure
(deftest tokenizing-attributes
  (is (= {"href" "x.html" "class" "big" "hidden" ""}
         (:attrs (first (p/tokenize "<a href=\"x.html\" class=\"big\" hidden>")))))
  (testing "tags may span lines"
    (is (= {"src" "a.png" "alt" "A"}
           (:attrs (first (p/tokenize "<img\n  src=\"a.png\"\n  alt=\"A\">")))))))
```

## From tokens to a tree

An element in our tree is a map with three keys: its `:tag`, a map of
`:attrs`, and a vector of `:children`, each of which is another element or
a string. The document as a whole is an element whose tag is the keyword
`:document` rather than a string, so that nothing in a real page can be
confused with it. A small constructor keeps tests readable:

```clojure
(defn element
  "Make an element node with the given tag, attribute map, and children."
  [tag attrs & children]
  {:tag tag :attrs attrs :children (vec children)})

(defn element?
  "True if `node` is an element rather than text."
  [node]
  (map? node))
```

The parser's job is to turn the flat token stream into nesting, and the
tool for that is a **stack** of elements that have been opened but not yet
closed. An opening tag pushes a new element; text is added to the children
of whatever is on top; a closing tag pops the top element and adds it to
the children of the element beneath it. When the input runs out, the
stack should hold exactly the document root.

We use a vector as the stack, because `conj`, `peek`, and `pop` all work
on the end of a vector. Adding a child to the top element means updating
the last entry of the vector:

```clojure
(def ^:private root
  {:tag :document :attrs {} :children []})

(defn- add-child
  "Append `child` to the children of the node on top of `stack`."
  [stack child]
  (update stack (dec (count stack)) update :children conj child))
```

The outer `update` replaces the last element of the stack with the result
of the inner `update`, which replaces that element's `:children` with the
result of `conj`. Nothing is mutated: each step returns a new vector that
shares almost all of its structure with the old one.

Feeding one token to the stack is a `case` on the token's type:

```clojure
(defn- push-token
  "Feed one token to the stack of open elements."
  [stack {:keys [type tag attrs self-closing pos text]}]
  (case type
    :text
    (add-child stack text)

    :open
    (let [node (element tag attrs)]
      (if self-closing
        (add-child stack node)
        (conj stack (assoc node :pos pos))))

    :close
    (let [open (peek stack)]
      (when (= :document (:tag open))
        (throw (ex-info (str "closing </" tag "> but nothing is open")
                        {:pos pos :found tag})))
      (when (not= tag (:tag open))
        (throw (ex-info (str "expected </" (:tag open) "> but found </" tag ">")
                        {:pos pos :expected (:tag open) :found tag})))
      (add-child (pop stack) (dissoc open :pos)))))
```

Two details are worth a look. A self-closing tag is an element with no
children that is finished the moment it is seen, so it goes straight into
the parent's children without ever being pushed. And an opening tag
records its `:pos` on the element while the element is on the stack, then
`dissoc`s it when the element is closed. The position is only needed to
report an element that is never closed, and we would rather the finished
tree carry no bookkeeping, so the key exists only as long as it is useful.

Parsing is then a `reduce` of `push-token` over the tokens, followed by a
check that everything was closed:

```clojure
(defn parse
  "Parse HTML text into a tree whose root has :tag :document.
   Throws ex-info with a :pos when tags are mismatched or left open."
  [text]
  (let [stack (reduce push-token [root] (tokenize text))]
    (when (> (count stack) 1)
      (let [open (peek stack)]
        (throw (ex-info (str "<" (:tag open) "> is never closed")
                        {:pos (:pos open) :tag (:tag open)}))))
    (peek stack)))
```

```clojure
(parse "<p class=\"note\">Hi <em>there</em></p>")
;; => {:tag :document, :attrs {}, :children
;;     [{:tag "p", :attrs {"class" "note"}, :children
;;       ["Hi " {:tag "em", :attrs {}, :children ["there"]}]}]}

(parse "<p><em>x</p>")
;; => throws ExceptionInfo "expected </em> but found </p>"
;;    with data {:pos 8, :expected "em", :found "p"}
```

Every error is an `ex-info` whose message is for people and whose data
map is for programs. The test for mismatched tags checks both, because a
message is easy to get slightly wrong and a position that is off by one
is worse than no position at all:

```clojure
(deftest parsing-reports-mismatched-tags
  (let [e (try (p/parse "<p><em>x</p>")
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= "expected </em> but found </p>" (ex-message e)))
    (is (= {:pos 8 :expected "em" :found "p"} (ex-data e)))))
```

Finally, we want to go the other way, from tree to text. The template
expander in the next chapter needs to print attributes, so that part is
public on its own. Attributes are sorted by name so that the output is the
same no matter what order a map happens to hold them in:

```clojure
(defn attrs->html
  "Render an attribute map as text, sorted by name so output is predictable."
  [attrs]
  (apply str (map (fn [[name value]] (str " " name "=\"" value "\""))
                  (sort attrs))))

(defn ->html
  "Turn a tree back into HTML text."
  [node]
  (cond
    (string? node) node
    (= :document (:tag node)) (apply str (map ->html (:children node)))
    :else (str "<" (:tag node) (attrs->html (:attrs node)) ">"
               (apply str (map ->html (:children node)))
               "</" (:tag node) ">")))
```

Having both directions gives us a cheap but powerful test: parsing and
unparsing should give back the original text, as long as the original
had its attributes in sorted order.

```clojure
(deftest round-trip
  (testing "parsing and unparsing gives back the text when attributes are sorted"
    (doseq [html ["<p>hello</p>"
                  "<a class=\"big\" href=\"x.html\">link</a>"
                  "<ul>\n  <li>one</li>\n  <li>two</li>\n</ul>"]]
      (is (= html (p/->html (p/parse html)))))))
```

A test like this does not tell us the tree has the right shape, but it
catches a whole class of bugs, dropped text, lost attributes, children in
the wrong order, with almost no effort.

> **Why not a recursive-descent parser?**
> The [pattern parser](../05-parser/) recursed into itself for every
> nested group. We could do the same here, with a function that parses one
> element by consuming an opening tag, parsing children until it sees the
> matching closing tag, and returning. The stack does the same work
> explicitly, and makes it easy to report the exact tag that was open when
> something went wrong. When the grammar is this regular, open, stuff,
> close, a stack is usually the simpler tool.

## Walking the tree

We are going to write several tools that look at every element of a page:
one that counts tags, one that gathers text, and several that look for
problems. Each needs to visit every node in order and know where it is.
Writing the recursion into each tool would mean writing it, and testing
it, five times.

The **Visitor pattern** separates the walk from the work. The walk is one
function that knows how to get to every node. The work is supplied by the
caller as callbacks that are invoked at each node. In languages with
classes, a visitor is an object with a method per node type; here it is a
map of functions, and since every function is passed an accumulator and
returns a new one, the visitor has no state of its own.

```clojure
(defn- call
  "Apply callback `f` if there is one, otherwise leave `acc` alone."
  [f acc node path]
  (if f (f acc node path) acc))

(defn walk
  "Visit every node under `tree`, calling the `callbacks` in document
   order and returning the final accumulator. The :document root is a
   container, not an element, so callbacks never see it."
  ([tree callbacks init]
   (walk tree callbacks init []))
  ([node {:keys [enter exit text] :as callbacks} acc path]
   (cond
     (string? node)
     (call text acc node path)

     (= :document (:tag node))
     (reduce (fn [acc child] (walk child callbacks acc path))
             acc
             (:children node))

     :else
     (let [path (conj path (:tag node))
           acc (call enter acc node path)
           acc (reduce (fn [acc child] (walk child callbacks acc path))
                       acc
                       (:children node))]
       (call exit acc node path)))))
```

There are three callbacks. `:enter` is called when an element is reached,
before its children; `:exit` is called after all of its children are
done; `:text` is called for each string. All three receive the
accumulator, the node, and a **path**: the vector of tags from the top of
the document down to the current element. The path is how a callback
knows where it is. Its last entry is the current tag, the entry before
that is the parent, and its length is the nesting depth. Text nodes get
the path of the element that contains them.

The walk is recursive, which means a document nested a thousand elements
deep would use a thousand stack frames. Real pages are rarely more than a
few dozen levels deep, so this is fine, and it keeps the code short. A
`reduce` over the children threads the accumulator through each one in
order, which is what makes the result deterministic.

Why have `:exit` at all? Because some questions can only be answered once
all the children have been seen: how many words does this section
contain, or is this list empty? The test `enter-and-exit-order` in
`test/sdx/html/visitor_test.clj` pins down exactly when each callback
fires:

```clojure
(deftest enter-and-exit-order
  (let [log (v/walk page
                    {:enter (fn [log node _] (conj log [:enter (:tag node)]))
                     :exit (fn [log node _] (conj log [:exit (:tag node)]))}
                    [])]
    (is (= [[:enter "html"] [:enter "body"]
            [:enter "h1"] [:exit "h1"]
            [:enter "ul"] [:enter "li"] [:exit "li"] [:enter "li"] [:exit "li"] [:exit "ul"]
            [:exit "body"] [:exit "html"]]
           log))))
```

With the walk written once, small tools are one callback each:

```clojure
(defn tag-counts
  "How many times each tag appears in `tree`."
  [tree]
  (walk tree
        {:enter (fn [counts node _] (update counts (:tag node) (fnil inc 0)))}
        {}))

(defn text-of
  "All the text in `tree`, in document order, joined into one string."
  [tree]
  (walk tree
        {:text (fn [pieces s _] (conj pieces s))}
        []))

(defn max-depth
  "The length of the longest path from the top of the document to an element."
  [tree]
  (walk tree
        {:enter (fn [deepest _ path] (max deepest (count path)))}
        0))
```

```clojure
(def page (parse "<html><body><h1>Title</h1><ul><li>a</li><li>b</li></ul></body></html>"))
(tag-counts page)   ;; => {"html" 1, "body" 1, "h1" 1, "ul" 1, "li" 2}
(text-of page)      ;; => ["Title" "a" "b"]
(max-depth page)    ;; => 4
```

`fnil` deserves a word: `(fnil inc 0)` is a version of `inc` that treats
a missing (`nil`) argument as `0`, so the first time a tag is seen its
count goes from nothing to one. It saves an `if` in nearly every counting
loop you will ever write.

## Checks

Now the validator itself. We want to report three kinds of problem: tags
we do not know, attributes a tag is not allowed to carry, and elements
whose parent is wrong. The rules for all three are tables, and tables
should be data, not code, so that someone can add a tag or an attribute
without understanding how the checker works:

```clojure
(def global-attributes
  "Attributes that any known element may carry."
  #{"class" "id" "style" "title"})

(def allowed-attributes
  "For each tag we know about, the attributes it may carry beyond the
   global ones. A tag that is not a key here is not a tag we accept."
  {"html"  #{"lang"}
   "head"  #{}
   "title" #{}
   "body"  #{}
   "h1"    #{}
   "h2"    #{}
   "p"     #{}
   "div"   #{}
   "span"  #{}
   "em"    #{}
   "strong" #{}
   "a"     #{"href" "target"}
   "img"   #{"src" "alt" "width" "height"}
   "ul"    #{}
   "ol"    #{"start"}
   "li"    #{"value"}
   "table" #{}
   "tr"    #{}
   "th"    #{"colspan"}
   "td"    #{"colspan"}})

(def nesting-rules
  "Tags that may only appear directly inside certain parents."
  {"li"    #{"ul" "ol"}
   "tr"    #{"table"}
   "td"    #{"tr"}
   "th"    #{"tr"}
   "title" #{"head"}})
```

This is a fraction of real HTML, but the shape is right: a map from tag to
set of attributes, and a map from tag to set of acceptable parents.
Everything else in this section is code that interprets those tables.

A **check** is a function that takes an element and its path and returns
a sequence of violations, where a violation is a map with a `:rule`, the
`:path` to the offending element, and a `:message`. The three rules
become three checks:

```clojure
(defn- violation
  "Build a violation map."
  [rule path message]
  {:rule rule :path path :message message})

(defn check-known-tag
  "A violation if `node` uses a tag we have no rules for."
  [node path]
  (when-not (contains? allowed-attributes (:tag node))
    [(violation :unknown-tag path (str "unknown tag <" (:tag node) ">"))]))

(defn check-attributes
  "A violation for each attribute that `node` is not allowed to carry.
   Unknown tags are skipped, since check-known-tag already reports them."
  [node path]
  (let [tag (:tag node)]
    (when-let [allowed (get allowed-attributes tag)]
      (for [attr (sort (keys (:attrs node)))
            :when (not (or (contains? global-attributes attr)
                           (contains? allowed attr)))]
        (violation :attribute path
                   (str "<" tag "> may not have attribute " attr))))))

(defn check-nesting
  "A violation if `node` has a nesting rule and its parent breaks it."
  [node path]
  (let [tag (:tag node)
        parent (peek (pop path))]
    (when-let [parents (get nesting-rules tag)]
      (when-not (contains? parents parent)
        [(violation :nesting path
                    (str "<" tag "> must be inside "
                         (str/join " or " (map #(str "<" % ">") (sort parents)))
                         ", not "
                         (if parent (str "<" parent ">") "the top level")))]))))

(def element-checks
  "The element checks run by default."
  [check-known-tag check-attributes check-nesting])
```

Each check returns `nil` when there is nothing to report, and `nil` is an
empty sequence as far as `mapcat` is concerned, so the checks compose
without special cases. `check-nesting` finds the parent by popping the
current tag off the path and peeking at what is left; for an element at
the top level the path has one entry, the pop leaves an empty vector, and
`peek` returns `nil`, which the message turns into "the top level".

Because a check is just a function of a node and a path, it can be tested
without a document, a walk, or a file:

```clojure
(check-nesting {:tag "li" :attrs {} :children []} ["div" "li"])
;; => [{:rule :nesting, :path ["div" "li"],
;;      :message "<li> must be inside <ol> or <ul>, not <div>"}]
```

The fourth problem we want to catch, an id used twice, is different in
kind. Whether an id is a duplicate depends on what came before it, so a
function of one node cannot decide. This check is written directly as a
walk whose accumulator remembers the ids seen so far alongside the
violations found:

```clojure
(defn check-unique-ids
  "A violation for each id that is used more than once in `tree`.
   This check needs to remember what it has seen, so it is written as a
   walk with its own accumulator instead of as an element check."
  [tree]
  (:violations
   (v/walk tree
           {:enter (fn [{:keys [seen] :as acc} node path]
                     (let [id (get-in node [:attrs "id"])]
                       (cond
                         (nil? id) acc
                         (seen id) (update acc :violations conj
                                           (violation :duplicate-id path
                                                      (str "id \"" id "\" is used more than once")))
                         :else (update acc :seen conj id))))}
           {:seen #{} :violations []})))

(def document-checks
  "The document checks run by default."
  [check-unique-ids])
```

Having two kinds of check is a design decision worth being honest about.
We could have made every check a full visitor with its own accumulator,
which would be uniform, but then the simple checks would carry
scaffolding they do not need. We could have given element checks access
to a shared "memory", but then they would no longer be pure functions
that can be tested in isolation. Keeping the simple case simple and
making the stateful case explicit is the compromise this chapter chooses.

Running everything is one walk for the element checks, then the document
checks over the whole tree:

```clojure
(defn validate
  "Run the element checks on every element and the document checks on
   the whole tree. Returns a vector of violations in document order,
   element violations first."
  ([tree]
   (validate tree element-checks document-checks))
  ([tree element-checks document-checks]
   (-> (v/walk tree
               {:enter (fn [found node path]
                         (into found (mapcat #(% node path)) element-checks))}
               [])
       (into (mapcat #(% tree)) document-checks))))

(defn path->string
  "Render a path for people: [\"html\" \"body\" \"p\"] -> \"html/body/p\"."
  [path]
  (str/join "/" path))
```

```clojure
(validate (parse "<div><li onclick=\"x\">y</li></div>"))
;; => [{:rule :attribute, :path ["div" "li"], :message "<li> may not have attribute onclick"}
;;     {:rule :nesting, :path ["div" "li"], :message "<li> must be inside <ol> or <ul>, not <div>"}]
```

The two-argument form of `validate` takes the lists of checks as
parameters. That is what makes the checker testable one rule at a time,
and what lets a user add a rule without editing this file. The test
`a-custom-check` in `test/sdx/html/check_test.clj` does exactly that with
a rule that this chapter never defined:

```clojure
(deftest a-custom-check
  (testing "any (fn [node path]) is a check"
    (let [no-empty-paragraphs (fn [node path]
                                (when (and (= "p" (:tag node)) (empty? (:children node)))
                                  [{:rule :empty-p :path path :message "empty paragraph"}]))]
      (is (= ["empty paragraph"]
             (map :message (c/validate (p/parse "<p></p><p>ok</p>")
                                       [no-empty-paragraphs] [])))))))
```

## A command-line validator

A validator is only useful if it can be pointed at a file, so the last
namespace is a small `-main`. It is split in two: `report` does the work
and returns a count, and `-main` turns that count into an exit status.
The split matters for testing, because a test can call `report` and look
at what it printed, whereas calling something that ends in `System/exit`
would end the test run too.

```clojure
(defn report
  "Validate the file at `filename`, print one line per problem, and return
   the number of problems found. A file that does not parse counts as one."
  [filename]
  (try
    (let [violations (c/validate (p/parse (slurp filename)))]
      (doseq [{:keys [path message]} violations]
        (println (str filename ":" (c/path->string path) ": " message)))
      (count violations))
    (catch clojure.lang.ExceptionInfo e
      (println (str filename ": parse error at " (:pos (ex-data e)) ": " (ex-message e)))
      1)))

(defn -main
  "Validate each file named on the command line; exit 1 if any had problems."
  [& filenames]
  (let [problems (reduce + 0 (map report filenames))]
    (System/exit (if (zero? problems) 0 1))))
```

`resources/html/sample.html` is a page with one of each kind of problem.
Running the validator on it from the project directory:

```
$ clojure -M -m sdx.html.main resources/html/sample.html
resources/html/sample.html:html/body/p/a: <a> may not have attribute onclick
resources/html/sample.html:html/body/li: <li> must be inside <ol> or <ul>, not <body>
resources/html/sample.html:html/body/blink: unknown tag <blink>
resources/html/sample.html:html/body/ul/li: id "top" is used more than once
```

The tests in `test/sdx/html/main_test.clj` write a page to a temporary
directory, call `report` with `*out*` bound to a string writer so that
nothing reaches the terminal, and compare the lines. A test that
exercises the same code path as the user does is worth more than one that
only tests the pieces.

## tree-seq and clojure.walk

Clojure ships with two general tools for trees, and it is worth seeing
what our visitor adds to them.

`tree-seq` turns any tree into a lazy sequence of its nodes in
depth-first order. It takes a predicate that says which nodes have
children and a function that returns them, and since our elements are
maps with a `:children` key, both are one word long:

```clojure
(map :tag (filter map? (tree-seq map? :children page)))
;; => (:document "html" "body" "h1" "ul" "li" "li")
```

For "find every node that..." questions this is hard to beat, and
`check-known-tag` could have been written as a `filter` over it. What
`tree-seq` cannot tell you is where each node is. By the time you are
looking at an `li`, its parent is gone; the sequence has flattened the
tree. Our walk keeps the path precisely because the nesting check needs
it.

`clojure.walk/postwalk` goes the other way: it rebuilds a tree, applying
a function to every node from the leaves up. It is the right tool for
transforming a tree, but it too has no idea where it is, and it applies
the function to *everything*, including the strings that are tag names:

```clojure
(clojure.walk/postwalk (fn [x] (if (string? x) (clojure.string/upper-case x) x))
                       (parse "<p>Hi <em>there</em></p>"))
;; => {:tag :document, :attrs {}, :children
;;     [{:tag "P", :attrs {}, :children ["HI " {:tag "EM", :attrs {}, :children ["THERE"]}]}]}
```

The text was upper-cased, but so were the tags, because to `postwalk` a
string is a string. Our walk knows that a string in a `:children` vector
is text and a string under `:tag` is not, because it was written for this
one shape of tree rather than for every nested collection.

That is the general lesson. The standard tools are general and therefore
know nothing about your data. A walk written for one kind of tree can
offer exactly the information its callbacks need, the path, the
enter/exit ordering, the distinction between elements and text, and is
only about twenty lines long. When the generic tool does the job, use it;
when it does not, writing a visitor is cheaper than it looks.

## Summary

- Splitting a parser into a tokenizer that finds the pieces and a parser
  that arranges them makes both halves small and separately testable.
- A stack turns a flat stream of opening and closing tags into a tree:
  push on open, pop on close, and anything still on the stack at the end
  is an error.
- Errors should carry a message for people and a data map for programs;
  `ex-info` gives us both, and tests should check both.
- If a tree can be turned back into text, a round-trip test catches many
  bugs for almost no effort.
- The Visitor pattern separates walking a tree from acting on its nodes.
  In Clojure a visitor is a map of callbacks that thread an accumulator,
  so it has no state of its own.
- A path, the list of tags from the root to the current node, is the
  cheapest way to tell a callback where it is.
- Rules belong in data (maps and sets) and checks belong in small pure
  functions over that data; the thing that runs the checks should take
  them as a parameter so that more can be added.
- `tree-seq` and `clojure.walk` are the right tools when you do not need
  to know where you are in the tree; when you do, write the walk.

## Exercises

1. **Single quotes.** Extend `attr-pattern` and `parse-attrs` so that
   attributes may use single quotes as well as double quotes. What
   happens to the round-trip test?

2. **Void elements.** Real HTML lets `<br>`, `<img>`, and a few others be
   written without a closing tag or a trailing slash. Add a set of such
   tags to the parser so that `<p>a<br>b</p>` parses. Where in
   `push-token` does the change go?

3. **Line and column.** Tokens record a character offset. Change them to
   record a line and column instead, and update the error messages. Is it
   better to compute line and column while tokenizing, or afterwards
   from the offset?

4. **Required attributes.** Add a table of attributes that a tag *must*
   have (`img` needs `alt`, `a` needs `href`) and a check that uses it.
   Does the check fit the element-check shape or the document-check
   shape?

5. **Exit-time checks.** Write a check that reports a `ul` or `ol` with no
   `li` children, and one that reports a `table` with no `tr`. Can these
   be element checks, given that an element already holds its children?
   When would you need the `:exit` callback instead?

6. **Depth limit.** Using `max-depth` as a model, write a document check
   that reports every element nested more than N levels deep, with N as a
   parameter. What is the cleanest way to make a parameterized check fit
   the `(fn [tree])` shape?

7. **Visitor with node parents.** Our path holds tags. Change the walk to
   pass a vector of ancestor *nodes* instead, and rewrite `check-nesting`
   to use it. What does the new version let a check do that the old one
   could not, and what does it cost?

8. **Streaming checks.** `tokenize` builds a vector of all tokens before
   parsing starts. Rewrite it to return a lazy sequence, and measure
   whether it makes any difference for a large file. Why might it not?

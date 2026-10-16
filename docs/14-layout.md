---
title: "Page Layout"
nav_order: 14
lede: "A layout engine that sizes blocks bottom-up, places them top-down, draws them as text, and wraps what does not fit."
---

A browser has to decide where everything on a page goes before it can
draw anything. A paragraph is as wide as the column it sits in and as
tall as its lines need; a table is as wide as its widest row; a row of
images is as tall as the tallest image. Every one of those sizes depends
on sizes further down the tree, and every position depends on positions
further up. Real layout engines are among the largest pieces of software
ever written, but the heart of the problem fits in a chapter.

Our page is a tree of **blocks**. A leaf has a fixed width and height.
A row puts its children side by side, so it is as wide as all of them
together and as tall as the tallest. A column stacks its children, so it
is as tall as all of them together and as wide as the widest. Laying out
such a tree takes three passes: compute every block's size from the
bottom up, assign every block a position from the top down, and draw.
Then we add the feature that makes layout genuinely hard: a row that is
wider than the page must be broken into several rows.

The design ideas here are about passes over a tree. Each pass is a
separate function that takes a tree and returns a tree with more
information attached, which makes each pass testable alone and lets us
add a new one, wrapping, without touching the others. We render to a
picture made of characters so that tests can say "the page should look
like this" rather than checking coordinates one at a time. And since the
blocks are plain maps distinguished by a `:type`, each pass is a
multimethod, which is where we pause to compare that choice with a
protocol.

## Blocks

A block is a map with a `:type`. Leaves carry their own size and a
character to draw themselves with; rows and columns carry a vector of
children. Three small constructors keep the tests short:

```clojure
(defn leaf
  "A block of fixed size, drawn with the character `fill`."
  ([width height] (leaf width height \x))
  ([width height fill] {:type :leaf :width width :height height :fill fill}))

(defn row
  "A block whose children sit side by side."
  [& children]
  {:type :row :children (vec children)})

(defn col
  "A block whose children are stacked vertically."
  [& children]
  {:type :col :children (vec children)})
```

```clojure
(row (leaf 2 1 \a) (leaf 1 2 \b))
;; => {:type :row, :children [{:type :leaf, :width 2, :height 1, :fill \a}
;;                            {:type :leaf, :width 1, :height 2, :fill \b}]}
```

Every pass in this chapter has to do something different for each of
the three types. The [objects chapter](../02-objects/) gave us two ways
to do that: a **multimethod**, which picks an implementation by calling
a dispatch function on its arguments, and a **protocol**, which picks by
the Java type of the first argument and needs a record or `deftype` for
each kind of block.

We use multimethods, and the reason is the shape of this chapter. We are
going to define four operations (size, place, draw, wrap) over three
types, and we are going to do it one operation at a time, each in its own
namespace. A multimethod lets each namespace add its operation to the
existing types without the types knowing. A protocol would want the
block types to list the protocols they implement, or would need
`extend-protocol` in each stage, which works but reads backwards: the
stage would be reaching into types defined elsewhere. Multimethods also
keep the blocks plain maps, which means `=` compares them structurally,
the REPL prints them readably, and a test can write down the exact tree
it expects. The price is that dispatch on a keyword is slower than
dispatch on a type, which for a few hundred blocks does not matter at
all.

> **The expression problem.**
> Adding a new kind of block to our system is easy with either tool: one
> more `defmethod` per operation, or one more record. Adding a new
> operation is easy with multimethods and awkward with protocols, because
> a protocol is declared once with all its functions. Systems that grow
> by adding types want protocols; systems that grow by adding operations
> want multimethods. Knowing which way yours will grow is half of choosing.

## Phase 1: sizes, bottom up

A row's width is the sum of its children's widths, so the children must
be sized before the row can be. That makes sizing a bottom-up pass:
recurse into the children first, then compute the parent from the
results. `size` returns the block it was given with `:width` and
`:height` filled in at every level:

```clojure
(defmulti size
  "Return `block` with :width and :height filled in for it and for every
   block inside it."
  :type)

(defmethod size :leaf
  [block]
  block)

(defmethod size :row
  [block]
  (let [children (mapv size (:children block))]
    (assoc block
           :children children
           :width (reduce + 0 (map :width children))
           :height (reduce max 0 (map :height children)))))

(defmethod size :col
  [block]
  (let [children (mapv size (:children block))]
    (assoc block
           :children children
           :width (reduce max 0 (map :width children))
           :height (reduce + 0 (map :height children)))))
```

The dispatch function is the keyword `:type`, which looks itself up in
the block. A leaf already knows its size, so its method returns the
block unchanged. A row and a column are mirror images: sum the widths
and take the largest height, or sum the heights and take the largest
width. The `0` given to `reduce` is what an empty row or column gets,
and it is what makes `(size (row))` return a block of no size rather
than an error.

```clojure
(size (row (leaf 2 1 \a) (leaf 1 2 \b)))
;; => {:type :row, :children [...], :width 3, :height 2}
```

The tests in `test/sdx/layout/size_test.clj` check each rule in
isolation and then one nested tree, because the bug that is easiest to
write is sizing the parent from the children's *original* maps instead
of the sized ones:

```clojure
(deftest sizes-are-computed-all-the-way-down
  (let [sized (s/size (col (row (leaf 1 1) (leaf 2 2)) (leaf 4 1)))]
    (is (= 4 (:width sized)))
    (is (= 3 (:height sized)))
    (testing "the inner row was sized too"
      (is (= [3 2] ((juxt :width :height) (first (:children sized))))))))
```

`juxt` takes several functions and returns one that calls them all and
collects the results in a vector, so `((juxt :width :height) block)` is
`[(:width block) (:height block)]`. It appears in nearly every test in
this chapter.

## Phase 2: positions, top down

A block's position depends on its parent's position and on the sizes of
the siblings before it, so placement runs the other way: decide where a
block goes, then tell each child where it goes. `place` takes a sized
block and the coordinates of its top-left corner, and returns the block
with `:x` and `:y` set everywhere:

```clojure
(defmulti place
  "Return the already sized `block` with :x and :y set on it and on every
   block inside it, given that its top-left corner is at (x, y)."
  (fn [block x y] (:type block)))

(defmethod place :leaf
  [block x y]
  (assoc block :x x :y y))

(defmethod place :row
  [block x y]
  (let [children (:children block)
        xs (reductions + x (map :width children))]
    (assoc block
           :x x
           :y y
           :children (mapv #(place %1 %2 y) children xs))))

(defmethod place :col
  [block x y]
  (let [children (:children block)
        ys (reductions + y (map :height children))]
    (assoc block
           :x x
           :y y
           :children (mapv #(place %1 x %2) children ys))))

(defn layout
  "Size and place a block tree with its top-left corner at the origin."
  [block]
  (place (s/size block) 0 0))
```

Since `place` takes three arguments, the dispatch function has to accept
all three and return the type of the first. The interesting line is the
one with `reductions`. Where `reduce` returns only the final result of
folding a function over a sequence, `reductions` returns every
intermediate result:

```clojure
(reductions + 0 [2 1 3])   ;; => (0 2 3 6)
```

Those are exactly the x coordinates of a row's children: the first child
starts at the row's x, the second starts after the first child's width,
and so on. The sequence has one more element than there are children (the
position where a fourth child would go), and `mapv` stops when the
shorter of its two inputs runs out, so the extra element is simply never
used. The column case is the same with heights and y.

In a row, every child's top edge is at the row's y, and in a column every
child's left edge is at the column's x. Children shorter than their row
leave a gap beneath them. Aligning them to the bottom or centering them
would be a one-line change to the `y` passed down, and the exercises ask
for it.

```clojure
(layout (row (leaf 2 1 \a) (leaf 1 2 \b)))
;; => {:type :row, :x 0, :y 0, :width 3, :height 2, :children
;;     [{:type :leaf, :width 2, :height 1, :fill \a, :x 0, :y 0}
;;      {:type :leaf, :width 1, :height 2, :fill \b, :x 2, :y 0}]}
```

The placement test that matters most is the one where a row sits inside
a column, because it checks that the offset passed to the inner row is
really passed on to the inner row's children:

The test uses a one-line helper that pulls out the corner of each child,
which keeps the assertions short enough to read at a glance:

```clojure
(defn- corners
  "The [x y] of each child of a placed block."
  [placed]
  (mapv (juxt :x :y) (:children placed)))
```

```clojure
(deftest placement-is-relative-to-the-parent
  (let [placed (pl/layout (col (leaf 5 2) (row (leaf 1 1) (leaf 1 1))))
        inner (second (:children placed))]
    (testing "the row starts below the first leaf"
      (is (= [0 2] ((juxt :x :y) inner))))
    (testing "and its children are offset from where the row starts"
      (is (= [[0 2] [1 2]] (corners inner))))))
```

## Phase 3: pictures

Checking coordinates one by one, as the tests above do, is precise but
hard to read, and it gets worse as layouts get bigger. What we would like
to write in a test is the picture we expect. So the third pass draws the
placed tree onto a grid of characters and returns it as a vector of
strings, one per line.

The grid is a vector of vectors of characters. That is a slightly odd
choice for something we are going to draw into, and the reason is that
it lets us draw without mutation. `assoc-in` returns a new grid with one
cell changed, sharing everything else with the old one, and a rectangle
is a `reduce` of `assoc-in` over its cells:

```clojure
(defn blank-grid
  "A grid of `height` rows and `width` columns, all blank."
  [width height]
  (vec (repeat height (vec (repeat width \.)))))

(defn fill-rect
  "The grid with the rectangle whose top-left corner is (x, y) filled with `ch`."
  [grid x y width height ch]
  (reduce (fn [g [row col]] (assoc-in g [row col] ch))
          grid
          (for [row (range y (+ y height))
                col (range x (+ x width))]
            [row col])))
```

The `for` with two bindings produces every `[row col]` pair in the
rectangle, like a nested loop. Blank cells are `.` rather than a space so
that gaps are visible in test output and trailing spaces cannot be lost
by an editor.

Drawing is the third multimethod. A leaf fills its rectangle with its
`:fill` character. Rows and columns draw nothing of their own; they just
hand the grid to each child in turn. Any part of a row that no leaf
covers therefore stays blank, which is how gaps show up in the picture:

```clojure
(defmulti draw
  "Draw the sized and placed `block` onto `grid`, returning the new grid."
  (fn [grid block] (:type block)))

(defmethod draw :leaf
  [grid {:keys [x y width height fill]}]
  (fill-rect grid x y width height fill))

(defmethod draw :row
  [grid block]
  (reduce draw grid (:children block)))

(defmethod draw :col
  [grid block]
  (reduce draw grid (:children block)))

(defn render
  "Lay out `block` and render it as a vector of strings, one per line."
  [block]
  (let [placed (pl/layout block)
        grid (blank-grid (:width placed) (:height placed))]
    (mapv #(apply str %) (draw grid placed))))
```

`draw` takes the grid first so that it has the shape `reduce` wants: a
function of an accumulator and an item. That is why `(reduce draw grid
children)` is all a container has to do. `render` runs the earlier
phases, makes a grid of the root's size, draws, and turns each row of
characters into a string.

```clojure
(render (row (leaf 2 1 \a) (leaf 1 2 \b)))
;; => ["aab"
;;     "..b"]

(render (col (row (leaf 2 1 \a) (leaf 1 2 \b))
             (leaf 3 1 \c)))
;; => ["aab"
;;     "..b"
;;     "ccc"]
```

Now the tests can be pictures. `test/sdx/layout/render_test.clj`
describes each layout by what it should look like, and when a test fails
the diff shows two pictures side by side, which is far easier to debug
than a wrong number:

```clojure
(deftest a-row-leaves-gaps-under-short-children
  (is (= ["aab"
          "..b"]
         (r/render (row (leaf 2 1 \a) (leaf 1 2 \b))))))

(deftest a-column-leaves-gaps-beside-narrow-children
  (is (= ["a.."
          "bbb"]
         (r/render (col (leaf 1 1 \a) (leaf 3 1 \b))))))
```

> **Rendering as a test oracle.**
> A renderer is often worth writing for the tests alone. Once a complex
> structure can be turned into something a person can look at, the tests
> for everything upstream of the renderer become easier to write, easier
> to read, and much easier to believe. The same idea appears wherever a
> program's output is a structure: pretty-printers for parse trees, dumps
> of a database page, a disassembler for a virtual machine.

## Wrapping

So far a row is as wide as its children, however wide that is. A real
page has a maximum width, and a row that is too wide must be broken into
pieces, each on its own line. The pieces are rows; the line stack is a
column; so wrapping a row means turning it into a column of rows.

The first step is to decide which children go on which line. Walking
through the children in order, we add each to the current line if it
fits and start a new line if it does not. A child that is wider than the
page on its own cannot fit anywhere, so it gets a line of its own and
overflows:

```clojure
(defn- line-width
  "The total width of the sized blocks in `line`."
  [line]
  (reduce + 0 (map :width line)))

(defn break-lines
  "Split the sized `children` of a row into lines, starting a new line
   whenever adding the next child would make the current one wider than
   `max-width`. A child that is too wide on its own gets a line to itself."
  [children max-width]
  (reduce (fn [lines child]
            (let [line (peek lines)]
              (if (and (seq line)
                       (> (+ (line-width line) (:width child)) max-width))
                (conj lines [child])
                (conj (pop lines) (conj line child)))))
          [[]]
          children))
```

The accumulator is a vector of lines that starts with one empty line.
`peek` gives the current line; if the child fits, we `pop` the line off,
add the child to it, and `conj` it back; otherwise we start a new line
holding only the child. The `(seq line)` test is what makes an oversized
child go onto the current line when that line is empty, instead of
creating an empty line in front of it.

```clojure
(break-lines [(leaf 2 1 \a) (leaf 2 1 \b) (leaf 2 1 \c)] 4)
;; => [[{:type :leaf, :width 2, ...} {:type :leaf, :width 2, ...}]
;;     [{:type :leaf, :width 2, ...}]]
```

`break-lines` needs the children's widths, so they must be sized before
it runs, and that leads to a question of order. Should a row wrap its
children before deciding how to break itself? It must, because a child
that is itself a wide row will become narrower once it wraps, and that
changes whether it fits. So `wrap` recurses first, sizes what comes back,
and only then breaks lines:

```clojure
(defmulti wrap
  "Return a version of `block` in which no row is wider than `max-width`
   unless a single child forces it. Rows that are too wide become a
   column of rows."
  (fn [block max-width] (:type block)))

(defmethod wrap :leaf
  [block _]
  block)

(defmethod wrap :col
  [block max-width]
  (update block :children (fn [children] (mapv #(wrap % max-width) children))))

(defmethod wrap :row
  [block max-width]
  (let [children (mapv #(s/size (wrap % max-width)) (:children block))
        lines (break-lines children max-width)]
    (if (= 1 (count lines))
      (assoc block :children children)
      (apply s/col (map #(apply s/row %) lines)))))
```

A row that fits on one line is returned as it was. One that does not
becomes a column whose children are rows, one per line. The result is an
ordinary block tree, which is the important design decision in this
section: wrapping is a transformation from trees to trees, done *before*
layout, and the three layout phases never find out that it happened. We
added a feature without changing a line of the code that was already
tested.

```clojure
(wrap (row (leaf 2 1 \a) (leaf 2 1 \b) (leaf 2 1 \c)) 4)
;; => {:type :col, :children
;;     [{:type :row, :children [{:type :leaf, ... \a} {:type :leaf, ... \b}]}
;;      {:type :row, :children [{:type :leaf, ... \c}]}]}

(render (wrap (row (leaf 2 1 \a) (leaf 2 1 \b) (leaf 2 1 \c)) 4))
;; => ["aabb"
;;     "cc.."]
```

The tests in `test/sdx/layout/wrap_test.clj` check `break-lines` on
its own, check the shape of the tree that `wrap` produces, and then
check pictures. The one below is the case that motivated wrapping the
children first: the inner row is four wide, but once it wraps to two
wide, the outer row fits in three columns and does not need to wrap at
all.

Here too a small helper does the wrapping and rendering in one step so
that each test is just a block, a width, and the picture we expect:

```clojure
(defn- picture
  "Render `block` after wrapping it at `max-width`."
  [block max-width]
  (r/render (w/wrap block max-width)))
```

```clojure
(deftest inner-rows-wrap-before-outer-ones
  (testing "the inner row wraps to 2 wide, so the outer row then fits in 3"
    (is (= ["abb"
            ".cc"]
           (picture (row (leaf 1 1 \a) (row (leaf 2 1 \b) (leaf 2 1 \c))) 3)))))
```

Because `wrap` returns an unsized tree, `render` sizes it again. The
sizes that `wrap` computed on the way are thrown away and recomputed,
which is wasteful by a constant factor and keeps every phase independent
of every other. That is a trade we will make every time at this scale.

## Layouts as markup

Writing layouts as nested function calls is fine for tests and awkward
for anything else, so the last stage lets a layout be written as markup
and parsed with the [HTML parser](../11-html/). The three block types
become three tags, and a leaf's size and fill become attributes:

```html
<col>
  <row>
    <leaf width="4" height="1" fill="h"/>
    <leaf width="6" height="1" fill="t"/>
  </row>
  <row>
    <leaf width="3" height="2" fill="a"/>
    <leaf width="3" height="2" fill="b"/>
    <leaf width="3" height="1" fill="c"/>
    <leaf width="3" height="2" fill="d"/>
  </row>
  <leaf width="5" height="1" fill="f"/>
</col>
```

Turning the parsed tree into blocks is a `case` on the tag. The
indentation in the file arrives as text nodes between the elements, and
we skip those; the parser kept them because it did not know they were
unimportant, and only this stage does.

```clojure
(defn- attr-int
  "The whole-number value of attribute `name` on `node`, or an error."
  [node name]
  (let [value (get-in node [:attrs name])]
    (or (and value (parse-long value))
        (throw (ex-info (str "<" (:tag node) "> needs a whole-number " name)
                        {:tag (:tag node) :attr name :value value})))))

(defn- child-elements
  "The element children of `node`, skipping text."
  [node]
  (filter p/element? (:children node)))

(defn block
  "Turn a parsed <leaf>, <row>, or <col> element into a block."
  [node]
  (case (:tag node)
    "leaf" (s/leaf (attr-int node "width")
                   (attr-int node "height")
                   (first (get-in node [:attrs "fill"] "x")))
    "row" (apply s/row (map block (child-elements node)))
    "col" (apply s/col (map block (child-elements node)))
    (throw (ex-info (str "unknown block <" (:tag node) ">") {:tag (:tag node)}))))

(defn parse-layout
  "Parse markup describing a layout into a block tree. The markup must
   have exactly one top-level element."
  [text]
  (let [elements (child-elements (p/parse text))]
    (when (not= 1 (count elements))
      (throw (ex-info (str "expected one top-level block, found " (count elements))
                      {:count (count elements)})))
    (block (first elements))))
```

`parse-long` returns `nil` for text that is not a number, and `and`
turns a missing attribute into `nil` as well, so one `or` covers both
ways an attribute can be wrong. Every error names the tag and the
attribute, because a layout file with forty leaves and one typo is
otherwise a miserable thing to debug.

```clojure
(parse-layout "<row><leaf width=\"2\" height=\"1\" fill=\"a\"/><leaf width=\"1\" height=\"2\" fill=\"b\"/></row>")
;; => {:type :row, :children [{:type :leaf, :width 2, :height 1, :fill \a}
;;                            {:type :leaf, :width 1, :height 2, :fill \b}]}
```

The command-line entry point strings the stages together: parse, wrap at
the requested width, render, print.

```clojure
(defn render-file
  "Read the layout markup in `filename` and render it as lines of text,
   wrapping rows wider than `max-width`."
  [filename max-width]
  (-> (slurp filename)
      m/parse-layout
      (w/wrap max-width)
      r/render))

(defn -main
  "Print the rendering of a layout file wrapped at the given width."
  [filename max-width]
  (doseq [line (render-file filename (parse-long max-width))]
    (println line)))
```

The markup above is in `resources/layout/page.html`. At a width of
twenty nothing wraps; at eight, both rows do, and the whole page becomes
narrower because its width is now the widest *line* rather than the
widest row:

```
$ clojure -M -m sdx.layout.main resources/layout/page.html 20
hhhhtttttt..
aaabbbcccddd
aaabbb...ddd
fffff.......

$ clojure -M -m sdx.layout.main resources/layout/page.html 8
hhhh..
tttttt
aaabbb
aaabbb
cccddd
...ddd
fffff.
```

The test for this stage in `test/sdx/layout/markup_test.clj` writes a
small layout to a temporary file and renders it at two widths through
`render-file`, so that the whole pipeline from text to picture is covered
by one test.

## Summary

- Layout is a sequence of passes over a tree: sizes flow up from the
  leaves, positions flow down from the root, and drawing happens last.
- Each pass is a function from a tree to a tree with more keys filled
  in, so each can be tested on its own and new passes can be inserted.
- Multimethods let each pass be defined in its own namespace over the
  same plain maps; protocols would be the better choice if we expected
  to add many block types rather than many operations.
- `reductions` turns a list of sizes into a list of positions in one
  step.
- A renderer that turns a structure into a picture makes tests for
  everything upstream of it easier to write and to read.
- Wrapping is a tree-to-tree rewrite done before layout, so the layout
  phases need not know it exists; wrap the children before the parent,
  because wrapping changes widths.
- Reusing the HTML parser gives us a file format for free, and
  converting its tree into ours is one small function.

## Exercises

1. **Alignment.** Rows align their children to the top. Add an `:align`
   key to rows (`:top`, `:middle`, or `:bottom`) and make `place` respect
   it. Does `size` need to change?

2. **Borders.** Make `draw` for rows and columns outline their rectangle
   with `+`, `-`, and `|` before drawing the children. What happens when
   a child covers the border, and is that what you want?

3. **Padding.** Give leaves an optional `:padding` that adds blank space
   around them. Which phase does it belong in, and should the blank
   space be drawn by the leaf or left to the parent?

4. **Text leaves.** Let `<leaf>` in the markup contain text instead of
   width and height attributes, with the text's length as the width and
   `1` as the height, and have `draw` write the text instead of a fill
   character. Now a row of words wraps like a paragraph.

5. **Wrapping columns.** A column taller than a maximum height could be
   broken into a row of columns, the way a newspaper breaks a story into
   columns. Add `max-height` to `wrap` and decide what happens when both
   limits are in force.

6. **A protocol instead.** Reimplement the three phases with a `Block`
   protocol and three records. Which parts of the chapter get shorter,
   which get longer, and what happens to the test that compares the
   tree `wrap` produces with `=`?

7. **Avoiding the second sizing.** `wrap` sizes the children and then
   `render` sizes everything again. Change `wrap` to return a sized tree
   and `render` to accept one. How many places have to know about the
   change, and is the saving worth it?

8. **Larger pages.** Generate a random tree of a few thousand blocks and
   time `render`. Which phase dominates, and why might `fill-rect` be
   the first thing to improve?

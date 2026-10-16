---
title: "A File Viewer"
nav_order: 23
lede: "A terminal file viewer whose model is a pure function of the keys it has seen, with the terminal kept at the edge."
---

Programs that take over the terminal, like `less`, `top`, and every
text editor, have a reputation for being untestable. They read keys one
at a time, they write strange escape codes, and they only work when a
real terminal is attached. Most of them are tested by a person sitting
in front of them, which means most of them are barely tested at all.

This chapter builds a small file viewer and arranges it so that nearly
everything can be checked with ordinary `deftest`s. The arrangement
rests on three ideas. First, the viewer never talks to the terminal
directly: it talks to a **protocol**, and the terminal is one
implementation of it, while a headless fake is another. Second, the
viewer's state is an immutable map and the viewer itself is two pure
functions, `step`, which turns a state and a key into a new state, and
`render`, which draws a state on whatever screen it is given. Third,
keys are plain data, so a test can script a session as a vector of
values and compare what ended up on the screen. The only code that
cannot be tested this way is the thin layer that speaks to a real
terminal, and we keep it as thin as we can.

## The screen as a protocol

What does a viewer need from a terminal? Surprisingly little. It has to
know how big the screen is, blank it, write some text at a position,
put the cursor somewhere, and wait for the next key. Those five
operations are the whole interface:

```clojure
(defprotocol Screen
  "What the viewer needs from a display and a keyboard."
  (dimensions [this] "The screen's [height width] in character cells.")
  (erase [this] "Blank every cell.")
  (write-at [this row col text]
    "Write `text` starting at `row`, `col` (both 0-based). Text past the
     right edge is clipped; rows off the screen are ignored.")
  (move-cursor [this row col] "Put the cursor at `row`, `col`.")
  (read-key [this] "Wait for the next key and return it, or nil if there is none."))
```

A protocol, as [the chapter on objects](../02-objects/) explained, is a
named set of functions that a type can promise to implement. Here it is
doing the job that an abstract base class does in other languages: it
names the seam between the viewer and the outside world so that the
outside world can be swapped. Everything above this line is ours to
test; everything below it has to deal with the terminal.

> **Why not `size` and `clear`?**
> We would have liked to call these methods `size` and `clear`, but
> both implementations of the protocol are going to be records, and a
> record is also a `java.util.Map`, which already has methods with
> those names. The compiler refuses to generate a class with two
> `size()` methods. The lesson generalizes: when naming protocol
> methods that records will implement, avoid the names of methods on
> `Map` and `Object` (`size`, `clear`, `get`, `count`, `keys`,
> `values`, `equals`, `toString`, and so on).

The protocol says what a key *is* only in its docstring, so let us be
explicit. A printable character arrives as a one-character string,
`"j"` or `"q"`. Everything else arrives as a keyword: `:up`, `:down`,
`:page-down`, `:enter`, `:backspace`, `:ctrl-c`, and so on. `read-key`
returns `nil` when there is nothing more to read. Choosing plain values
rather than, say, key codes means a test can write `["j" "j" :page-down
"q"]` and a reader can understand it without a lookup table.

Two helpers are useful to every implementation. `clip` keeps a string
from running off the right edge, and `overlay` writes a string into
another string the way a terminal writes into a row of cells:

```clojure
(defn clip
  "At most the first `n` characters of `text`."
  [text n]
  (if (> (count text) n)
    (subs text 0 (max n 0))
    text))

(defn overlay
  "The string `row` with `text` written over it from column `col`,
   clipped to the row's width. Writes that start off the row are dropped."
  [row col text]
  (let [width (count row)]
    (if (or (neg? col) (>= col width))
      row
      (let [text (clip text (- width col))]
        (str (subs row 0 col) text (subs row (+ col (count text))))))))
```

```clojure
(overlay "....." 2 "ab")       ;; => "..ab."
(overlay "....." 4 "abc")      ;; => "....a"
(overlay "....." 5 "abc")      ;; => "....."
```

Now the headless screen. It draws into a vector of strings, one per
row, and answers `read-key` from a list of keys it was given when it
was made:

```clojure
(defn- blank-rows
  "A vector of `height` strings, each `width` spaces long."
  [height width]
  (vec (repeat height (apply str (repeat width \space)))))

(defrecord Headless [height width cells script]
  Screen
  (dimensions [_] [height width])
  (erase [_]
    (swap! cells assoc :rows (blank-rows height width)))
  (write-at [_ row col text]
    (when (and (<= 0 row) (< row height))
      (swap! cells update-in [:rows row] overlay col text)))
  (move-cursor [_ row col]
    (swap! cells assoc :cursor [row col]))
  (read-key [_]
    (let [k (first @script)]
      (swap! script rest)
      k)))
```

The `cells` and `script` fields are **atoms**, one of the few places
in this book that reaches for mutable state on purpose. An atom is a box holding
one immutable value; `@cells` reads the current value and `(swap! cells
f args...)` replaces it with `(f current args...)`. The reason we need
one is that a screen is a *device*. Its whole purpose is to remember
what was drawn on it, and `write-at` is called for its effect, not its
return value. The real terminal has exactly the same nature: writing to
it changes the glass. The headless screen is honest about that by
keeping the same kind of state, just in memory where a test can look at
it. The important thing is where the atoms live: inside the screen, at
the edge of the program, where the viewer's own logic never sees them.

A constructor and two accessors round it out. The accessors are not
part of the protocol because the viewer has no business reading the
screen back; they exist for tests:

```clojure
(defn headless
  "A screen `height` rows by `width` columns that answers `read-key`
   with each of `keys` in turn and then with nil."
  [height width keys]
  (->Headless height width
              (atom {:rows (blank-rows height width) :cursor [0 0]})
              (atom (seq keys))))

(defn rows
  "What is on a headless screen right now: a vector of strings."
  [screen]
  (:rows @(:cells screen)))

(defn cursor
  "Where a headless screen's cursor is: [row col]."
  [screen]
  (:cursor @(:cells screen)))
```

The tests in `test/sdx/viewer/screen_test.clj` check the screen the way
we will later check the viewer: do something, then compare the rows.
Since `overlay` is where all the edge cases live, it gets a table:

```clojure
(deftest overlay-cases
  (doseq [[row col text expected]
          [["....." 0 "ab" "ab..."]
           ["....." 2 "ab" "..ab."]
           ["....." 4 "ab" "....a"]    ; clipped at the right edge
           ["....." 5 "ab" "....."]    ; starts off the row: dropped
           ["....." -1 "ab" "....."]   ; starts before the row: dropped
           ["....." 1 "" "....."]
           ["....." 0 "abcdefg" "abcde"]]]
    (testing (pr-str [row col text])
      (is (= expected (scr/overlay row col text))))))

(deftest writing-and-clearing
  (let [screen (scr/headless 3 5 [])]
    (scr/write-at screen 0 0 "hello world")
    (scr/write-at screen 1 2 "xy")
    (scr/write-at screen 7 0 "off the bottom")
    (scr/write-at screen -1 0 "off the top")
    (is (= ["hello" "  xy " "     "] (scr/rows screen)))
    (scr/move-cursor screen 1 3)
    (is (= [1 3] (scr/cursor screen)))
    (scr/erase screen)
    (is (= ["     " "     " "     "] (scr/rows screen)))))
```

## Stage 1: showing a file

The first version of the viewer, in `sdx.viewer.show`, does one thing:
it draws the start of a file and waits for `q`. It is worth building
even though it does so little, because it establishes the shape that
the later stages keep. There is a state, a function that draws the
state, and a loop that reads keys:

```clojure
(defn make-state
  "The viewer's state for `lines`: just the lines, for now."
  [lines]
  {:lines (vec lines)})

(defn render
  "Draw as many of the lines as fit, starting at the top left."
  [screen state]
  (let [[height _] (scr/dimensions screen)]
    (scr/erase screen)
    (doseq [[row line] (map-indexed vector (take height (:lines state)))]
      (scr/write-at screen row 0 line))
    (scr/move-cursor screen 0 0)))
```

`render` is the only function in this chapter that calls the drawing
side of the protocol, and it always draws the whole screen from
scratch. That is wasteful, since most key presses change one row, but
it means the picture on the screen is a function of the state and
nothing else. There is no way for a stale row to survive from an
earlier frame, which is the class of bug that haunts programs that try
to be clever about redrawing. `(map-indexed vector coll)` pairs each
line with its index, which `doseq` then destructures.

The loop is equally plain:

```clojure
(defn run
  "Draw the state, then wait until the user presses q or the keys run
   out. Returns the state so that a caller can inspect it."
  [screen state]
  (render screen state)
  (loop []
    (let [key (scr/read-key screen)]
      (if (or (nil? key) (= "q" key))
        state
        (recur)))))
```

Stopping on `nil` as well as on `"q"` is a small decision with a large
effect on testing: a test whose script forgets to end with `"q"` ends
anyway instead of hanging forever. The test file
`test/sdx/viewer/show_test.clj` checks the drawing and the quitting:

```clojure
(deftest render-shows-what-fits
  (let [screen (scr/headless 3 4 [])]
    (show/render screen (show/make-state lines))
    (testing "rows beyond the screen's height are not drawn"
      (is (= ["one " "two " "thre"] (scr/rows screen))))
    (is (= [0 0] (scr/cursor screen)))))

(deftest run-waits-for-q
  (let [screen (scr/headless 3 4 ["x" "y" "q" "z"])
        state (show/run screen (show/make-state lines))]
    (is (= lines (:lines state)))
    (testing "keys after q are never read"
      (is (= "z" (scr/read-key screen))))))
```

Notice what the second test does: after the viewer has quit, it asks
the screen for the next key and expects to get the one after `"q"`.
The scripted screen does not just let us drive the viewer; it lets us
check how much of the script the viewer consumed.

## Stage 2: a cursor

`sdx.viewer.cursor` adds the thing that makes a viewer feel like a
viewer: a cursor the user can move with the arrow keys or with `h`,
`j`, `k`, and `l`. The state gains a `:cursor`, a `[row col]` pair:

```clojure
(defn make-state
  "The viewer's state for `lines`, with the cursor at the top left."
  [lines]
  {:lines (vec lines) :cursor [0 0]})

(defn- clamp
  "`x`, pulled back into the range `lo`..`hi` if it is outside it."
  [x lo hi]
  (-> x (max lo) (min hi)))

(defn move
  "Move the cursor by `dr` rows and `dc` columns without leaving the
   text. The column may rest just past the last character of a line,
   which is also the only legal column on an empty line."
  [state dr dc]
  (let [{:keys [lines cursor]} state
        [row col] cursor
        row (clamp (+ row dr) 0 (max 0 (dec (count lines))))
        col (clamp (+ col dc) 0 (count (nth lines row "")))]
    (assoc state :cursor [row col])))
```

Every movement goes through `move`, and `move` is where the rules
about where a cursor may be are enforced. The row is clamped to the
lines that exist. The column is clamped to the length of the line the
cursor *lands on*, not the line it came from, so moving down from the
end of a long line onto a short one pulls the cursor in. We allow the
column to equal the line's length, one past the last character, for
two reasons. The practical one is that the [next chapter](../24-undo/)
turns this viewer into an editor, and an editor has to be able to
append to a line. The nicer one is that an empty line then needs no
special case: its only legal column is zero, and `(count "")` is zero.

With `move` in hand, each key's behaviour is a one-line function, and
quitting becomes a change to the state rather than a decision the loop
makes:

```clojure
(defn up "Move the cursor up one row." [state] (move state -1 0))
(defn down "Move the cursor down one row." [state] (move state 1 0))
(defn left "Move the cursor left one column." [state] (move state 0 -1))
(defn right "Move the cursor right one column." [state] (move state 0 1))
(defn quit "Mark the state as finished." [state] (assoc state :quit true))

(def bindings
  "Which function handles each key. Keys that are not here are ignored."
  {"k" up,    :up up,
   "j" down,  :down down,
   "h" left,  :left left,
   "l" right, :right right,
   "q" quit,  :ctrl-c quit})

(defn step
  "The state after one key press."
  [state key]
  (if-let [action (get bindings key)]
    (action state)
    state))
```

`bindings` is a map from keys to functions, and `step` does nothing
but look the key up and call what it finds. We could have written a
`case` with the same branches, and it would have been slightly faster.
The map is better here because it is data: a test can read it to check
that `"j"` means `down`, a help screen could print it, a user could
override part of it, and, as the next chapter shows, another program
can take it and add bindings of its own without touching `step`.
Binding `:ctrl-c` to `quit` matters more than it looks, because in the
raw terminal mode we will set up later, Ctrl-C is just another key.

Rendering is the stage 1 version plus one line that puts the screen's
cursor where the state's cursor is, and the loop now checks the state
instead of the key:

```clojure
(defn render
  "Draw the lines and put the screen's cursor where the state's is."
  [screen state]
  (let [[height _] (scr/dimensions screen)
        [row col] (:cursor state)]
    (scr/erase screen)
    (doseq [[i line] (map-indexed vector (take height (:lines state)))]
      (scr/write-at screen i 0 line))
    (scr/move-cursor screen row col)))

(defn run
  "Draw, read a key, step, and repeat until the state says it has quit
   or the keys run out. Returns the final state."
  [screen state]
  (loop [state state]
    (if (:quit state)
      state
      (do
        (render screen state)
        (if-let [key (scr/read-key screen)]
          (recur (step state key))
          state)))))
```

This loop is the whole architecture of the program in eight lines.
`render` only reads the state; `step` only computes a new one; the
screen is touched in exactly two places, both inside the loop. Because
`step` is pure, most of the tests in `test/sdx/viewer/cursor_test.clj`
do not use a screen at all. Pressing a sequence of keys is just a
`reduce`:

```clojure
(defn- after
  "The cursor after pressing each of `keys` in turn, with no screen at all."
  [keys]
  (:cursor (reduce cur/step (cur/make-state lines) keys)))

(deftest cannot-leave-the-text
  (is (= [0 0] (after ["k" "h"])))
  (is (= [3 0] (after (repeat 10 "j"))))
  (testing "the column may rest just past the last character"
    (is (= [0 3] (after (repeat 10 "l"))))))

(deftest column-follows-the-line
  (testing "moving from a long line to a short one pulls the column in"
    (is (= [1 0] (after ["l" "l" "j"])))
    (is (= [3 2] (after ["j" "j" "l" "l" "l" "l" "j"])))))
```

Writing these tests turned up a behaviour that is easy to miss when
testing by hand. With `lines` being `["abc" "" "defgh" "ij"]`, pressing
right, down, down, up, up leaves the cursor at column 0, not column 1:
passing through the empty line clamped the column, and nothing
remembered where it had been. Real editors keep a "goal column" for
exactly this reason, and the exercises ask you to add one.

Stage 2 has a more serious problem, which one more test makes visible:

```clojure
(deftest the-cursor-can-fall-off-the-screen
  (testing "stage 2 has no scrolling: on a two-row screen the cursor ends up on row 3"
    (let [screen (scr/headless 2 6 ["j" "j" "j" "q"])]
      (cur/run screen (cur/make-state lines))
      (is (= [3 0] (scr/cursor screen)))
      (is (= ["abc   " "      "] (scr/rows screen))))))
```

The cursor is on line 3 of the text, but the screen has only two rows,
so the user is looking at a cursor that has vanished. The viewer needs
to scroll.

## Stage 3: scrolling

`sdx.viewer.core` is the finished viewer. The state learns which line
is at the top of the screen, `:top`, and how tall the screen is,
`:height`:

```clojure
(defn make-state
  "The viewer's starting state for `lines` on a screen `height` rows tall."
  [lines height]
  {:lines (vec lines) :cursor [0 0] :top 0 :height height})
```

Putting the screen's height into the state deserves a justification,
because it looks like the pure model is learning something about the
terminal. It is, and it has to: whether the cursor is visible and how
far a page key moves both depend on the window's size. The alternative,
passing the height to `step` on every call, would work too, but it
would make every function in the model take an extra argument for the
sake of two of them. So the model takes the height once, when the
state is made, and from then on a test can make a state with any height
it likes without a screen in sight.

`move` is unchanged from stage 2. Paging is new. A page key moves both
the window and the cursor by a screenful, with the window clamped so
that it never scrolls past the end of the text:

```clojure
(defn page
  "Move the window and the cursor together by `n` rows (negative is up)."
  [state n]
  (let [{:keys [lines height]} state
        max-top (max 0 (- (count lines) height))]
    (-> state
        (update :top #(clamp (+ % n) 0 max-top))
        (move n 0))))

(defn up "Move the cursor up one row." [state] (move state -1 0))
(defn down "Move the cursor down one row." [state] (move state 1 0))
(defn left "Move the cursor left one column." [state] (move state 0 -1))
(defn right "Move the cursor right one column." [state] (move state 0 1))
(defn page-up "Move up by a screenful." [state] (page state (- (:height state))))
(defn page-down "Move down by a screenful." [state] (page state (:height state)))
(defn quit "Mark the state as finished." [state] (assoc state :quit true))

(def bindings
  "Which function handles each key. Keys that are not here are ignored."
  {"k" up,    :up up,
   "j" down,  :down down,
   "h" left,  :left left,
   "l" right, :right right,
   :page-up page-up,
   :page-down page-down,
   "q" quit,  :ctrl-c quit})
```

Now the central decision of this stage. The cursor must always be on
the screen: that is an **invariant**, a property that should hold
after every key press. We could make every movement function
responsible for it, but then every new movement function would be a
chance to forget. Instead, one function knows how to restore the
invariant, and `step` calls it after every action, whatever the action
was:

```clojure
(defn scroll-to-cursor
  "Move :top by the smallest amount that puts the cursor's row on screen."
  [state]
  (let [{:keys [top height cursor]} state
        [row _] cursor]
    (assoc state :top
           (cond
             (< row top) row
             (>= row (+ top height)) (inc (- row height))
             :else top))))

(defn step
  "The state after one key press: do what the key is bound to, then
   scroll so that the cursor is visible. The two-argument form uses the
   viewer's own bindings; other programs can pass their own."
  ([state key] (step bindings state key))
  ([bindings state key]
   (if-let [action (get bindings key)]
     (scroll-to-cursor (action state))
     state)))
```

`scroll-to-cursor` moves the window by the smallest amount that works:
if the cursor is above the window, the window's top becomes the
cursor's row; if it is below, the window's bottom does. Moving down one
line at a time therefore scrolls one line at a time, which is what
people expect, while a page key, which has already moved `:top` itself,
leaves nothing for `scroll-to-cursor` to fix. The invariant is enforced
in one place, and the movement functions never think about it.

`step` has grown a second arity that takes the bindings as an argument.
The two-argument form is what the viewer uses; the three-argument form
exists for the next chapter, which keeps this `step` and this loop but
supplies its own map of keys.

Rendering draws the window starting at `:top`, and the screen's cursor
is the text cursor shifted up by `:top` rows. The column is clamped to
the screen's width because we do not scroll sideways (another
exercise):

```clojure
(defn render
  "Draw the lines from :top down and put the screen's cursor where the
   state's cursor is."
  [screen state]
  (let [{:keys [lines top cursor]} state
        [height width] (scr/dimensions screen)
        [row col] cursor]
    (scr/erase screen)
    (doseq [[i line] (map-indexed vector (take height (drop top lines)))]
      (scr/write-at screen i 0 line))
    (scr/move-cursor screen (- row top) (min col (dec width)))))

(defn run
  "Draw, read a key, step, and repeat until the state says it has quit
   or the keys run out. Returns the final state. The three-argument
   form takes the step function to use, so that programs built on the
   viewer can reuse the loop."
  ([screen state] (run step screen state))
  ([step-fn screen state]
   (loop [state state]
     (if (:quit state)
       state
       (do
         (render screen state)
         (if-let [key (scr/read-key screen)]
           (recur (step-fn state key))
           state))))))

(defn start
  "View `lines` on `screen`, sizing the state to fit the screen."
  [screen lines]
  (let [[height _] (scr/dimensions screen)]
    (run screen (make-state lines height))))
```

`start` is the only function that connects the two places a height can
come from: it asks the screen, and builds a state to match. Like `run`,
it works with any `Screen`, so the tests in
`test/sdx/viewer/core_test.clj` can drive the complete viewer with a
script and look at the result, both as a state and as a picture:

```clojure
(deftest scrolling-down-and-back
  (testing "the window moves only when the cursor would leave it"
    (is (= 0 (:top (after 3 ["j" "j"]))))
    (is (= 1 (:top (after 3 ["j" "j" "j"]))))
    (is (= 7 (:top (after 3 (repeat 20 "j"))))))
  (testing "moving up above the window pulls it back"
    (let [state (after 3 (concat (repeat 5 "j") (repeat 4 "k")))]
      (is (= [1 0] (:cursor state)))
      (is (= 1 (:top state))))))

(deftest a-scripted-session
  (let [screen (scr/headless 3 6 ["j" "j" "j" "j" :right "q" "k"])
        state (core/run screen (core/make-state lines 3))]
    (is (= [4 1] (:cursor state)))
    (is (= ["line 2" "line 3" "line 4"] (scr/rows screen)))
    (is (= [2 1] (scr/cursor screen)))
    (testing "the loop stopped at q"
      (is (= "k" (scr/read-key screen))))))
```

The second test is the payoff of the whole design. Four presses of `j`
on a three-row screen, then a right arrow: the test knows exactly which
three lines should be showing, where the cursor should be on the
screen, and that the key after `q` was never read. Nothing in it is
timing-dependent, nothing needs a terminal, and it runs in a
millisecond.

## The real terminal

Everything so far would work on any `Screen`. Now we need one that
works on an actual terminal, and this is the part of the chapter that
cannot be tested automatically, so it lives in its own namespace,
`sdx.viewer.ansi`, and it is deliberately small.

Terminals are controlled by **escape sequences**: short strings that
begin with the escape character (code 27) and `[`, and that the
terminal interprets as commands instead of printing. The three we need
are "move the cursor", "clear the screen", and a pair that switches to
an alternate screen and back, so that the user's shell is intact when
the viewer exits:

```clojure
(def ^:private esc
  "The Control Sequence Introducer that starts every command we send."
  "\u001b[")

(defn goto
  "The sequence that moves the terminal's cursor to `row`, `col`
   (0-based here; the terminal counts from 1)."
  [row col]
  (str esc (inc row) ";" (inc col) "H"))

(def clear-screen "Blank the whole screen." (str esc "2J"))
(def alt-screen-on "Switch to the alternate screen, saving the shell's." (str esc "?1049h"))
(def alt-screen-off "Switch back to the shell's screen." (str esc "?1049l"))
```

```clojure
(goto 2 10)   ;; => "\u001b[3;11H"
```

Keys come in the same way, in reverse: an arrow key arrives as the
bytes for escape, `[`, and a letter, and Page Down arrives as escape,
`[`, `6`, `~`. A plain letter arrives as its own byte, and a control
key arrives as a byte below 32. `decode-key` turns one of these into
the key values the rest of the program expects. It takes the byte that
was just read plus a function for fetching more bytes if the first one
starts an escape sequence:

```clojure
(def ^:private csi-letters
  "Final letters of the escape sequences for the keys we care about."
  {\A :up, \B :down, \C :right, \D :left, \H :home, \F :end})

(def ^:private csi-numbers
  "Numeric codes in sequences of the form ESC [ n ~."
  {1 :home, 3 :delete, 4 :end, 5 :page-up, 6 :page-down, 7 :home, 8 :end})

(defn- csi
  "Read the rest of an ESC [ sequence from `next-byte` and name the key."
  [next-byte]
  (loop [params []]
    (let [b (next-byte)]
      (cond
        (neg? b) :unknown
        (<= 0x30 b 0x3f) (recur (conj params (char b)))
        :else (let [final (char b)
                    n (parse-long (apply str params))]
                (or (csi-letters final)
                    (when (= final \~) (csi-numbers n))
                    :unknown))))))

(defn decode-key
  "Turn the byte `b` just read from the terminal into a key, pulling any
   further bytes of an escape sequence from `next-byte`, a function that
   returns the next pending byte or -1 if there is none."
  [b next-byte]
  (cond
    (neg? b) nil
    (= b 27) (let [b2 (next-byte)]
               (cond
                 (= b2 (int \[)) (csi next-byte)
                 (= b2 (int \O)) (get csi-letters (char (max 0 (next-byte))) :unknown)
                 :else :escape))
    (or (= b 13) (= b 10)) :enter
    (= b 9) :tab
    (or (= b 127) (= b 8)) :backspace
    (< b 32) (keyword (str "ctrl-" (char (+ b 96))))
    :else (str (char b))))
```

Taking `next-byte` as a function rather than reading from `System/in`
directly is what makes this decoder testable: a test hands it a
function that returns bytes from a vector. The same trick appears
throughout this book under different names, and it always has the same
shape: the thing that is hard to control is passed in instead of
reached for. A sequence we do not recognize decodes to `:unknown`
rather than `nil`, because `nil` means "no more input" and would stop
the viewer.

The screen itself is a record that holds a `Writer` to send escape
sequences to and an `InputStream` to read keys from:

```clojure
(defrecord Terminal [^java.io.Writer out ^java.io.InputStream in height width]
  scr/Screen
  (dimensions [_] [height width])
  (erase [_]
    (.write out ^String clear-screen))
  (write-at [_ row col text]
    (when (and (<= 0 row) (< row height) (<= 0 col) (< col width))
      (.write out ^String (str (goto row col) (scr/clip text (- width col))))))
  (move-cursor [_ row col]
    (.write out ^String (goto row col))
    (.flush out))
  (read-key [_]
    (decode-key (.read in)
                #(if (pos? (.available in)) (.read in) -1))))
```

This is our first real **Java interop**. `(.write out s)` calls the
`write` method of the Java object `out`, and `(.read in)` reads one
byte, returning -1 at end of input. The `^java.io.Writer` and
`^String` annotations are **type hints**: they tell the compiler which
class a value has so that it can pick the right overload of `write` at
compile time instead of searching for it by reflection on every call.
The program would work without them, slowly. `move-cursor` flushes
because `render` calls it last, so one flush per frame sends the whole
frame to the terminal at once.

The function passed to `decode-key` only reads a byte if one is already
waiting. That is how a bare press of the Escape key is told apart from
the start of an arrow sequence: the terminal sends an arrow's three
bytes together, so after the escape byte there is more pending, while a
lone Escape has nothing behind it.

Two things remain, and both are Unix-only. By default a terminal
collects a whole line before handing it to a program and echoes what is
typed; the viewer wants each key as it is pressed, silently. That is
**raw mode**, and the standard way to switch it on is the `stty`
command. We run it through a `ProcessBuilder`, redirecting its input
from `/dev/tty` so that it talks to the terminal even though our own
`System/in` is in use:

```clojure
(defn stty
  "Run `stty` with `args` against the controlling terminal and return
   whatever it prints, trimmed."
  [args]
  (let [proc (.start (ProcessBuilder. ["sh" "-c" (str "stty " args " < /dev/tty")]))
        out (slurp (.getInputStream proc))]
    (.waitFor proc)
    (str/trim out)))

(defn terminal-size
  "The controlling terminal's [height width], as reported by stty."
  []
  (let [[rows cols] (str/split (stty "size") #"\s+")]
    [(parse-long rows) (parse-long cols)]))

(defn with-terminal
  "Put the terminal into raw mode on the alternate screen, call `f`
   with a Terminal screen, and put everything back however `f` ends."
  [f]
  (let [saved (stty "-g")
        [height width] (terminal-size)
        out *out*
        screen (->Terminal out System/in height width)]
    (stty "raw -echo")
    (.write out ^String alt-screen-on)
    (.flush out)
    (try
      (f screen)
      (finally
        (.write out ^String alt-screen-off)
        (.flush out)
        (stty saved)))))
```

`with-terminal` is the one function that must not be skipped on the
way out. `stty -g` prints the terminal's current settings in a form
that `stty` can read back, so we save them before changing anything
and restore them in a `finally`, which runs whether `f` returns, throws,
or is interrupted. A terminal left in raw mode after a crash shows
nothing you type and ignores Enter, and the only cure is to type
`reset` blind. Chapter [9](../09-protocols/) explains why this kind of
setup-and-guaranteed-cleanup usually wants to be a macro; here a plain
function taking a function is enough because there is only one caller.

The entry point in `sdx.viewer.main` reads a file and hands it to
`core/start` inside `with-terminal`:

```clojure
(defn -main
  "View FILE in the terminal. Move with the arrow keys or h/j/k/l, page
   with Page Up and Page Down, and press q to quit."
  [& [path]]
  (if-not path
    (binding [*out* *err*]
      (println "usage: clojure -M -m sdx.viewer.main FILE"))
    (let [lines (str/split-lines (slurp path))]
      (ansi/with-terminal (fn [screen] (core/start screen lines)))
      nil)))
```

```sh
clojure -M -m sdx.viewer.main docs/23-viewer.md
```

Run that from a Unix terminal and you will see the start of this
chapter, with a cursor you can move; press `q` to come back. It will
not work on Windows, where there is no `stty` and no `/dev/tty`, and
it will not work from an IDE's output pane or under a test runner,
because neither is a terminal. Those are the limits of the thin layer,
and the reason it is thin.

What *can* be tested about it is more than you might expect, and
`test/sdx/viewer/ansi_test.clj` does it. Escape sequences are strings,
so `goto` has a test. `decode-key` takes a function, so it is tested
with bytes from a vector. And the `Terminal` record only needs a
`Writer` and an `InputStream`, so a `StringWriter` and a
`ByteArrayInputStream` let us check exactly which bytes it writes and
how it turns bytes into keys:

```clojure
(deftest terminal-writes-escape-codes
  (let [out (java.io.StringWriter.)
        in (java.io.ByteArrayInputStream. (.getBytes "j\u001b[Bq" "UTF-8"))
        term (ansi/->Terminal out in 3 10)]
    (is (= [3 10] (scr/dimensions term)))
    (scr/erase term)
    (scr/write-at term 1 2 "hello world")
    (scr/write-at term 5 0 "off screen")
    (scr/move-cursor term 1 4)
    (is (= (str "\u001b[2J" "\u001b[2;3Hhello wo" "\u001b[2;5H") (str out)))
    (testing "keys come from the input stream"
      (is (= "j" (scr/read-key term)))
      (is (= :down (scr/read-key term)))
      (is (= "q" (scr/read-key term)))
      (is (nil? (scr/read-key term))))))
```

The only code without a test is `stty`, `terminal-size`, and
`with-terminal`: about twenty lines, each of which does one obvious
thing. That is the shape to aim for whenever a program has to touch
something that tests cannot reach. Push the logic inward until the
untestable part is too simple to be wrong, or at least too simple to be
wrong in an interesting way.

## Summary

- Put a protocol between a program and any device it uses. A headless
  implementation that records what was done turns an interactive
  program into one that ordinary tests can drive.
- Keep the model pure: an immutable state map, a `step` function from
  state and key to state, and a `render` function from state to
  drawing. Pressing a sequence of keys is then a `reduce`.
- Redraw everything from the state every time. It costs a little and
  removes an entire class of bugs.
- Represent keys as plain values that both the real and the fake
  screen produce, so that a test can script a session as a vector.
- Bindings are data: a map from keys to functions can be inspected,
  tested, and extended by other programs.
- Enforce an invariant, such as "the cursor is always visible", in one
  function that runs after every action, not in every action.
- Pass hard-to-control things in (a byte source, a writer, a height)
  instead of reaching for them, and keep the code that must touch the
  real device so small that it cannot be wrong in interesting ways.

## Exercises

1. **Goal column.** Moving from a long line through a short one and
   back loses the column. Add a `:goal` column to the state that is set
   by horizontal moves and used by vertical ones, and write the test
   from stage 2 that currently documents the loss so that it passes
   with the column restored.

2. **Home and end.** `decode-key` already produces `:home` and `:end`.
   Bind them to functions that move the cursor to the start and end of
   the current line, and add `"g"` and `"G"` to jump to the first and
   last line of the file.

3. **Horizontal scrolling.** Lines longer than the screen are clipped
   and the cursor is pinned to the last column. Add a `:left` offset to
   the state, a `scroll-to-cursor` rule for it, and make `render` draw
   each line from that offset.

4. **A status line.** Reserve the bottom row of the screen for the file
   name and the cursor position, such as `notes.txt  12/40`. Where does
   the height that `make-state` receives have to change, and what else
   does?

5. **Only redraw what changed.** Make `render` compare the rows it is
   about to write with the rows it wrote last time and skip the ones
   that are the same. Where will it keep the previous rows, and what
   does that do to the claim that the picture is a function of the
   state alone?

6. **Resizing.** When a terminal window is resized, the shell sends the
   process a `SIGWINCH` signal that the JVM does not expose. Instead,
   make the viewer re-read `terminal-size` every time it receives a
   key, and compare the result with `:height`. What has to happen to
   `:top` when the screen gets shorter?

7. **A screen made of screens.** Write a `Screen` implementation that
   wraps another screen and logs every call to it as data. Use it to
   write a test that checks `render` writes each row exactly once per
   frame.

8. **Searching.** Add a `/` key that reads characters until Enter
   (using `read-key` inside the action) and then moves the cursor to
   the next line containing the text. What does this do to the claim
   that `step` is a pure function, and how could you keep it one?

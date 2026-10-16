---
title: "Undo and Redo"
nav_order: 24
lede: "The viewer becomes an editor, and we build undo twice: once by reversing actions and once by keeping the past."
---

Undo is the feature users rely on most and the one that programs get
last and worst. It is hard to add late because it needs every change
to the document to go through a place where it can be noticed, and
programs that grew without that place have changes scattered
everywhere. So this chapter does things in a deliberate order. It
first turns the [file viewer](../23-viewer/) of the previous chapter
into a small editor in which every change is an **action**: a map that
says what is to be done. Then it adds undo and redo in two different
ways, one that records the actions and reverses them, and one that
records the states and goes back to them, and ends by comparing the
two.

Everything from the previous chapter is reused as it stands: the
`Screen` protocol and its headless implementation, the viewer's state
map, its `scroll-to-cursor` invariant, its `step` and `run`. The
editor only adds keys to the bindings and a way of changing the lines.
That reuse is the first test of the previous chapter's design, and it
passes: no function in `sdx.viewer.core` needs to change.

## Actions as data

`sdx.undo.edit` starts with a state. It is the viewer's state, with one
difference: an editor on an empty file needs a line to type on, so the
state always has at least one:

```clojure
(defn make-state
  "An editor state: the viewer's state over at least one line, so that
   there is always somewhere to type."
  [lines height]
  (core/make-state (if (seq lines) lines [""]) height))
```

Now the central decision. When the user presses a key that changes the
text, the editor does not change the text. It builds a small map
describing the change, such as `{:op :insert :row 2 :col 5 :char
"x"}`, and hands that map to a function that performs it. There are
four kinds of change, and `apply-action` chooses what to do by the
action's `:op`:

```clojure
(defmulti apply-action
  "Perform `action` on `state`. Returns the new state with the cursor
   where the change ended."
  (fn [_state action] (:op action)))
```

`apply-action` is a **multimethod**, which [the chapter on
objects](../02-objects/) introduced: a function that picks its
implementation by calling a dispatch function on its arguments. The
dispatch function here takes both arguments and returns the action's
`:op`, so each `defmethod` below handles one kind of action. A `case`
would have done the job in one function; the multimethod lets a later
namespace, or an exercise, add a fifth kind of action without editing
this one.

Two private helpers do the string and vector surgery. `splice` is
the string version of "replace this range", and `replace-lines`
swaps some lines for others:

```clojure
(defn- splice
  "The string `s` with the characters from `from` (inclusive) to `to`
   (exclusive) replaced by `middle`."
  [s from to middle]
  (str (subs s 0 from) middle (subs s to)))

(defn- replace-lines
  "The vector `lines` with `n` lines starting at `row` replaced by `with`."
  [lines row n with]
  (-> (subvec lines 0 row)
      (into with)
      (into (subvec lines (+ row n)))))
```

With those, each kind of action is a few lines. Inserting and deleting
a character work on one line; splitting and joining work on two:

```clojure
(defmethod apply-action :insert
  [state {:keys [row col char]}]
  (-> state
      (update-in [:lines row] splice col col char)
      (assoc :cursor [row (inc col)])))

(defmethod apply-action :delete
  [state {:keys [row col]}]
  (-> state
      (update-in [:lines row] splice col (inc col) "")
      (assoc :cursor [row col])))

(defmethod apply-action :split
  [state {:keys [row col]}]
  (let [line (get-in state [:lines row])]
    (-> state
        (update :lines replace-lines row 1 [(subs line 0 col) (subs line col)])
        (assoc :cursor [(inc row) 0]))))

(defmethod apply-action :join
  [state {:keys [row col]}]
  (let [lines (:lines state)]
    (-> state
        (update :lines replace-lines row 2 [(str (lines row) (lines (inc row)))])
        (assoc :cursor [row col]))))
```

```clojure
(apply-action {:lines ["abc" "de"] :cursor [0 0]} {:op :split :row 0 :col 1})
;; => {:lines ["a" "bc" "de"], :cursor [1 0]}
```

Every action puts the cursor where a user would expect it after the
change: after an inserted character, at the start of the new line
after a split, at the seam after a join. That is part of what the
action means, and keeping it inside `apply-action` means undo will get
the cursor right for free later.

Look closely at what the actions carry. `:delete` has a `:char` field,
although deleting does not need to know which character it is
removing. `:join` has a `:col` field, although joining two lines does
not need to know how long the first one was. They are there because an
action should be a *complete* description of a change, one that can be
understood without the state it was made in. "Delete the `b` at row 0,
column 1" means something on its own; "delete whatever is at row 0,
column 1" means something only in context. The cost is a few extra
bytes per action. The benefit arrives in the next section.

The test file `test/sdx/undo/edit_test.clj` checks each action with a
table, which is the natural shape for a function that takes data and
returns data:

```clojure
(deftest applying-actions
  (doseq [[action expected-lines expected-cursor]
          [[{:op :insert :row 0 :col 1 :char "X"} ["aXbc" "de"] [0 2]]
           [{:op :insert :row 1 :col 2 :char "!"} ["abc" "de!"] [1 3]]
           [{:op :delete :row 0 :col 1 :char "b"} ["ac" "de"] [0 1]]
           [{:op :split :row 0 :col 1} ["a" "bc" "de"] [1 0]]
           [{:op :split :row 1 :col 2} ["abc" "de" ""] [2 0]]
           [{:op :join :row 0 :col 3} ["abcde"] [0 3]]]]
    (testing (pr-str action)
      (let [result (edit/apply-action (state 0 0) action)]
        (is (= expected-lines (:lines result)))
        (is (= expected-cursor (:cursor result)))))))
```

## From keys to actions

The other half of editing is deciding which action a key stands for.
A printable character inserts itself at the cursor; Enter splits the
line; Backspace deletes the character before the cursor, or, at the
start of a line, joins it to the one above:

```clojure
(defn- printable?
  "True for a key that stands for a character the user typed."
  [key]
  (and (string? key)
       (= 1 (count key))
       (<= 32 (int (first key)))))

(defn key->action
  "The action `key` stands for in `state`, or nil if the key does not
   edit the text. Each action carries everything needed to describe
   the change on its own: the character a delete removes, the column a
   join happens at."
  [state key]
  (let [{:keys [lines cursor]} state
        [row col] cursor]
    (cond
      (printable? key)
      {:op :insert :row row :col col :char key}

      (= key :enter)
      {:op :split :row row :col col}

      (and (= key :backspace) (pos? col))
      {:op :delete :row row :col (dec col) :char (subs (lines row) (dec col) col)}

      (and (= key :backspace) (pos? row))
      {:op :join :row (dec row) :col (count (lines (dec row)))}

      :else nil)))
```

`key->action` reads the state but never changes it. This is where the
`:char` of a delete and the `:col` of a join are looked up, at the only
moment they are cheap to find. Separating "decide what to do" from "do
it" also gives us two small functions to test instead of one tangled
one. The test for this function is a list of keys and the actions they
should produce:

```clojure
(deftest keys-become-actions
  (is (= {:op :insert :row 0 :col 2 :char "z"} (edit/key->action (state 0 2) "z")))
  (is (= {:op :split :row 1 :col 1} (edit/key->action (state 1 1) :enter)))
  (testing "backspace deletes the character before the cursor"
    (is (= {:op :delete :row 0 :col 1 :char "b"} (edit/key->action (state 0 2) :backspace))))
  (testing "backspace at the start of a line joins it to the one above"
    (is (= {:op :join :row 0 :col 3} (edit/key->action (state 1 0) :backspace))))
  (testing "nothing to delete at the very start"
    (is (nil? (edit/key->action (state 0 0) :backspace))))
  (testing "movement and control keys are not edits"
    (is (nil? (edit/key->action (state 0 0) :down)))
    (is (nil? (edit/key->action (state 0 0) :ctrl-q)))))
```

Now to wire the editor into the viewer's loop. The viewer bound `h`,
`j`, `k`, `l`, and `q` to movement and quitting. In an editor those
letters are text, so the editor's bindings are the viewer's with every
string key removed, plus Ctrl-Q to quit:

```clojure
(def bindings
  "The viewer's bindings for every key that is not a letter, plus
   ctrl-q to quit. Letters are text now, so h/j/k/l and q go."
  (into {:ctrl-q core/quit}
        (remove (fn [[k _]] (string? k)))
        core/bindings))
```

This is the payoff of making bindings a map rather than a `case`: a
different program can take them apart and put them back together.
`(into to xform from)` pours the entries of `from` into `to` through
the **transducer** `xform`, here a `remove` that drops every entry
whose key is a string; it is `(into to (remove pred from))` without
building the intermediate sequence. The arrow keys, page keys, and
Ctrl-C survive, and so do the functions they are bound to, which are
the viewer's own.

`step` tries the editing keys first and falls back to the viewer's
`step`, with the viewer's three-argument form taking the editor's
bindings. One more thing is threaded through: `perform`, the function
that applies an action. Stage 1 passes `apply-action` itself, and the
two stages that follow pass functions that apply the action *and*
remember it:

```clojure
(defn step
  "The state after one key: build and perform an action if the key
   edits, otherwise let the viewer handle it. `perform` is how an
   action is applied; later stages pass one that also remembers it."
  ([state key] (step apply-action bindings state key))
  ([perform bindings state key]
   (if-let [action (key->action state key)]
     (core/scroll-to-cursor (perform state action))
     (core/step bindings state key))))

(defn run
  "Edit on `screen` until ctrl-q. Returns the final state."
  [screen state]
  (core/run step screen state))
```

An edit is followed by `scroll-to-cursor` just as a movement is, so
pressing Enter at the bottom of the screen scrolls, with no new code:
the invariant from the previous chapter holds for a kind of state
change it was never written for. The scripted test checks a whole
session, including that typing past the bottom scrolls:

```clojure
(deftest a-scripted-session
  (let [keys ["h" "i" :enter "t" "h" "e" "r" "e" :backspace :backspace :up :right :right "!" :ctrl-q]
        screen (scr/headless 3 6 keys)
        final (edit/run screen (edit/make-state [] 3))]
    (is (= ["hi!" "the"] (:lines final)))
    (is (= [0 3] (:cursor final)))
    (is (= ["hi!   " "the   " "      "] (scr/rows screen)))
    (is (= [0 3] (scr/cursor screen)))))

(deftest typing-past-the-bottom-scrolls
  (let [screen (scr/headless 2 4 [:right :enter :enter :enter "x" :ctrl-q])
        final (edit/run screen (edit/make-state ["a"] 2))]
    (is (= ["a" "" "" "x"] (:lines final)))
    (is (= 2 (:top final)))
    (is (= ["    " "x   "] (scr/rows screen)))))
```

We have an editor. It cannot undo anything, but every change it makes
passes through `perform` as a map, and that is all the next two
sections need.

## Undo by inverting actions

The first design, in `sdx.undo.history`, remembers what was done. The
state gains two vectors: `:done`, the actions applied so far, and
`:undone`, the actions that have been taken back and could be redone:

```clojure
(defn make-state
  "An editor state with an empty history."
  [lines height]
  (assoc (edit/make-state lines height) :done [] :undone []))
```

Undoing an action means applying the action that reverses it. Because
our actions were designed to be complete descriptions, every one of
them has a reverse that is also one of our actions. Inserting a
character is undone by deleting it, and the delete needs the row and
column, which the insert has. Deleting a character is undone by
inserting it, which needs the character, and that is why `:delete`
carries `:char`. A split is undone by a join at the same row and
column, and a join by a split, which is why `:join` carries `:col`.
Every field of every action is there for one direction or the other.
So inversion is a lookup:

```clojure
(def ^:private inverse-op
  "Each operation and the one that reverses it."
  {:insert :delete, :delete :insert, :split :join, :join :split})

(defn invert
  "The action that exactly reverses `action`."
  [action]
  (update action :op inverse-op))
```

```clojure
(invert {:op :delete :row 0 :col 1 :char "b"})
;; => {:op :insert, :row 0, :col 1, :char "b"}
```

`update` calls a function on the value under a key, and a map is a
function of its keys, so `(update action :op inverse-op)` replaces the
`:op` by its inverse. Inverting twice gives the original action back,
which a test checks below.

`perform` applies an action and records it. `undo` applies the inverse
of the most recent action and moves it to the redo list; `redo` moves
it back:

```clojure
(defn perform
  "Apply `action` and remember it. Anything that had been undone can no
   longer be redone, so that list is cleared."
  [state action]
  (-> (edit/apply-action state action)
      (update :done conj action)
      (assoc :undone [])))

(defn undo
  "Reverse the most recent action, if there is one."
  [state]
  (if-let [action (peek (:done state))]
    (-> (edit/apply-action state (invert action))
        (update :done pop)
        (update :undone conj action))
    state))

(defn redo
  "Apply the most recently undone action again, if there is one."
  [state]
  (if-let [action (peek (:undone state))]
    (-> (edit/apply-action state action)
        (update :undone pop)
        (update :done conj action))
    state))
```

Both vectors are used as stacks. On a vector, `conj` adds at the end,
`peek` returns the last element, and `pop` returns the vector without
it, all in constant time, so a vector is Clojure's stack. (On a list
the same three functions work at the front, which is why we say "the
vector's `peek`" and not just "the end".) When there is nothing to
undo, `peek` of an empty vector is `nil`, `if-let` takes the other
branch, and the state is returned untouched, which is what a user
pressing Ctrl-Z one time too many expects.

`perform` clears `:undone`. Suppose the user types `a`, undoes it, and
types `b`. If the undone `a` were still redoable, redo would insert it
after the `b`, in a place it was never typed. Clearing the list keeps
history linear: the sequence of actions in `:done`, applied in order to
the original text, always gives the current text, and `:undone` is
only ever a tail of actions that were cut from the end of that
sequence. Editors that keep the cut tails around and let the user
return to them have "undo trees", and the exercises ask for one.

The remaining code wires undo and redo to Ctrl-Z and Ctrl-Y, and
passes `perform` to the editor's `step`:

```clojure
(def bindings
  "The editor's bindings plus ctrl-z to undo and ctrl-y to redo."
  (assoc edit/bindings :ctrl-z undo :ctrl-y redo))

(defn step
  "The state after one key, with every edit recorded."
  [state key]
  (edit/step perform bindings state key))

(defn run
  "Edit on `screen` until ctrl-q. Returns the final state."
  [screen state]
  (core/run step screen state))
```

Undo and redo go through the viewer's `step`, so `scroll-to-cursor`
runs after them too: undoing a change made far from the current
position scrolls back to where it happened.

The tests in `test/sdx/undo/history_test.clj` begin with the property
that makes the design work, for every kind of action: applying an
action and then its inverse gives back the original text. If a new
kind of action is ever added, this test is the one that catches a
wrong or incomplete inverse:

```clojure
(deftest inverting-actions
  (let [actions [{:op :insert :row 0 :col 1 :char "X"}
                 {:op :delete :row 0 :col 1 :char "b"}
                 {:op :split :row 0 :col 1}
                 {:op :join :row 0 :col 3}]]
    (doseq [action actions]
      (testing (pr-str action)
        (is (= action (hist/invert (hist/invert action)))
            "inverting twice gives the action back")
        (let [start (hist/make-state lines 5)
              done (edit/apply-action start action)
              undone (edit/apply-action done (hist/invert action))]
          (is (= lines (:lines undone))
              "applying an action and then its inverse restores the text"))))))
```

The rest script keys, including the case that is easiest to get wrong
by hand, a join followed by an undo, which must bring back both lines
and put the cursor at the start of the second:

```clojure
(deftest undoing-a-join-restores-both-lines
  (let [state (after [:down :backspace])]
    (is (= ["abcde"] (:lines state)))
    (let [back (hist/undo state)]
      (is (= ["abc" "de"] (:lines back)))
      (is (= [1 0] (:cursor back))))))

(deftest a-scripted-session
  (let [keys ["h" "i" :enter "!" :ctrl-z :ctrl-z :ctrl-z :ctrl-y "?" :ctrl-q]
        screen (scr/headless 3 5 keys)
        final (hist/run screen (hist/make-state [] 3))]
    (testing "three undos leave \"h\", the redo brings \"i\" back, then ? is typed"
      (is (= ["hi?"] (:lines final))))
    (is (= ["hi?  " "     " "     "] (scr/rows screen)))
    (is (= [0 3] (scr/cursor screen)))))
```

## Undo by remembering states

The second design, in `sdx.undo.snapshot`, does not record what was
done. It records what things *were*. Before every change it saves a
**snapshot** of the parts of the state that a change can affect, and
undo simply restores the most recent one:

```clojure
(defn make-state
  "An editor state with no past and no future."
  [lines height]
  (assoc (edit/make-state lines height) :past [] :future []))

(defn- snapshot
  "The parts of `state` that an edit can change."
  [state]
  (select-keys state [:lines :cursor]))

(defn perform
  "Apply `action`, keeping a snapshot of what things were like before."
  [state action]
  (-> (edit/apply-action state action)
      (update :past conj (snapshot state))
      (assoc :future [])))
```

A snapshot is the lines and the cursor but not `:top` or `:height`:
undo should put the cursor back where the change was made, and
`scroll-to-cursor` will then bring that place onto the screen,
wherever the window happens to be now. Deciding what belongs in a
snapshot is the only design question this approach has, and it is a
real one. Too little, and undo leaves something changed; too much, and
undo drags back things the user did not think of as part of the
document, like which line was at the top of the screen.

Undo and redo are now the same operation in opposite directions: take
a snapshot off one stack, push the present onto the other, and restore
the snapshot:

```clojure
(defn- travel
  "Step along the history: restore the snapshot on top of `from` and
   push a snapshot of the present onto `to`."
  [state from to]
  (if-let [snap (peek (get state from))]
    (-> state
        (merge snap)
        (update from pop)
        (update to conj (snapshot state)))
    state))

(defn undo
  "Go back to the state before the most recent change."
  [state]
  (travel state :past :future))

(defn redo
  "Go forward to the state the most recent undo left."
  [state]
  (travel state :future :past))
```

There is a subtlety in `travel` worth pausing on. Inside the `->`
form, `(snapshot state)` takes a snapshot of the *present*, not of the
half-restored value being threaded, because the threading macro only
rewrites the first argument of each form; `state` in the last line is
still the function's parameter. That is exactly what we want, since
the snapshot pushed onto the other stack must be the state before the
restore, but it is the kind of line to read twice.

Everything else is the same as the first design, with the editor's
`step` given this namespace's `perform`:

```clojure
(def bindings
  "The editor's bindings plus ctrl-z to undo and ctrl-y to redo."
  (assoc edit/bindings :ctrl-z undo :ctrl-y redo))

(defn step
  "The state after one key, with a snapshot taken before every edit."
  [state key]
  (edit/step perform bindings state key))

(defn run
  "Edit on `screen` until ctrl-q. Returns the final state."
  [screen state]
  (core/run step screen state))
```

Anyone who has written an editor in a language with mutable strings
and arrays will object at this point. Saving the whole document before
every keystroke is absurd: a megabyte of text copied a thousand times
is a gigabyte. The objection is right in those languages and wrong in
this one, and the reason is **structural sharing**. A Clojure vector
is immutable, so `assoc` on it does not change it; it returns a new
vector. But the new vector does not copy the old one. It is a tree,
and `assoc` copies only the handful of tree nodes on the path from the
root to the changed element, sharing every other node with the old
vector. The strings in the vector are immutable too, so they are never
copied at all. After inserting one character into a thousand-line
document, the new `:lines` and the old `:lines` have nine hundred and
ninety-nine lines in common, and "in common" means the very same
objects, not equal copies. A test can check that with `identical?`,
which is true only for the same object in memory:

```clojure
(deftest snapshots-share-unchanged-lines
  (let [big (vec (map #(str "line " %) (range 1000)))
        before (snap/make-state big 5)
        after (snap/perform before {:op :insert :row 0 :col 0 :char "x"})
        saved (:lines (peek (:past after)))]
    (is (= "xline 0" (get-in after [:lines 0])))
    (is (= "line 0" (saved 0)))
    (testing "every line but the edited one is the very same string object"
      (is (identical? (saved 1) (get-in after [:lines 1])))
      (is (identical? (saved 999) (get-in after [:lines 999]))))))
```

So a snapshot costs the new line plus a few tree nodes, a few hundred
bytes, and a thousand keystrokes cost a few hundred kilobytes. The
immutability that this book has been using for safety and clarity
turns out to make an entire design affordable.

Since we now have two implementations of the same behaviour, each can
serve as the oracle for the other. The last test in
`test/sdx/undo/snapshot_test.clj` runs several scripts through both and
demands the same text and cursor from each:

```clojure
(deftest the-two-designs-agree
  (let [scripts [["x" "y" :ctrl-z]
                 ["a" :enter "b" :ctrl-z :ctrl-z :ctrl-y]
                 [:down :backspace :ctrl-z]
                 [:right :right :backspace :ctrl-z :ctrl-y "q"]
                 [:ctrl-z :ctrl-y "z" :ctrl-z :ctrl-z :ctrl-y :ctrl-y]]]
    (doseq [keys scripts]
      (testing (pr-str keys)
        (let [by-history (reduce hist/step (hist/make-state lines 5) keys)
              by-snapshot (reduce snap/step (snap/make-state lines 5) keys)]
          (is (= (:lines by-history) (:lines by-snapshot)))
          (is (= (:cursor by-history) (:cursor by-snapshot))))))))
```

## Comparing the two

Both designs pass the same tests, so the choice between them is about
everything other than whether they work.

The inverse-action design asks more of the programmer. Every kind of
change needs an inverse, every inverse must be exactly right, and every
action must carry whatever its inverse will need. A mistake does not
show up when the change is made; it shows up when the change is undone,
possibly much later and far away, as a document that is subtly not what
it was. The property test above is the defence, and it has to be
extended for every new action. The snapshot design asks almost
nothing: `travel` is correct for any change whatsoever, including
changes nobody has thought of yet, because it never needs to know what
changed.

In exchange, the action history *means* something. It is a list of
what the user did, in order, and that list can be printed, saved to
disk and replayed to recover a document after a crash, sent to another
machine so that two people can edit together, or examined to answer
"what did I change in the last ten minutes?". A list of snapshots
answers none of those questions without being diffed. The action
design is also the one that generalizes to changes outside the
document: an action can say "set the font to 12 point", and the
snapshot design can only undo it if the font is part of the snapshot.

Memory favours snapshots in Clojure, as we saw, and favours actions in
languages where saving a state means copying it. That is the reason
nearly every editor you have used takes the first approach, and the
reason it is worth knowing that the second one exists.

These two designs have names in the pattern literature. Representing
an operation as a value that can be stored, inverted, and replayed is
the **command** pattern; capturing an object's state so that it can be
restored later is the **memento** pattern. Both were described for
languages with mutable objects, where the memento in particular is
expensive and awkward, which is partly why the command pattern is the
famous one. In a language with immutable values, a memento is a
reference, and the balance shifts.

## Running it

`sdx.undo.main` runs the snapshot editor on a file in the terminal,
using the previous chapter's `with-terminal`, and writes the file back
only if it changed:

```clojure
(defn -main
  "Edit FILE in the terminal. Type to insert, Backspace to delete, Enter
   to split a line, arrows to move, ctrl-z to undo, ctrl-y to redo and
   ctrl-q to quit. The file is written back only if it changed."
  [& [path]]
  (if-not path
    (binding [*out* *err*]
      (println "usage: clojure -M -m sdx.undo.main FILE"))
    (let [original (read-lines path)
          final (ansi/with-terminal
                  (fn [screen]
                    (let [[height _] (scr/dimensions screen)]
                      (snap/run screen (snap/make-state original height)))))]
      (when (not= original (:lines final))
        (spit path (str (str/join "\n" (:lines final)) "\n"))
        (println "wrote" path))
      nil)))
```

```sh
clojure -M -m sdx.undo.main notes.txt
```

As in the previous chapter, this needs a Unix terminal. Swapping
`snap` for `hist` in the two places it appears gives the other editor,
and nothing visible changes, which is the point.

## Summary

- Make every change to a document pass through one place as a value
  that describes it. Nothing else about undo is possible without this,
  and it is hard to retrofit.
- An action should be a complete description of a change, carrying
  what its reverse will need even when the forward direction does not.
- Undo by inverse actions keeps a stack of what was done and a stack of
  what was undone; a new action clears the redo stack to keep history
  linear.
- Undo by snapshots keeps past states and restores them; it is correct
  for any change without extra code, but the history does not say what
  happened.
- Persistent data structures share structure: a changed vector shares
  all but a few nodes with the original, so keeping old versions is
  cheap. Immutability changes which designs are affordable.
- When two implementations must behave the same, test them against
  each other.
- Vectors are stacks: `conj`, `peek`, and `pop` work at the end in
  constant time.

## Exercises

1. **Delete forward.** `decode-key` already produces `:delete` for the
   Delete key. Add it to `key->action` so that it removes the
   character under the cursor, or joins the next line when the cursor
   is at the end of a line. Does the inverse-action design need any
   new code? Does the snapshot design?

2. **Undo by words.** Pressing Ctrl-Z after typing a sentence removes
   one character. Change `perform` in `sdx.undo.history` to merge a
   run of single-character inserts on the same line into one action
   with a multi-character `:char`, so that undo removes the whole word.
   What has to change in `apply-action` and in `invert`?

3. **The same for snapshots.** Do the previous exercise for
   `sdx.undo.snapshot`. Which design made it easier, and why?

4. **Undo tree.** Instead of clearing `:undone` when a new action is
   performed, keep the abandoned branch. Design a representation for
   the history as a tree and a pair of keys for moving between
   branches.

5. **Replay.** Write a function that takes the original lines and a
   `:done` vector and reproduces the current lines by applying the
   actions in order. Add a test to `history_test.clj` that checks this
   holds after every scripted session. Why can this test not be
   written for the snapshot design?

6. **Save the log.** Append each action to a file as EDN as it is
   performed, and write a `recover` function that rebuilds a document
   from the original file plus the log. What should happen to the log
   when the user undoes?

7. **Measure it.** Make a 100,000-line document, type 1,000
   characters into it using `reduce` and `step`, and use
   `(System/nanoTime)` to time both designs. Then measure the memory
   each history holds by walking the snapshots with `identical?` and
   counting distinct strings. Does the result match the chapter's
   claim?

8. **Snapshots of the window.** Add `:top` to the snapshot and run the
   editor. Describe what happens when you make a change, scroll far
   away, and press Ctrl-Z. Which behaviour do you prefer, and would a
   user agree?

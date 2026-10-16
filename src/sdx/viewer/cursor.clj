(ns sdx.viewer.cursor
  "Stage 2: a cursor the user can move around the text.

   The state gains a :cursor, each key is looked up in a map of
   bindings to find the function that handles it, and `step` applies
   that function. None of this touches a screen: the model is a pure
   function of the keys it has seen."
  (:require [sdx.viewer.screen :as scr]))

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

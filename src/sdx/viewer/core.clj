(ns sdx.viewer.core
  "Stage 3: the whole viewer. The state remembers which line is at the
   top of the screen (:top) and how tall the screen is (:height), so
   that the text can be longer than the screen. Every key goes through
   `step`, which does what the key asks and then scrolls just enough to
   keep the cursor visible. Nothing here knows what a terminal is."
  (:require [sdx.viewer.screen :as scr]))

(defn make-state
  "The viewer's starting state for `lines` on a screen `height` rows tall."
  [lines height]
  {:lines (vec lines) :cursor [0 0] :top 0 :height height})

;; ---------------------------------------------------------------------
;; Moving the cursor and the window.

(defn- clamp
  "`x`, pulled back into the range `lo`..`hi` if it is outside it."
  [x lo hi]
  (-> x (max lo) (min hi)))

(defn move
  "Move the cursor by `dr` rows and `dc` columns without leaving the
   text. The column may rest just past the last character of a line."
  [state dr dc]
  (let [{:keys [lines cursor]} state
        [row col] cursor
        row (clamp (+ row dr) 0 (max 0 (dec (count lines))))
        col (clamp (+ col dc) 0 (count (nth lines row "")))]
    (assoc state :cursor [row col])))

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

;; ---------------------------------------------------------------------
;; Keeping the cursor on the screen.

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

;; ---------------------------------------------------------------------
;; Drawing and the main loop.

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

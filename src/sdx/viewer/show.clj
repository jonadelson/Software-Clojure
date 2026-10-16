(ns sdx.viewer.show
  "Stage 1: put the first screenful of a file on the screen and wait
   for the user to press q. Nothing moves yet, but the shape of the
   program is already here: a state, a render function that draws it,
   and a loop that reads keys."
  (:require [sdx.viewer.screen :as scr]))

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

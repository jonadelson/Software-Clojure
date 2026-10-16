(ns sdx.layout.render
  "Phase 3 of layout: drawing placed blocks onto a grid of characters.

   The grid is a vector of rows, each a vector of characters, so that it
   can be built without mutation and compared with = in tests. Leaves
   draw themselves as a rectangle of their :fill character; rows and
   columns draw nothing of their own, so any space inside them that no
   leaf covers stays blank."
  (:require [sdx.layout.place :as pl]))

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

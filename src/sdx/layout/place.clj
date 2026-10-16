(ns sdx.layout.place
  "Phase 2 of layout: placing blocks from the top down.

   Every block gets an :x and :y for its top-left corner. A row's
   children follow one another from left to right along the row's top
   edge; a column's children follow one another downward along its left
   edge."
  (:require [sdx.layout.size :as s]))

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

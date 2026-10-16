(ns sdx.layout.wrap
  "Wrapping: a row that is wider than the page becomes a column of rows.

   Wrapping happens before sizing and placing, and produces an ordinary
   block tree, so the three phases of layout do not know it exists."
  (:require [sdx.layout.size :as s]))

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

(ns sdx.layout.size
  "Blocks, and the first phase of layout: computing sizes from the bottom up.

   A block is a map with a :type. A :leaf has a fixed :width and :height
   and a :fill character it is drawn with. A :row lays its :children out
   side by side; a :col stacks them top to bottom.")

;; ---------------------------------------------------------------------
;; Constructors.

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

;; ---------------------------------------------------------------------
;; Phase 1: sizes, computed bottom-up.

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

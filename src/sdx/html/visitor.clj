(ns sdx.html.visitor
  "A generic depth-first walk over an HTML tree.

   The walk threads an accumulator through three optional callbacks:
   :enter is called when an element is reached, :exit after all of its
   children have been visited, and :text for each string. Every callback
   takes (acc node path) and returns the new accumulator, where path is
   the vector of tags from the top of the document down to the element.")

(defn- call
  "Apply callback `f` if there is one, otherwise leave `acc` alone."
  [f acc node path]
  (if f (f acc node path) acc))

(defn walk
  "Visit every node under `tree`, calling the `callbacks` in document
   order and returning the final accumulator. The :document root is a
   container, not an element, so callbacks never see it."
  ([tree callbacks init]
   (walk tree callbacks init []))
  ([node {:keys [enter exit text] :as callbacks} acc path]
   (cond
     (string? node)
     (call text acc node path)

     (= :document (:tag node))
     (reduce (fn [acc child] (walk child callbacks acc path))
             acc
             (:children node))

     :else
     (let [path (conj path (:tag node))
           acc (call enter acc node path)
           acc (reduce (fn [acc child] (walk child callbacks acc path))
                       acc
                       (:children node))]
       (call exit acc node path)))))

;; ---------------------------------------------------------------------
;; Small tools built on the walk.

(defn tag-counts
  "How many times each tag appears in `tree`."
  [tree]
  (walk tree
        {:enter (fn [counts node _] (update counts (:tag node) (fnil inc 0)))}
        {}))

(defn text-of
  "All the text in `tree`, in document order, joined into one string."
  [tree]
  (walk tree
        {:text (fn [pieces s _] (conj pieces s))}
        []))

(defn max-depth
  "The length of the longest path from the top of the document to an element."
  [tree]
  (walk tree
        {:enter (fn [deepest _ path] (max deepest (count path)))}
        0))

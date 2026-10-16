(ns sdx.protocols.seqs
  "Making our own types behave like sequences, and building sequences
   by hand.

   Every collection function in Clojure starts by calling `seq` on its
   argument. Anything that can answer that call gets map, filter,
   reduce, and the rest for free.")

;; ---------------------------------------------------------------------
;; Lazy sequences built by hand.

(defn countdown
  "The numbers from `n` down to 1, produced one at a time as they are asked for."
  [n]
  (lazy-seq
    (when (pos? n)
      (cons n (countdown (dec n))))))

(defn multiples
  "Every positive multiple of `k`, in order. This sequence never ends."
  ([k] (multiples k k))
  ([k current]
   (lazy-seq
     (cons current (multiples k (+ current k))))))

;; ---------------------------------------------------------------------
;; An iterator: the imperative cousin of a seq.

(defn iterator
  "A java.util.Iterator over `coll`: a cursor that remembers how far
   through the collection it has got."
  [coll]
  (let [remaining (atom (seq coll))]
    (reify java.util.Iterator
      (hasNext [_]
        (boolean @remaining))
      (next [_]
        (if-let [s @remaining]
          (do (reset! remaining (next s))
              (first s))
          (throw (java.util.NoSuchElementException.)))))))

(defn iterator->seq
  "A lazy seq that pulls elements from iterator `it` as they are needed."
  [^java.util.Iterator it]
  (lazy-seq
    (when (.hasNext it)
      (cons (.next it) (iterator->seq it)))))

;; ---------------------------------------------------------------------
;; A type of our own that the whole sequence library can work with.

(declare in-order)

(deftype Tree [value left right]
  clojure.lang.Seqable
  (seq [this] (in-order this))

  clojure.lang.Counted
  (count [this] (count (in-order this)))

  clojure.lang.Sequential)

(defn in-order
  "The values stored in `tree` from smallest to largest, as a lazy seq.
   An empty tree is nil, and its traversal is nil too."
  [^Tree tree]
  (when tree
    (lazy-seq
      (concat (in-order (.-left tree))
              (cons (.-value tree) (in-order (.-right tree)))))))

(defn insert
  "A new tree holding everything in `tree` plus `v`. The old tree is
   not changed; the new one shares every subtree it did not need to rebuild."
  [^Tree tree v]
  (cond
    (nil? tree)          (Tree. v nil nil)
    (< v (.-value tree)) (Tree. (.-value tree) (insert (.-left tree) v) (.-right tree))
    (> v (.-value tree)) (Tree. (.-value tree) (.-left tree) (insert (.-right tree) v))
    :else                tree))

(defn tree
  "A search tree holding the given values."
  [& values]
  (reduce insert nil values))

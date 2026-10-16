(ns sdx.dupes.brute
  "Finding duplicates the obvious way: compare every file with every
   other file. Correct, simple, and far too slow for large directories."
  (:require [sdx.dupes.files :as files]))

(defn pairs
  "Every unordered pair of distinct items from `items`, as two-element
   vectors. There are n(n-1)/2 of them for n items."
  [items]
  (let [v (vec items)
        n (count v)]
    (for [i (range n)
          j (range (inc i) n)]
      [(v i) (v j)])))

(defn same-bytes?
  "True if two byte arrays have the same length and the same contents."
  [^bytes a ^bytes b]
  (java.util.Arrays/equals a b))

(defn duplicate-pairs
  "Every pair of paths whose files have identical contents. Each file is
   read once; each pair of files is compared once."
  [paths]
  (let [contents (into {} (map (juxt identity files/read-bytes) paths))]
    (filter (fn [[a b]] (same-bytes? (contents a) (contents b)))
            (pairs paths))))

(defn pairs->groups
  "Merge pairs that share a member into groups. Each group is a sorted
   vector of paths, and the groups are sorted by their first member.
   This relies on the relation being transitive: if a is paired with b
   and b with c, then a must be paired with c as well."
  [pairs]
  (let [partners (reduce (fn [m [a b]]
                           (-> m
                               (update a (fnil conj #{a}) b)
                               (update b (fnil conj #{b}) a)))
                         {}
                         pairs)]
    (->> (vals partners)
         distinct
         (map (comp vec sort))
         (sort-by first))))

(defn duplicate-groups
  "Groups of files under `root` with identical contents, found by
   comparing every pair of files."
  [root]
  (-> (files/list-files root)
      duplicate-pairs
      pairs->groups))

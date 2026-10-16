(ns sdx.dupes.naive
  "Sorting files into buckets with a deliberately weak hash function, to
   see what hashing buys us and what a weak hash costs."
  (:require [sdx.dupes.brute :as brute]
            [sdx.dupes.files :as files]))

(def bucket-count
  "How many buckets the naive hash sorts files into."
  13)

(defn naive-hash
  "A hash of a byte array: the sum of its bytes, modulo `bucket-count`.
   Each byte is treated as an unsigned number from 0 to 255."
  [^bytes bs]
  (mod (reduce (fn [sum b] (+ sum (bit-and b 0xff))) 0 bs)
       bucket-count))

(defn buckets
  "A map from hash value to the paths of the files with that hash."
  [paths]
  (group-by #(naive-hash (files/read-bytes %)) paths))

(defn duplicate-groups
  "Groups of files under `root` with identical contents: put the files
   in buckets by hash, then compare pairs only within each bucket."
  [root]
  (->> (files/list-files root)
       buckets
       vals
       (filter #(> (count %) 1))
       (mapcat (comp brute/pairs->groups brute/duplicate-pairs))
       (sort-by first)))

(ns sdx.protocols.seqs-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.protocols.seqs :as s]))

(deftest everything-has-a-seq
  (is (= [\a \b] (seq "ab")))
  (is (= [[:a 1]] (seq {:a 1})))
  (is (= [1 2] (seq (java.util.ArrayList. [1 2]))))
  (is (nil? (seq [])))
  (is (nil? (seq nil))))

(deftest counting-down
  (is (= [3 2 1] (s/countdown 3)))
  (is (= [] (s/countdown 0)))
  (is (= 6 (reduce + (s/countdown 3)))))

(deftest laziness-means-nothing-happens-until-asked
  (let [s (s/countdown 3)]
    (is (not (realized? s)))
    (is (= 3 (first s)))
    (is (realized? s)))
  (testing "an endless sequence is fine as long as we only take part of it"
    (is (= [7 14 21] (take 3 (s/multiples 7))))
    (is (= 35 (first (filter #(> % 30) (s/multiples 7)))))))

(deftest iterators-are-stateful
  (let [it (s/iterator [1 2])]
    (is (.hasNext it))
    (is (= 1 (.next it)))
    (is (= 2 (.next it)))
    (is (not (.hasNext it)))
    (is (thrown? java.util.NoSuchElementException (.next it)))))

(deftest iterators-can-be-made-into-seqs
  (let [xs (s/iterator->seq (s/iterator [1 2 3]))]
    (is (= [1 2 3] xs))
    (testing "the seq remembers what it saw, so it can be walked again"
      (is (= [1 2 3] xs)))))

(deftest a-tree-is-a-collection
  (let [t (s/tree 5 3 8 1 4 8)]
    (is (= [1 3 4 5 8] (seq t)))
    (is (= 5 (count t)))
    (is (= [2 4 5 6 9] (map inc t)))
    (is (= [4 8] (filter even? t)))
    (is (= 21 (reduce + t)))
    (is (= #{1 3 4 5 8} (into #{} t)))
    (let [[smallest second-smallest] t]
      (is (= [1 3] [smallest second-smallest])))))

(deftest inserting-does-not-change-the-old-tree
  (let [t1 (s/tree 2)
        t2 (s/insert t1 1)]
    (is (= [2] (seq t1)))
    (is (= [1 2] (seq t2)))))

(deftest the-empty-tree
  (is (nil? (s/tree)))
  (is (= [] (map inc (s/tree))))
  (is (= 0 (count (s/tree)))))

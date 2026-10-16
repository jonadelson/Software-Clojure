(ns sdx.dupes.brute-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.dupes.brute :as brute]
            [sdx.dupes.fixture :as fx]))

(deftest pairs-of-items
  (is (= [] (brute/pairs [])))
  (is (= [] (brute/pairs [:a])))
  (is (= [[:a :b] [:a :c] [:b :c]] (brute/pairs [:a :b :c])))
  (testing "the number of pairs grows with the square of the number of items"
    (is (= 45 (count (brute/pairs (range 10)))))
    (is (= 4950 (count (brute/pairs (range 100)))))))

(deftest comparing-bytes
  (is (brute/same-bytes? (.getBytes "abc") (.getBytes "abc")))
  (is (not (brute/same-bytes? (.getBytes "abc") (.getBytes "abd"))))
  (is (not (brute/same-bytes? (.getBytes "abc") (.getBytes "ab")))))

(deftest merging-pairs-into-groups
  (is (= [] (brute/pairs->groups [])))
  (is (= [[:a :b :c] [:x :y]]
         (brute/pairs->groups [[:a :b] [:b :c] [:a :c] [:x :y]]))))

(deftest finding-duplicates-by-brute-force
  (fx/with-files fx/sample
    (fn [dir]
      (is (= fx/sample-groups
             (fx/relative dir (brute/duplicate-groups dir)))))))

(deftest no-duplicates
  (fx/with-files {"a" "1" "b" "2"}
    (fn [dir]
      (is (= [] (brute/duplicate-groups dir))))))

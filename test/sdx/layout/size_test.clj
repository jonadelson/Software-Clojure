(ns sdx.layout.size-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.layout.size :as s :refer [leaf row col]]))

(deftest leaves-know-their-own-size
  (is (= {:type :leaf :width 2 :height 3 :fill \x} (s/size (leaf 2 3))))
  (is (= \a (:fill (leaf 1 1 \a)))))

(deftest rows-add-widths-and-take-the-tallest
  (let [sized (s/size (row (leaf 2 1) (leaf 1 2)))]
    (is (= 3 (:width sized)))
    (is (= 2 (:height sized)))))

(deftest columns-add-heights-and-take-the-widest
  (let [sized (s/size (col (leaf 2 1) (leaf 1 2)))]
    (is (= 2 (:width sized)))
    (is (= 3 (:height sized)))))

(deftest sizes-are-computed-all-the-way-down
  (let [sized (s/size (col (row (leaf 1 1) (leaf 2 2)) (leaf 4 1)))]
    (is (= 4 (:width sized)))
    (is (= 3 (:height sized)))
    (testing "the inner row was sized too"
      (is (= [3 2] ((juxt :width :height) (first (:children sized))))))))

(deftest empty-containers-have-no-size
  (is (= [0 0] ((juxt :width :height) (s/size (row)))))
  (is (= [0 0] ((juxt :width :height) (s/size (col))))))

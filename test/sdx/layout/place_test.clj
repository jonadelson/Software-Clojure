(ns sdx.layout.place-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.layout.place :as pl]
            [sdx.layout.size :refer [leaf row col]]))

(defn- corners
  "The [x y] of each child of a placed block."
  [placed]
  (mapv (juxt :x :y) (:children placed)))

(deftest a-row-places-children-left-to-right
  (let [placed (pl/layout (row (leaf 2 1) (leaf 1 2) (leaf 3 1)))]
    (is (= [0 0] ((juxt :x :y) placed)))
    (is (= [[0 0] [2 0] [3 0]] (corners placed)))))

(deftest a-column-places-children-top-to-bottom
  (let [placed (pl/layout (col (leaf 2 1) (leaf 1 2) (leaf 3 1)))]
    (is (= [[0 0] [0 1] [0 3]] (corners placed)))))

(deftest placement-is-relative-to-the-parent
  (let [placed (pl/layout (col (leaf 5 2) (row (leaf 1 1) (leaf 1 1))))
        inner (second (:children placed))]
    (testing "the row starts below the first leaf"
      (is (= [0 2] ((juxt :x :y) inner))))
    (testing "and its children are offset from where the row starts"
      (is (= [[0 2] [1 2]] (corners inner))))))

(deftest place-can-start-anywhere
  (is (= [[10 20] [12 20]]
         (corners (pl/place (sdx.layout.size/size (row (leaf 2 1) (leaf 2 1))) 10 20)))))

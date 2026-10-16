(ns sdx.layout.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.layout.render :as r]
            [sdx.layout.size :refer [leaf row col]]))

(deftest filling-a-rectangle
  (is (= [[\. \. \.]
          [\. \a \a]
          [\. \a \a]]
         (r/fill-rect (r/blank-grid 3 3) 1 1 2 2 \a))))

(deftest a-single-leaf
  (is (= ["xx"
          "xx"]
         (r/render (leaf 2 2)))))

(deftest a-row-leaves-gaps-under-short-children
  (is (= ["aab"
          "..b"]
         (r/render (row (leaf 2 1 \a) (leaf 1 2 \b))))))

(deftest a-column-leaves-gaps-beside-narrow-children
  (is (= ["a.."
          "bbb"]
         (r/render (col (leaf 1 1 \a) (leaf 3 1 \b))))))

(deftest nested-blocks
  (is (= ["aab"
          "..b"
          "ccc"]
         (r/render (col (row (leaf 2 1 \a) (leaf 1 2 \b))
                        (leaf 3 1 \c)))))
  (testing "a tall leaf beside a column"
    (is (= ["ab..."
            "acccc"
            "a...."]
           (r/render (row (leaf 1 3 \a) (col (leaf 1 1 \b) (leaf 4 1 \c))))))))

(deftest empty-layouts-render-as-nothing
  (is (= [] (r/render (row))))
  (is (= [] (r/render (col)))))

(ns sdx.layout.wrap-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.layout.render :as r]
            [sdx.layout.size :as s :refer [leaf row col]]
            [sdx.layout.wrap :as w]))

(defn- picture
  "Render `block` after wrapping it at `max-width`."
  [block max-width]
  (r/render (w/wrap block max-width)))

(deftest breaking-a-list-of-blocks-into-lines
  (let [a (leaf 2 1 \a) b (leaf 2 1 \b) c (leaf 2 1 \c)]
    (is (= [[a b c]] (w/break-lines [a b c] 6)))
    (is (= [[a b] [c]] (w/break-lines [a b c] 4)))
    (is (= [[a] [b] [c]] (w/break-lines [a b c] 2)))
    (testing "a block too wide for any line still gets one"
      (is (= [[a] [b] [c]] (w/break-lines [a b c] 1))))
    (is (= [[]] (w/break-lines [] 4)))))

(deftest a-row-that-fits-is-left-alone
  (is (= ["aabbcc"]
         (picture (row (leaf 2 1 \a) (leaf 2 1 \b) (leaf 2 1 \c)) 6))))

(deftest a-row-that-is-too-wide-becomes-a-column-of-rows
  (is (= ["aabb"
          "cc.."]
         (picture (row (leaf 2 1 \a) (leaf 2 1 \b) (leaf 2 1 \c)) 4)))
  (is (= ["aa"
          "bb"
          "cc"]
         (picture (row (leaf 2 1 \a) (leaf 2 1 \b) (leaf 2 1 \c)) 2))))

(deftest wrapping-produces-an-ordinary-block-tree
  (is (= (col (row (leaf 2 1 \a) (leaf 2 1 \b))
              (row (leaf 2 1 \c)))
         (w/wrap (row (leaf 2 1 \a) (leaf 2 1 \b) (leaf 2 1 \c)) 4))))

(deftest a-child-wider-than-the-page-overflows-on-its-own-line
  (is (= ["aaaaa"
          "b...."]
         (picture (row (leaf 5 1 \a) (leaf 1 1 \b)) 3))))

(deftest rows-inside-columns-wrap-too
  (is (= ["aaa"
          "bbb"]
         (picture (col (row (leaf 3 1 \a) (leaf 3 1 \b))) 3))))

(deftest inner-rows-wrap-before-outer-ones
  (testing "the inner row wraps to 2 wide, so the outer row then fits in 3"
    (is (= ["abb"
            ".cc"]
           (picture (row (leaf 1 1 \a) (row (leaf 2 1 \b) (leaf 2 1 \c))) 3)))))

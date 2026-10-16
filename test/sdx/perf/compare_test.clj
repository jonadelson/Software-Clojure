(ns sdx.perf.compare-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sdx.perf.compare :as c]
            [sdx.perf.cols :as cols]
            [sdx.perf.frame :as f]
            [sdx.perf.rows :as rows]))

(deftest generated-data-is-rectangular
  (let [data (c/make-rows 5 3)]
    (is (= 5 (count data)))
    (is (= #{:c0 :c1 :c2} (set (keys (first data)))))
    (is (= {:c0 4 :c1 5 :c2 6} (nth data 4)))
    (is (f/eq? (rows/row-frame data) (cols/from-rows data)))))

(deftest measure-reports-every-quantity
  (let [m (c/measure 50 {:warmup 1 :warmup-ms 0 :repeats 3})]
    (is (= 50 (:rows m)))
    (is (every? #(and (number? (m %)) (>= (m %) 0))
                [:select-rows :select-cols :filter-rows :filter-cols]))))

(deftest table-is-markdown
  (let [text (c/table [{:rows 10 :select-rows 0.5 :select-cols 0.25
                        :filter-rows 1 :filter-cols 2}])
        lines (str/split-lines text)]
    (is (= 3 (count lines)))
    (is (str/starts-with? (first lines) "| rows |"))
    (is (= "| 10 | 0.500 | 0.250 | 1.000 | 2.000 |" (last lines)))))

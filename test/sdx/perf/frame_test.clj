(ns sdx.perf.frame-test
  "The same tests run against both layouts: anything the protocol
   promises must hold however the frame is stored."
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.perf.cols :as cols]
            [sdx.perf.frame :as f]
            [sdx.perf.rows :as rows]))

(def sample
  [{:name "ant" :legs 6 :flies false}
   {:name "bat" :legs 2 :flies true}
   {:name "cat" :legs 4 :flies false}
   {:name "dove" :legs 2 :flies true}])

(def layouts
  "Each layout's constructor, so every test below runs on both."
  {"row-major" rows/row-frame
   "column-major" cols/from-rows})

(deftest shape
  (doseq [[label build] layouts]
    (testing label
      (let [df (build sample)]
        (is (= 3 (f/ncol df)))
        (is (= 4 (f/nrow df)))
        (is (= #{:name :legs :flies} (f/cols df)))
        (is (= [6 2 4 2] (f/column df :legs)))
        (is (= {:name "bat" :legs 2 :flies true} (f/row df 1)))))))

(deftest selecting-columns
  (doseq [[label build] layouts]
    (testing label
      (let [df (f/select (build sample) [:name :legs])]
        (is (= #{:name :legs} (f/cols df)))
        (is (= 4 (f/nrow df)))
        (is (= ["ant" "bat" "cat" "dove"] (f/column df :name)))))))

(deftest filtering-rows
  (doseq [[label build] layouts]
    (testing label
      (let [df (f/filter-rows (build sample) :flies)]
        (is (= 2 (f/nrow df)))
        (is (= 3 (f/ncol df)))
        (is (= ["bat" "dove"] (f/column df :name)))))))

(deftest filtering-everything-keeps-the-columns
  (doseq [[label build] layouts]
    (testing label
      (let [df (f/filter-rows (build sample) (constantly false))]
        (is (= 0 (f/nrow df)))
        (is (= #{:name :legs :flies} (f/cols df)))))))

(deftest layouts-are-interchangeable
  (let [by-row (rows/row-frame sample)
        by-col (cols/from-rows sample)]
    (is (f/eq? by-row by-col))
    (is (f/eq? by-col by-row))
    (is (f/eq? (f/select by-row [:legs]) (f/select by-col [:legs])))
    (is (f/eq? (f/filter-rows by-row :flies) (f/filter-rows by-col :flies)))
    (is (not (f/eq? by-row (f/select by-col [:legs]))))
    (is (not (f/eq? by-row (f/filter-rows by-col :flies))))))

(deftest ragged-data-is-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (rows/row-frame [{:a 1 :b 2} {:a 3}])))
  (is (thrown? clojure.lang.ExceptionInfo
               (cols/col-frame {:a [1 2 3] :b [4 5]}))))

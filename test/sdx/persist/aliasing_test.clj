(ns sdx.persist.aliasing-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.persist.aliasing :as p]
            [sdx.persist.basic-test :refer [samples]]))

(deftest unshared-values-look-the-same-as-before
  (is (= ["int:42"] (p/to-lines 42)))
  (is (= ["vec:3" "int:1" "int:2" "int:3"] (p/to-lines [1 2 3])))
  (is (= ["map:1" "kw:a" "vec:1" "str:1" "x"] (p/to-lines {:a ["x"]}))))

(deftest everything-still-round-trips
  (doseq [v samples]
    (testing (pr-str v)
      (is (= v (p/from-lines (p/to-lines v))))
      (is (= v (p/from-string (p/to-string v)))))))

(deftest a-shared-collection-is-written-once
  (let [shared [1 2]
        value {:a shared :b shared}]
    (is (= ["map:2" "kw:a" "vec:2" "int:1" "int:2" "kw:b" "ref:0"]
           (p/to-lines value)))
    (testing "equal but distinct collections are both written in full"
      (is (= ["vec:2" "vec:1" "int:1" "vec:1" "int:1"]
             (p/to-lines [[1] [1]]))))))

(deftest sharing-survives-a-round-trip
  (let [shared {:name "shared" :items [1 2 3]}
        value [shared {:left shared :right shared} shared]
        loaded (p/from-lines (p/to-lines value))]
    (is (= value loaded))
    (testing "the loaded structure has one copy, referenced four times"
      (let [[a {:keys [left right]} b] loaded]
        (is (identical? a left))
        (is (identical? a right))
        (is (identical? a b))))))

(deftest references-are-numbered-in-completion-order
  (testing "an inner collection finishes before the one around it"
    (let [inner [1]
          outer [inner]]
      (is (= ["vec:3" "vec:1" "vec:1" "int:1" "ref:1" "ref:0"]
             (p/to-lines [outer outer inner]))))))

(deftest scalars-are-never-shared
  (testing "the same string twice is simply written twice"
    (let [s "hello"]
      (is (= ["vec:2" "str:1" "hello" "str:1" "hello"] (p/to-lines [s s]))))))

(deftest bad-references-are-reported
  (is (thrown? IndexOutOfBoundsException (p/from-lines ["vec:1" "ref:0"]))))

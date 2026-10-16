(ns sdx.interp.calc-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.interp.calc :as calc]))

(deftest literals-evaluate-to-themselves
  (is (= 5 (calc/evaluate 5)))
  (is (= -2.5 (calc/evaluate -2.5))))

(deftest nested-arithmetic
  (is (= 7 (calc/evaluate [:add 1 [:mul 2 3]])))
  (is (= 2 (calc/evaluate [:div [:sub 10 4] 3])))
  (is (= 9 (calc/evaluate [:max 1 9 4]))))

(deftest unknown-operation
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown operation :pow"
                        (calc/evaluate [:pow 2 3]))))

(deftest the-table-is-a-parameter
  (let [table (assoc calc/ops :pow (fn [base exp] (long (Math/pow base exp))))]
    (is (= 8 (calc/evaluate table [:pow 2 3])))))

(deftest why-if-cannot-be-a-table-entry
  (let [table (assoc calc/ops :if (fn [test then else] (if test then else)))]
    (testing "it picks the right branch"
      (is (= 1 (calc/evaluate table [:if true 1 2]))))
    (testing "but both branches are evaluated before it gets to choose"
      (is (thrown? ArithmeticException
                   (calc/evaluate table [:if true 1 [:div 1 0]]))))))

(ns sdx.runner.simple-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sdx.runner.sample.basic]
            [sdx.runner.simple :as r]))

(deftest finding-tests
  (testing "only public vars whose names start with test-, in name order"
    (is (= '[test-addition test-division test-strings test-subtraction]
           (map r/var-name (r/find-tests 'sdx.runner.sample.basic))))))

(deftest classifying-outcomes
  (is (= {:outcome :pass} (r/classify (fn [] 42))))
  (testing "a false assert is a failure, with its message"
    (let [{:keys [outcome message]} (r/classify (fn [] (assert false "nope")))]
      (is (= :fail outcome))
      (is (str/includes? message "nope"))))
  (testing "any other exception is an error"
    (is (= :error (:outcome (r/classify (fn [] (throw (ex-info "boom" {})))))))
    (is (= :error (:outcome (r/classify (fn [] (/ 1 0))))))))

(deftest running-one-test
  (is (= {:test 'test-addition :outcome :pass}
         (r/run-test #'sdx.runner.sample.basic/test-addition)))
  (is (= :fail (:outcome (r/run-test #'sdx.runner.sample.basic/test-subtraction))))
  (is (= :error (:outcome (r/run-test #'sdx.runner.sample.basic/test-division)))))

(deftest running-a-namespace
  (let [results (r/run-tests 'sdx.runner.sample.basic)]
    (is (= [:pass :error :pass :fail] (map :outcome results)))
    (is (= {:pass 2 :fail 1 :error 1} (r/summarize results)))))

(deftest summarizing-nothing
  (is (= {:pass 0 :fail 0 :error 0} (r/summarize []))))

(deftest reporting
  (let [results (r/run-tests 'sdx.runner.sample.basic)
        output (with-out-str (r/report results))
        lines (str/split-lines output)]
    (is (= 5 (count lines)))
    (is (= "pass   test-addition" (first lines)))
    (is (str/starts-with? (second lines) "error  test-division: java.lang.ArithmeticException"))
    (is (= "fail   test-subtraction: Assert failed: subtraction is broken" (nth lines 3)))
    (is (= "pass 2  fail 1  error 1" (last lines)))))

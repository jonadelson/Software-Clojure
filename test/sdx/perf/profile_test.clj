(ns sdx.perf.profile-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sdx.perf.profile :as p]))

(use-fixtures :each (fn [run] (p/reset-stats!) (run)))

(defn slow-add
  "A function to profile: adds two numbers after a short pause."
  [a b]
  (Thread/sleep 1)
  (+ a b))

(deftest timed-blocks-are-counted
  (is (= 3 (p/timed :block (+ 1 2))))
  (p/timed :block (Thread/sleep 1))
  (let [[entry] (p/report)]
    (is (= :block (:label entry)))
    (is (= 2 (:calls entry)))
    (is (>= (:total-ms entry) 1.0))))

(deftest timed-records-even-when-the-body-throws
  (is (thrown? ArithmeticException (p/timed :boom (/ 1 0))))
  (is (= 1 (get-in @p/stats [:boom :calls]))))

(deftest profiled-function-still-works
  (let [add (p/profiled :add +)]
    (is (= 6 (add 1 2 3)))
    (is (= 0 (add)))
    (is (= 2 (get-in @p/stats [:add :calls])))))

(deftest profiling-a-var-and-restoring-it
  (let [original slow-add]
    (p/profile-var! #'slow-add)
    (testing "callers see the same results"
      (is (= 5 (slow-add 2 3)))
      (is (= 7 (slow-add 3 4))))
    (testing "and the calls were recorded under the var's name"
      (let [{:keys [calls total-ms mean-ms]} (get-in (into {} (map (juxt :label identity)) (p/report))
                                                     ['sdx.perf.profile-test/slow-add])]
        (is (= 2 calls))
        (is (>= total-ms 2.0))
        (is (>= mean-ms 1.0))))
    (testing "profiling twice does not wrap twice"
      (p/profile-var! #'slow-add)
      (slow-add 1 1)
      (is (= 3 (get-in @p/stats ['sdx.perf.profile-test/slow-add :calls]))))
    (testing "unprofiling restores the original function"
      (p/unprofile-var! #'slow-add)
      (is (identical? original slow-add))
      (slow-add 1 1)
      (is (= 3 (get-in @p/stats ['sdx.perf.profile-test/slow-add :calls]))))))

(deftest report-is-sorted-by-total-time
  (p/record! :small 10)
  (p/record! :large 1000)
  (p/record! :large 1000)
  (is (= [:large :small] (map :label (p/report))))
  (is (= 2 (:calls (first (p/report)))))
  (is (= 1.0e-3 (:mean-ms (first (p/report))))))

(ns sdx.perf.bench-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.perf.bench :as b]))

(deftest median-of-numbers
  (is (= 3 (b/median [5 1 3])))
  (is (= 2.5 (b/median [4 1 2 3])))
  (is (= 7 (b/median [7]))))

(deftest body-runs-warmup-plus-repeats-times
  (let [calls (atom 0)
        result (b/bench {:warmup 2 :warmup-ms 0 :repeats 3} (swap! calls inc))]
    (is (= 5 @calls))
    (is (= 2 (:warmup-runs result)))))

(deftest warmup-keeps-going-until-time-is-up
  (let [calls (atom 0)]
    (b/bench {:warmup 1 :warmup-ms 20 :repeats 1} (do (swap! calls inc) (Thread/sleep 1)))
    (is (> @calls 5))))

(deftest timings-are-plausible
  (let [result (b/bench {:warmup 1 :warmup-ms 0 :repeats 3} (Thread/sleep 2))]
    (testing "a 2ms sleep never takes less than 2ms"
      (is (>= (:min-ms result) 2.0)))
    (testing "min, median and max are in order"
      (is (<= (:min-ms result) (:median-ms result) (:max-ms result))))
    (testing "every timed run is reported"
      (is (= 3 (count (:times-ms result)))))))

(deftest bench-star-takes-a-thunk
  (let [result (b/bench* (fn [] (reduce + (range 1000))) {:warmup 0 :warmup-ms 0 :repeats 1})]
    (is (= 1 (count (:times-ms result))))
    (is (number? (:median-ms result)))))

(ns sdx.protocols.mock-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.protocols.mock :as m]))

(deftest the-real-thing-is-unusable
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no network"
                        (m/convert 10 "USD" "EUR"))))

(deftest with-redefs-replaces-a-dependency
  (with-redefs [m/exchange-rate (constantly 2)]
    (is (= 20 (m/convert 10 "USD" "EUR"))))
  (testing "the replacement is gone once the block ends"
    (is (thrown? clojure.lang.ExceptionInfo (m/convert 10 "USD" "EUR")))))

(deftest a-fixed-clock-makes-output-predictable
  (with-redefs [m/exchange-rate (fn [_ _] 1.5)
                m/current-time-ms (constantly 1700000000000)]
    (is (= "15.00 EUR at 1700000000000" (m/receipt 10 "USD" "EUR")))))

(deftest a-var-is-a-box-you-can-look-inside
  (is (var? #'m/convert))
  (is (identical? #'m/convert (var m/convert)))
  (is (fn? (deref #'m/convert)))
  (is (= 'sdx.protocols.mock (ns-name (:ns (meta #'m/convert))))))

(deftest home-made-replacement
  (is (= 30 (m/with-replacement #'m/exchange-rate (constantly 3)
              (fn [] (m/convert 10 "USD" "EUR")))))
  (testing "the old value comes back even when the body throws"
    (is (thrown? ArithmeticException
                 (m/with-replacement #'m/exchange-rate (constantly 3)
                   (fn [] (/ 1 0)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no network"
                          (m/convert 10 "USD" "EUR")))))

(deftest recording-what-was-asked
  (let [rate (m/recording (constantly 2))]
    (with-redefs [m/exchange-rate (:fn rate)]
      (m/convert 10 "USD" "EUR")
      (m/convert 5 "GBP" "JPY"))
    (is (= [["USD" "EUR"] ["GBP" "JPY"]] @(:calls rate)))))

(ns sdx.objects.simple-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.objects.simple :as s]))

(deftest square-geometry
  (let [sq (s/square "sq" 3)]
    (is (= 9 (s/call sq :area)))
    (is (= 12 (s/call sq :perimeter)))))

(deftest circle-geometry
  (let [ci (s/circle "ci" 2)]
    (is (< (Math/abs (- (* 4 Math/PI) (s/call ci :area))) 1e-9))
    (is (< (Math/abs (- (* 4 Math/PI) (s/call ci :perimeter))) 1e-9))))

(deftest describing-any-shape
  (testing "describe does not need to know what kind of shape it has"
    (is (= "sq: area 9.00, perimeter 12.00"
           (s/describe (s/square "sq" 3))))
    (is (= "ci: area 3.14, perimeter 6.28"
           (s/describe (s/circle "ci" 1))))))

(deftest missing-method
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no method :volume"
                        (s/call (s/square "sq" 1) :volume))))

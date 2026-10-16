(ns sdx.objects.builtin-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.objects.builtin :as b]))

(deftest multimethod-dispatch
  (is (= 9 (b/area {:kind :square :side 3})))
  (is (= 12 (b/perimeter {:kind :square :side 3})))
  (is (= "ci: area 3.14, perimeter 6.28"
         (b/describe {:kind :circle :name "ci" :radius 1}))))

(deftest multimethod-unknown-kind
  (is (thrown? IllegalArgumentException (b/area {:kind :blob}))))

(deftest protocol-dispatch
  (let [sq (b/->Square "sq" 3)
        ci (b/->Circle "ci" 1)]
    (is (= 9 (b/shape-area sq)))
    (is (= 12 (b/shape-perimeter sq)))
    (is (= "ci: area 3.14, perimeter 6.28" (b/describe-record ci)))
    (testing "records are still maps"
      (is (= "sq" (:name sq)))
      (is (= 4 (:side (assoc sq :side 4)))))))

(deftest extending-after-the-fact
  (testing "a new kind of shape can be added without touching existing code"
    (defmethod b/area :triangle [{:keys [base height]}] (/ (* base height) 2))
    (is (= 6 (b/area {:kind :triangle :base 3 :height 4})))
    (remove-method b/area :triangle)))

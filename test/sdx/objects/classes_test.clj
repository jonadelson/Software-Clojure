(ns sdx.objects.classes-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.objects.classes :as c]))

(deftest constructing-instances
  (let [sq (c/make c/square "sq" 3)]
    (is (= "sq" (:name sq)))
    (is (= 3 (:side sq)))
    (is (= "Square" (get-in sq [:class :name])))))

(deftest calling-own-methods
  (let [sq (c/make c/square "sq" 3)]
    (is (= 9 (c/call sq :area)))
    (is (= 12 (c/call sq :perimeter)))))

(deftest calling-inherited-methods
  (testing ":describe is defined on Shape but works on a Square"
    (is (= "sq: area 9.00, perimeter 12.00"
           (c/call (c/make c/square "sq" 3) :describe))))
  (testing "inherited methods can take extra arguments"
    (is (= 2 (c/call (c/make c/square "sq" 3) :density 18)))))

(deftest method-lookup-walks-the-chain
  (is (fn? (c/find-method c/square :describe)))
  (is (nil? (c/find-method c/square :volume)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no method :volume for Square"
                        (c/call (c/make c/square "sq" 1) :volume))))

(deftest instance-checks
  (let [sq (c/make c/square "sq" 3)]
    (is (c/instance-of? sq c/square))
    (is (c/instance-of? sq c/shape))
    (is (not (c/instance-of? sq c/circle)))))

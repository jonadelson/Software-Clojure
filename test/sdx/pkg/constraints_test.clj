(ns sdx.pkg.constraints-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.pkg.constraints :as c]))

(deftest parsing-versions
  (is (= [1 2 3] (c/parse-version "1.2.3")))
  (is (= [10] (c/parse-version "10")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad version"
                        (c/parse-version "1.2-beta")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad version"
                        (c/parse-version ""))))

(deftest comparing-versions
  (is (zero? (c/compare-versions [1 2] [1 2 0])))
  (is (neg? (c/compare-versions [1 9] [1 10])))
  (is (pos? (c/compare-versions [2] [1 99 99])))
  (is (= ["3.0" "2.1" "2.0" "1.10" "1.9"] (c/newest-first ["1.9" "2.0" "1.10" "3.0" "2.1"]))))

(deftest parsing-clauses
  (is (= [">=" [1 2]] (c/parse-clause ">=1.2")))
  (is (= ["=" [2 0 1]] (c/parse-clause "2.0.1")))
  (is (= ["<" [3]] (c/parse-clause " < 3 ")))
  (is (= [[">=" [1 2]] ["<" [3]]] (c/parse-constraint ">=1.2,<3")))
  (is (= [] (c/parse-constraint "*")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad constraint"
                        (c/parse-constraint "~1.2"))))

(deftest satisfying-constraints
  (is (c/meets? ">=1.2" "1.2"))
  (is (c/meets? ">=1.2" "1.10"))
  (is (not (c/meets? ">=1.2" "1.1.9")))
  (is (c/meets? "<3" "2.9.9"))
  (is (not (c/meets? "<3" "3.0")))
  (is (c/meets? "=2.0.1" "2.0.1"))
  (is (not (c/meets? "=2.0.1" "2.0")))
  (is (c/meets? "!=2" "2.1"))
  (is (c/meets? ">=1.2,<3" "2.5"))
  (is (not (c/meets? ">=1.2,<3" "3")))
  (is (c/meets? "*" "0.0.1"))
  (is (= #{"1.2" "2.0"} (c/allowed-versions ">=1.2,<3" ["1.0" "1.2" "2.0" "3.0"]))))

(def manifest
  {"app" {"1.0" {"web" ">=2", "db" ">=1"}
          "1.1" {"web" ">=2.1", "db" ">=1.2"}}
   "web" {"2.0" {"db" "<2"}
          "2.1" {"db" ">=1.1,<3"}
          "3.0" {"db" ">=2"}}
   "db"  {"1.0" {} "1.2" {} "2.0" {}}})

(deftest compiling-a-manifest
  (let [compiled (c/compile-manifest manifest)]
    (is (= {"web" #{"2.0" "2.1" "3.0"} "db" #{"1.0" "1.2" "2.0"}} (get-in compiled ["app" "1.0"])))
    (is (= {"web" #{"2.1" "3.0"} "db" #{"1.2" "2.0"}} (get-in compiled ["app" "1.1"])))
    (is (= {"db" #{"1.0" "1.2"}} (get-in compiled ["web" "2.0"])))
    (is (= {} (get-in compiled ["db" "1.0"]))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown package nope"
                        (c/compile-manifest {"a" {"1" {"nope" ">=1"}}}))))

(deftest solving-with-expressions
  (let [{:keys [solutions]} (c/solve manifest)]
    (is (= #{{"app" "1.0" "web" "2.0" "db" "1.0"}
             {"app" "1.0" "web" "2.0" "db" "1.2"}
             {"app" "1.0" "web" "2.1" "db" "1.2"}
             {"app" "1.0" "web" "2.1" "db" "2.0"}
             {"app" "1.0" "web" "3.0" "db" "2.0"}
             {"app" "1.1" "web" "2.1" "db" "1.2"}
             {"app" "1.1" "web" "2.1" "db" "2.0"}
             {"app" "1.1" "web" "3.0" "db" "2.0"}}
           (set solutions)))
    (testing "newest versions are tried first, so the first solution is the newest"
      (is (= {"app" "1.1" "web" "3.0" "db" "2.0"} (first solutions))))))

(deftest unsatisfiable-constraints
  (let [impossible {"a" {"1.0" {"c" ">=2"}}
                    "b" {"1.0" {"c" "<2"}}
                    "c" {"1.0" {} "2.0" {}}}]
    (is (= [] (:solutions (c/solve impossible)))))
  (testing "a constraint no version meets compiles to an empty set"
    (let [compiled (c/compile-manifest {"a" {"1.0" {"c" ">=9"}} "c" {"1.0" {}}})]
      (is (= #{} (get-in compiled ["a" "1.0" "c"])))
      (is (= [] (:solutions (c/solve {"a" {"1.0" {"c" ">=9"}} "c" {"1.0" {}}})))))))

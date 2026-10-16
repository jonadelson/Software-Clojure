(ns sdx.pkg.exhaustive-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.pkg.exhaustive :as ex]))

(def manifest
  {"app" {"1" {"web" #{"1" "2"} "db" #{"1" "2"}}
          "2" {"web" #{"2"} "db" #{"2" "3"}}}
   "web" {"1" {"db" #{"1"}}
          "2" {"db" #{"2" "3"}}}
   "db"  {"1" {} "2" {} "3" {}}})

(def solutions
  #{{"app" "1" "web" "1" "db" "1"}
    {"app" "1" "web" "2" "db" "2"}
    {"app" "2" "web" "2" "db" "2"}
    {"app" "2" "web" "2" "db" "3"}})

(deftest cartesian-products
  (is (= '(()) (ex/cartesian [])))
  (is (= '((1 :a) (1 :b) (2 :a) (2 :b)) (ex/cartesian [[1 2] [:a :b]])))
  (is (= '() (ex/cartesian [[1 2] []])))
  (testing "the product is lazy: it can start from an infinite collection"
    (is (= '((0 :a) (0 :b) (1 :a)) (take 3 (ex/cartesian [(range) [:a :b]]))))))

(deftest every-candidate-is-generated
  (is (= 12 (ex/candidate-count manifest)))
  (is (= 12 (count (ex/candidates manifest))))
  (is (= 12 (count (set (ex/candidates manifest)))))
  (is (= {"app" "1" "db" "1" "web" "1"} (first (ex/candidates manifest)))))

(deftest checking-one-candidate
  (is (ex/consistent? manifest {"app" "1" "web" "1" "db" "1"}))
  (is (not (ex/consistent? manifest {"app" "1" "web" "2" "db" "1"})))
  (testing "a package is compatible when its own dependencies are satisfied"
    (is (ex/compatible? manifest {"app" "1" "web" "2" "db" "1"} "app" "1"))
    (is (not (ex/compatible? manifest {"app" "1" "web" "2" "db" "1"} "web" "2")))))

(deftest solving-checks-everything
  (let [{:keys [solutions checked]} (ex/solve manifest)]
    (is (= 12 checked))
    (is (= 4 (count solutions)))
    (is (= sdx.pkg.exhaustive-test/solutions (set solutions)))))

(deftest unsatisfiable-manifests-have-no-solutions
  (let [impossible {"a" {"1" {"c" #{"2"}}}
                    "b" {"1" {"c" #{"1"}}}
                    "c" {"1" {} "2" {}}}
        {:keys [solutions checked]} (ex/solve impossible)]
    (is (= [] solutions))
    (is (= 2 checked))))

(deftest unknown-packages-are-an-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a 1 depends on unknown package nope"
                        (ex/solve {"a" {"1" {"nope" #{"1"}}}}))))

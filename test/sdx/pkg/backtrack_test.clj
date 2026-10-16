(ns sdx.pkg.backtrack-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.pkg.exhaustive :as ex]
            [sdx.pkg.exhaustive-test :refer [manifest solutions]]
            [sdx.pkg.backtrack :as bt]))

(def bigger
  {"app"  {"1" {"web" #{"2" "3"} "db" #{"2" "3"} "log" #{"1" "2" "3"}}
           "2" {"web" #{"3"} "db" #{"3"} "log" #{"2" "3"}}
           "3" {"web" #{"3"} "db" #{"3"} "log" #{"3"}}}
   "web"  {"1" {"http" #{"1"}}
           "2" {"http" #{"1" "2"}}
           "3" {"http" #{"2"} "log" #{"2" "3"}}}
   "db"   {"1" {}
           "2" {"log" #{"1" "2"}}
           "3" {"log" #{"2" "3"}}}
   "http" {"1" {}
           "2" {"log" #{"1" "2"}}}
   "log"  {"1" {} "2" {} "3" {}}})

(defn chain
  "A manifest of `n` packages p0 ... p(n-1), each with three versions,
   where every version of each package requires the same version of
   the next package. There are exactly three solutions however long the
   chain is."
  [n]
  (into {}
        (for [i (range n)]
          [(str "p" i)
           (into {}
                 (for [v ["1" "2" "3"]]
                   [v (if (< (inc i) n) {(str "p" (inc i)) #{v}} {})]))])))

(deftest fitting-into-a-partial-choice
  (testing "nothing chosen yet: anything fits"
    (is (bt/fits? manifest {} "app" "1")))
  (testing "the newcomer's constraints on chosen packages"
    (is (bt/fits? manifest {"db" "1"} "web" "1"))
    (is (not (bt/fits? manifest {"db" "1"} "web" "2"))))
  (testing "chosen packages' constraints on the newcomer"
    (is (bt/fits? manifest {"app" "2"} "web" "2"))
    (is (not (bt/fits? manifest {"app" "2"} "web" "1"))))
  (testing "packages not yet chosen are not consulted"
    (is (bt/fits? manifest {"app" "2"} "db" "3"))))

(deftest backtracking-finds-the-same-solutions
  (let [{:keys [solutions checked]} (bt/solve manifest)]
    (is (= sdx.pkg.exhaustive-test/solutions (set solutions)))
    (is (= 16 checked)))
  (testing "on a bigger manifest, the two stages still agree"
    (let [exhaustive (ex/solve bigger)
          backtracking (bt/solve bigger)]
      (is (= 162 (:checked exhaustive)))
      (is (= 10 (count (:solutions exhaustive))))
      (is (= (set (:solutions exhaustive)) (set (:solutions backtracking))))
      (is (= 77 (:checked backtracking))))))

(deftest pruning-beats-the-explosion
  (let [m (chain 8)
        exhaustive (ex/solve m)
        backtracking (bt/solve m)]
    (is (= 6561 (ex/candidate-count m)))
    (is (= 6561 (:checked exhaustive)))
    (is (= 3 (count (:solutions exhaustive))))
    (is (= (set (:solutions exhaustive)) (set (:solutions backtracking))))
    (testing "three choices at the top, then three tries per level of which one fits"
      (is (= (+ 3 (* 3 3 7)) (:checked backtracking))))))

(deftest search-order-changes-the-work
  (is (= ["app" "db" "http" "log" "web"] (bt/alphabetical bigger)))
  (is (= ["http" "app" "db" "log" "web"] (bt/fewest-versions-first bigger)))
  (is (= ["app" "log" "web" "db" "http"] (bt/most-constrained-first bigger)))
  (let [tried (fn [order] (:checked (bt/solve bigger {:order order})))]
    (is (= 77 (tried bt/alphabetical)))
    (is (= 83 (tried bt/fewest-versions-first)))
    (is (= 74 (tried bt/most-constrained-first))))
  (testing "trying versions newest first finds the same solutions"
    (let [newest (fn [package] (reverse (sort (keys (bigger package)))))]
      (is (= (set (:solutions (bt/solve bigger)))
             (set (:solutions (bt/solve bigger {:versions-of newest}))))))))

(deftest unsatisfiable-manifests-are-pruned-early
  (let [impossible {"a" {"1" {"c" #{"2"}}}
                    "b" {"1" {"c" #{"1"}}}
                    "c" {"1" {} "2" {}}}
        {:keys [solutions checked]} (bt/solve impossible)]
    (is (= [] solutions))
    (is (= 4 checked))))

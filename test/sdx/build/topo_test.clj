(ns sdx.build.topo-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.build.topo :as topo]))

(def config
  {"app"    {:depends ["main.o" "util.o"] :rule "link app"}
   "main.o" {:depends ["main.c" "defs.h"] :rule "compile main"}
   "util.o" {:depends ["util.c" "defs.h"] :rule "compile util"}
   "main.c" {}
   "util.c" {}
   "defs.h" {}})

(defn position [order name]
  (.indexOf ^java.util.List order name))

(deftest sort-puts-dependencies-first
  (let [order (topo/topo-sort config)]
    (is (= 6 (count order)))
    (is (= #{"app" "main.o" "util.o" "main.c" "util.c" "defs.h"} (set order)))
    (doseq [[target {:keys [depends]}] config
            dep depends]
      (is (< (position order dep) (position order target))
          (str dep " should come before " target)))))

(deftest sort-is-deterministic
  (is (= ["defs.h" "main.c" "main.o" "util.c" "util.o" "app"]
         (topo/topo-sort config)))
  (is (= (topo/topo-sort config) (topo/topo-sort config))))

(deftest dependents-point-the-other-way
  (is (= {"a" #{"b" "c"} "b" #{"c"} "c" #{}}
         (topo/dependents {"a" {} "b" {:depends ["a"]} "c" {:depends ["a" "b"]}}))))

(deftest cycles-are-detected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle among a, b"
                        (topo/topo-sort {"a" {:depends ["b"]} "b" {:depends ["a"]}})))
  (testing "a target that depends on itself is a cycle of one"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle among a"
                          (topo/topo-sort {"a" {:depends ["a"]}}))))
  (testing "targets downstream of a cycle are reported with it"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle among a, b, c"
                          (topo/topo-sort {"a" {:depends ["b"]}
                                           "b" {:depends ["a"]}
                                           "c" {:depends ["b"]}
                                           "d" {}})))))

(deftest unknown-dependencies-are-an-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"app depends on unknown target x.o"
                        (topo/topo-sort {"app" {:depends ["x.o"]}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown target nope"
                        (topo/plan config ["nope"]))))

(deftest plan-covers-only-what-is-needed
  (is (= ["defs.h" "main.c" "main.o"] (topo/plan config ["main.o"])))
  (is (= (topo/topo-sort config) (topo/plan config ["app"])))
  (is (= (topo/topo-sort config) (topo/plan config nil))))

(deftest build-runs-rules-in-order
  (let [ran (atom [])
        order (topo/build config ["app"] (fn [target rule] (swap! ran conj [target rule])))]
    (is (= 6 (count order)))
    (testing "sources have no rule and so nothing is run for them"
      (is (= [["main.o" "compile main"] ["util.o" "compile util"] ["app" "link app"]]
             @ran)))))

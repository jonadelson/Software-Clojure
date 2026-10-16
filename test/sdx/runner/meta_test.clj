(ns sdx.runner.meta-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.runner.meta :as m]
            [sdx.runner.sample.basic]
            [sdx.runner.sample.meta :as sample-meta]
            [sdx.runner.sample.only]
            [sdx.runner.simple :as simple]))

(defn- decisions
  "The plan for a namespace as [name decision] pairs."
  [ns-sym]
  (map (fn [[v d]] [(simple/var-name v) d])
       (m/plan (simple/find-tests ns-sym))))

(deftest planning-without-metadata
  (testing "with no metadata, every test runs"
    (is (every? #(= :run (second %)) (decisions 'sdx.runner.sample.basic)))))

(deftest planning-with-skip
  (is (= '[[test-one :run] [test-slow :skip] [test-two :run]]
         (decisions 'sdx.runner.sample.meta))))

(deftest planning-with-only
  (testing "one ^:only test silences the others, and ^:skip beats ^:only"
    (is (= '[[test-first :skip] [test-fourth :skip] [test-second :run] [test-third :skip]]
           (decisions 'sdx.runner.sample.only)))))

(deftest finding-hooks
  (is (= 'before-each (simple/var-name (m/find-hook 'sdx.runner.sample.meta :setup))))
  (is (= 'after-each (simple/var-name (m/find-hook 'sdx.runner.sample.meta :teardown))))
  (is (nil? (m/find-hook 'sdx.runner.sample.basic :setup))))

(deftest hooks-wrap-every-test
  (reset! sample-meta/log [])
  (let [results (m/run-tests 'sdx.runner.sample.meta)]
    (is (= [:pass :skip :fail] (map :outcome results)))
    (testing "teardown ran after the failing test, and the skipped test never ran"
      (is (= [:setup :one :teardown :setup :two :teardown] @sample-meta/log)))))

(deftest a-failing-hook-is-the-tests-error
  (let [hooks {:setup (fn [] (throw (ex-info "no database" {})))}
        result (m/run-test hooks #'sdx.runner.sample.basic/test-addition)]
    (is (= :error (:outcome result)))
    (is (= 'test-addition (:test result)))))

(deftest only-runs-only
  (is (= [:skip :skip :pass :skip]
         (map :outcome (m/run-tests 'sdx.runner.sample.only)))))

(deftest reporting-skips
  (reset! sample-meta/log [])
  (let [output (with-out-str (m/report (m/run-tests 'sdx.runner.sample.meta)))]
    (is (.endsWith ^String (.trim ^String output) "pass 1  fail 1  error 0  skip 1"))))

(deftest finds-deftests
  (testing "clojure.test marks its tests with :test metadata, so we can find them too"
    (let [found (m/find-deftests 'sdx.runner.meta-test)]
      (is (some #{'finds-deftests} (map simple/var-name found)))
      (is (every? #(fn? (:test (meta %))) found)))))

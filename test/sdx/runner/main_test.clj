(ns sdx.runner.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sdx.runner.main :as main]
            [sdx.runner.sample.meta :as sample-meta]))

(deftest running-several-namespaces
  (reset! sample-meta/log [])
  (let [summary (atom nil)
        output (with-out-str
                 (reset! summary
                         (main/run-namespaces ["sdx.runner.sample.basic"
                                               "sdx.runner.sample.meta"])))]
    (is (= {:pass 3 :fail 2 :error 1 :skip 1} @summary))
    (is (str/includes? output "== sdx.runner.sample.basic"))
    (is (str/includes? output "== sdx.runner.sample.meta"))))

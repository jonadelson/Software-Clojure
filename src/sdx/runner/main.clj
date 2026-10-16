(ns sdx.runner.main
  "A command-line entry point for the runner:

       clojure -M:dev -m sdx.runner.main sdx.runner.sample.basic

   Loads each named namespace, runs its tests, prints a report, and
   exits with status 1 if anything failed or errored."
  (:require [sdx.runner.meta :as meta]))

(defn run-namespaces
  "Load and run each namespace named in `ns-names`, printing a report
   for each. Returns the combined summary."
  [ns-names]
  (let [summaries (mapv (fn [n]
                          (let [ns-sym (symbol n)]
                            (require ns-sym)
                            (println "==" n)
                            (meta/report (meta/run-tests ns-sym))))
                        ns-names)]
    (apply merge-with + {:pass 0 :fail 0 :error 0 :skip 0} summaries)))

(defn -main
  "Run the tests in the named namespaces and exit non-zero on failure."
  [& ns-names]
  (let [{:keys [fail error]} (run-namespaces ns-names)]
    (System/exit (if (zero? (+ fail error)) 0 1))))

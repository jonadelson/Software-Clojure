(ns sdx.runner.simple
  "Stage 1: a test runner that finds the public functions in a namespace
   whose names start with \"test-\", calls each one with no arguments,
   and classifies what happened.

   A test passes if it returns, fails if an `assert` inside it throws an
   AssertionError, and errors if it throws anything else."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------
;; Finding tests

(defn var-name
  "The symbol a var was defined with, without its namespace."
  [v]
  (:name (meta v)))

(defn find-tests
  "Every public var in the namespace `ns-sym` whose name starts with
   \"test-\", sorted by name so that runs are repeatable."
  [ns-sym]
  (->> (ns-publics ns-sym)
       vals
       (filter #(str/starts-with? (name (var-name %)) "test-"))
       (sort-by var-name)))

;; ---------------------------------------------------------------------
;; Running one test

(defn classify
  "Call `thunk` with no arguments and report how it ended:
   {:outcome :pass}, {:outcome :fail ...} if an assertion failed, or
   {:outcome :error ...} if anything else was thrown."
  [thunk]
  (try
    (thunk)
    {:outcome :pass}
    (catch AssertionError e
      {:outcome :fail :message (.getMessage e)})
    (catch Throwable e
      {:outcome :error :message (str e)})))

(defn run-test
  "Run the test held in var `v` and return its result, tagged with the
   test's name."
  [v]
  (assoc (classify v) :test (var-name v)))

;; ---------------------------------------------------------------------
;; Running them all and reporting

(defn run-tests
  "Run every test in `ns-sym`, in name order, and return the results."
  [ns-sym]
  (mapv run-test (find-tests ns-sym)))

(defn summarize
  "Count how many results had each outcome, e.g. {:pass 2 :fail 1 :error 0}."
  [results]
  (merge {:pass 0 :fail 0 :error 0}
         (frequencies (map :outcome results))))

(defn report
  "Print one line per result followed by a summary line, and return the
   summary. `outcomes` lists the outcomes to show on the summary line,
   in order; it defaults to pass, fail, and error."
  ([results]
   (report [:pass :fail :error] results))
  ([outcomes results]
   (doseq [{:keys [test outcome message]} results]
     (println (format "%-6s %s%s"
                      (name outcome)
                      test
                      (if message
                        (str ": " (first (str/split-lines message)))
                        ""))))
   (let [summary (summarize results)]
     (println (str/join "  " (map #(str (name %) " " (get summary % 0)) outcomes)))
     summary)))

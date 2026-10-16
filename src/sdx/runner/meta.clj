(ns sdx.runner.meta
  "Stage 2: the same runner, but test vars can carry metadata that
   controls what runs.

   - ^:skip on a test means it is not run.
   - ^:only on one or more tests means only those tests are run.
   - a var marked ^:setup is called before every test, and one marked
     ^:teardown is called after every test, whether or not it passed."
  (:require [sdx.runner.simple :as simple]))

;; ---------------------------------------------------------------------
;; Deciding what to run

(defn- decision
  "Whether one test var should :run or be :skip-ped. `any-only?` says
   whether any test in the namespace is marked ^:only."
  [any-only? v]
  (let [m (meta v)]
    (cond
      (:skip m)                        :skip
      (and any-only? (not (:only m)))  :skip
      :else                            :run)))

(defn plan
  "Pair each test var with the decision about it, [var :run] or
   [var :skip], keeping the vars in their original order."
  [vars]
  (let [any-only? (boolean (some #(:only (meta %)) vars))]
    (mapv (fn [v] [v (decision any-only? v)]) vars)))

;; ---------------------------------------------------------------------
;; Setup and teardown

(defn find-hook
  "The first public var in `ns-sym` whose metadata has `flag` set
   (for example :setup), or nil if there is none."
  [ns-sym flag]
  (->> (ns-publics ns-sym)
       vals
       (filter #(flag (meta %)))
       first))

(defn run-test
  "Run the test in var `v` with `setup` called before it and `teardown`
   after it. Teardown runs even if the test fails or throws. A failure in
   either hook is reported as the test's own result."
  [{:keys [setup teardown]} v]
  (assoc (simple/classify
          (fn []
            (when setup (setup))
            (try
              (v)
              (finally
                (when teardown (teardown))))))
         :test (simple/var-name v)))

;; ---------------------------------------------------------------------
;; Putting it together

(defn run-tests
  "Run the tests in `ns-sym` that their metadata allows, with the
   namespace's setup and teardown hooks around each one. Skipped tests
   appear in the results with outcome :skip."
  [ns-sym]
  (let [hooks {:setup (find-hook ns-sym :setup)
               :teardown (find-hook ns-sym :teardown)}]
    (mapv (fn [[v action]]
            (if (= action :run)
              (run-test hooks v)
              {:test (simple/var-name v) :outcome :skip}))
          (plan (simple/find-tests ns-sym)))))

(defn report
  "Print results the way stage 1 does, with skips on the summary line."
  [results]
  (simple/report [:pass :fail :error :skip] results))

;; ---------------------------------------------------------------------
;; How clojure.test does it

(defn find-deftests
  "The vars in `ns-sym` that clojure.test's deftest created. Each one
   carries its test body as a function in its :test metadata."
  [ns-sym]
  (->> (ns-publics ns-sym)
       vals
       (filter #(:test (meta %)))
       (sort-by simple/var-name)))

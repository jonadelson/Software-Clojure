(ns sdx.perf.profile
  "A home-made profiler. Wrap a function, or a block of code, so that
   every call is counted and timed, and the totals pile up in one atom
   that a report can read later.")

(def stats
  "Everything measured so far: a map from label to {:calls n :nanos total}.
   This has to be an atom because calls happen all over the program, at
   different times, and they all need to add to the same totals."
  (atom {}))

(defn reset-stats!
  "Forget everything measured so far."
  []
  (reset! stats {}))

(defn record!
  "Add one call to `label` that took `nanos` nanoseconds."
  [label nanos]
  (swap! stats update label
         (fnil (fn [entry] (-> entry (update :calls inc) (update :nanos + nanos)))
               {:calls 0 :nanos 0})))

(defmacro timed
  "Evaluate `body`, recording how long it took under `label`, even if
   it throws."
  [label & body]
  `(let [start# (System/nanoTime)]
     (try
       ~@body
       (finally
         (record! ~label (- (System/nanoTime) start#))))))

(defn profiled
  "A function that behaves like `f` but records every call under `label`."
  [label f]
  (fn [& args]
    (timed label (apply f args))))

(defn profile-var!
  "Replace the function held by var `v` with a profiled version of
   itself, labelled with the var's name. Does nothing if `v` is already
   profiled. Returns `v`."
  [v]
  (when-not (::original (meta v))
    (let [f @v]
      (alter-meta! v assoc ::original f)
      (alter-var-root v (constantly (profiled (symbol v) f)))))
  v)

(defn unprofile-var!
  "Put back the function that `v` held before `profile-var!`. Returns `v`."
  [v]
  (when-let [f (::original (meta v))]
    (alter-var-root v (constantly f))
    (alter-meta! v dissoc ::original))
  v)

(defn report
  "What has been measured so far: one map per label with :calls,
   :total-ms and :mean-ms, sorted so the biggest total comes first."
  []
  (->> @stats
       (map (fn [[label {:keys [calls nanos]}]]
              {:label label
               :calls calls
               :total-ms (/ nanos 1e6)
               :mean-ms (/ nanos calls 1e6)}))
       (sort-by :total-ms >)
       vec))

(defn print-report
  "Print `report` as a table."
  []
  (println (format "%-40s %10s %12s %12s" "label" "calls" "total ms" "mean ms"))
  (doseq [{:keys [label calls total-ms mean-ms]} (report)]
    (println (format "%-40s %10d %12.3f %12.6f" (str label) calls total-ms mean-ms))))

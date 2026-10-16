(ns sdx.build.stamps
  "Stage 2: rebuild only what is out of date.

   The build takes a map of timestamps alongside the configuration and
   returns the updated map, so the core never looks at a filesystem. A
   name that is absent from the map does not exist yet."
  (:require [sdx.build.topo :as topo]))

(defn stale?
  "True if `target` must be rebuilt: it does not exist, one of its
   dependencies was rebuilt during this run, or one of its dependencies
   has a newer timestamp than it does."
  [config stamps rebuilt target]
  (let [mine (get stamps target)
        depends (get-in config [target :depends])]
    (boolean
     (or (nil? mine)
         (some rebuilt depends)
         (some #(> (get stamps %) mine) depends)))))

(defn build
  "Bring `targets` (default: all of them) up to date as of time `now`.
   Returns {:stamps updated-stamps, :rebuilt [names in build order]}.
   Throws if a target that does not exist has no rule to make it."
  ([config stamps now] (build config stamps now nil))
  ([config stamps now targets]
   (reduce (fn [{:keys [stamps rebuilt] :as state} target]
             (cond
               (not (stale? config stamps (set rebuilt) target))
               state

               (get-in config [target :rule])
               (-> state
                   (assoc-in [:stamps target] now)
                   (update :rebuilt conj target))

               :else
               (throw (ex-info (str "no rule to make " target) {:target target}))))
           {:stamps stamps :rebuilt []}
           (topo/plan config targets))))

(defn commands
  "The rules to run for a `build` result, in order."
  [config {:keys [rebuilt]}]
  (mapv #(get-in config [% :rule]) rebuilt))

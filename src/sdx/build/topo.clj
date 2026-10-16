(ns sdx.build.topo
  "Stage 1: a build configuration is a graph, and building is visiting
   that graph in an order where every target comes after the things it
   depends on.

   A configuration is a map from target name to a map with :depends (a
   vector of other target names) and, usually, :rule (a command to run
   to make the target)."
  (:require [clojure.string :as str]))

(defn check-config
  "Return `config` unchanged if every dependency names a target in it;
   otherwise throw."
  [config]
  (doseq [[target {:keys [depends]}] config
          dep depends]
    (when-not (contains? config dep)
      (throw (ex-info (str target " depends on unknown target " dep)
                      {:target target :missing dep}))))
  config)

(defn dependents
  "A map from each target to the set of targets that depend on it
   directly: the edges of the graph, pointing the other way."
  [config]
  (reduce (fn [m [target {:keys [depends]}]]
            (reduce (fn [m dep] (update m dep conj target)) m depends))
          (zipmap (keys config) (repeat #{}))
          config))

(defn topo-sort
  "Every target in `config`, ordered so that each comes after all of its
   dependencies. When several targets are ready at once, they are taken
   in sorted order so the result is deterministic. Throws if the graph
   has a cycle."
  [config]
  (let [config (check-config config)
        downstream (dependents config)]
    (loop [waiting (into {} (for [[t {:keys [depends]}] config]
                              [t (count (set depends))]))
           ready (into (sorted-set) (for [[t n] waiting :when (zero? n)] t))
           order []]
      (cond
        (seq ready)
        (let [t (first ready)
              waiting (reduce #(update %1 %2 dec) (dissoc waiting t) (downstream t))
              freed (filter #(zero? (waiting %)) (downstream t))]
          (recur waiting (into (disj ready t) freed) (conj order t)))

        (seq waiting)
        (throw (ex-info (str "cycle among " (str/join ", " (sort (keys waiting))))
                        {:cycle (set (keys waiting))}))

        :else order))))

(defn needed
  "The set of targets that building `targets` involves: the targets
   themselves and everything they depend on, directly or indirectly."
  [config targets]
  (loop [todo (vec targets)
         seen #{}]
    (cond
      (empty? todo) seen

      (seen (peek todo)) (recur (pop todo) seen)

      (not (contains? config (peek todo)))
      (throw (ex-info (str "unknown target " (peek todo)) {:target (peek todo)}))

      :else (recur (into (pop todo) (get-in config [(peek todo) :depends]))
                   (conj seen (peek todo))))))

(defn plan
  "The targets to visit, in order, to build `targets`. With no targets,
   every target in the config is built."
  ([config] (topo-sort config))
  ([config targets]
   (if (seq targets)
     (filterv (needed config targets) (topo-sort config))
     (topo-sort config))))

(defn build
  "Build `targets` by calling `(execute target rule)` for each target in
   the plan that has a rule. Returns the names visited, in order."
  [config targets execute]
  (let [order (plan config targets)]
    (doseq [target order
            :let [rule (get-in config [target :rule])]
            :when rule]
      (execute target rule))
    order))

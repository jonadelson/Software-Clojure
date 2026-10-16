(ns sdx.pkg.constraints
  "Stage 3: constraints written as expressions such as \">=1.2\",
   \"<3\", \"=2.0.1\", or \">=1.2,<3\", over dotted version numbers.

   Rather than teach the search about expressions, this stage compiles
   a manifest whose constraints are expressions into one whose
   constraints are sets of acceptable versions, which is what stage 2
   already understands."
  (:require [clojure.string :as str]
            [sdx.pkg.backtrack :as bt]))

;; ---------------------------------------------------------------------
;; Versions

(defn parse-version
  "\"1.2.3\" becomes [1 2 3]. Throws on anything that is not digits
   separated by dots."
  [s]
  (when-not (re-matches #"\d+(\.\d+)*" s)
    (throw (ex-info (str "bad version " (pr-str s)) {:version s})))
  (mapv #(Long/parseLong %) (str/split s #"\.")))

(defn compare-versions
  "Compare two parsed versions component by component, treating missing
   trailing components as zero, so that [1 2] equals [1 2 0] and
   [1 10] is newer than [1 9]."
  [a b]
  (let [width (max (count a) (count b))
        pad (fn [v] (into v (repeat (- width (count v)) 0)))]
    (compare (pad a) (pad b))))

(defn newest-first
  "Version strings sorted from newest to oldest."
  [versions]
  (sort-by parse-version #(compare-versions %2 %1) versions))

;; ---------------------------------------------------------------------
;; Constraint expressions

(def ^:private operators
  {">=" #(>= % 0), "<=" #(<= % 0), "!=" #(not= % 0)
   ">" #(> % 0), "<" #(< % 0), "=" #(= % 0)})

(defn parse-clause
  "One comparison such as \">=1.2\" becomes [op [1 2]]. A bare version
   means equality."
  [s]
  (if-let [[_ op version] (re-matches #"\s*(>=|<=|!=|>|<|=)?\s*([\d.]+)\s*" s)]
    [(or op "=") (parse-version version)]
    (throw (ex-info (str "bad constraint " (pr-str s)) {:constraint s}))))

(defn parse-constraint
  "A constraint is clauses separated by commas, all of which must hold.
   \"*\" or the empty string accepts any version."
  [s]
  (if (contains? #{"*" ""} (str/trim s))
    []
    (mapv parse-clause (str/split s #","))))

(defn meets?
  "True if version string `version` meets every clause of `constraint`."
  [constraint version]
  (let [v (parse-version version)]
    (every? (fn [[op wanted]] ((operators op) (compare-versions v wanted)))
            (parse-constraint constraint))))

(defn allowed-versions
  "The subset of `available` version strings that satisfy `constraint`."
  [constraint available]
  (set (filter #(meets? constraint %) available)))

;; ---------------------------------------------------------------------
;; Putting them together

(defn compile-manifest
  "Replace every constraint expression in `manifest` with the set of the
   dependency's versions that satisfy it. The result is a stage 2
   manifest."
  [manifest]
  (into {}
        (for [[package versions] manifest]
          [package
           (into {}
                 (for [[version depends] versions]
                   [version
                    (into {}
                          (for [[dep constraint] depends]
                            (if-let [available (keys (manifest dep))]
                              [dep (allowed-versions constraint available)]
                              (throw (ex-info (str package " " version
                                                   " depends on unknown package " dep)
                                              {:package package :version version :missing dep})))))]))])))

(defn solve
  "Find every consistent assignment for a manifest with constraint
   expressions, trying newest versions first."
  [manifest]
  (let [compiled (compile-manifest manifest)]
    (bt/solve compiled {:order bt/most-constrained-first
                        :versions-of (fn [package] (newest-first (keys (compiled package))))})))

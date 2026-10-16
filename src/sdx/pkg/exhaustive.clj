(ns sdx.pkg.exhaustive
  "Stage 1: find every consistent set of package versions by trying
   every combination.

   A manifest maps each package name to a map of its versions, and each
   version to the packages it depends on and the versions of each that
   it can work with:

     {\"app\" {\"1\" {\"db\" #{\"1\" \"2\"}}}
      \"db\"  {\"1\" {} \"2\" {}}}

   A candidate is a map from every package name to one chosen version.")

(defn check-manifest
  "Return `manifest` if every dependency names a package in it; otherwise
   throw."
  [manifest]
  (doseq [[package versions] manifest
          [version depends] versions
          [dep _] depends]
    (when-not (contains? manifest dep)
      (throw (ex-info (str package " " version " depends on unknown package " dep)
                      {:package package :version version :missing dep}))))
  manifest)

(defn cartesian
  "A lazy sequence of every way to pick one element from each of
   `colls`, as a list. Picking from no collections has exactly one
   result: picking nothing."
  [colls]
  (if (empty? colls)
    '(())
    (let [tails (cartesian (rest colls))]
      (for [head (first colls)
            tail tails]
        (cons head tail)))))

(defn candidates
  "A lazy sequence of every assignment of one version to every package."
  [manifest]
  (let [names (sort (keys manifest))
        versions (map #(sort (keys (manifest %))) names)]
    (map #(zipmap names %) (cartesian versions))))

(defn candidate-count
  "How many candidates a manifest has: the product of its version counts."
  [manifest]
  (reduce * (map count (vals manifest))))

(defn compatible?
  "True if the versions that `candidate` picks for the dependencies of
   `package` at `version` are all acceptable to it."
  [manifest candidate package version]
  (every? (fn [[dep allowed]] (contains? allowed (candidate dep)))
          (get-in manifest [package version])))

(defn consistent?
  "True if every package in `candidate` is happy with every other."
  [manifest candidate]
  (every? (fn [[package version]] (compatible? manifest candidate package version))
          candidate))

(defn solve
  "Test every candidate. Returns {:solutions [consistent candidates],
   :checked number-of-candidates-tested}."
  [manifest]
  (let [manifest (check-manifest manifest)]
    (reduce (fn [result candidate]
              (cond-> (update result :checked inc)
                (consistent? manifest candidate) (update :solutions conj candidate)))
            {:solutions [] :checked 0}
            (candidates manifest))))

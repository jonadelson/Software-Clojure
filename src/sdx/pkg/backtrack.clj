(ns sdx.pkg.backtrack
  "Stage 2: choose versions one package at a time and give up on a
   partial choice as soon as it breaks a constraint.

   The manifest format is the same as stage 1. The search is a
   depth-first walk of a tree whose levels are packages and whose
   branches are versions; a branch is cut off the moment the versions
   chosen so far cannot be extended."
  (:require [sdx.pkg.exhaustive :as ex]))

(defn fits?
  "Can `package` at `version` join the partial assignment `chosen`?
   Its own constraints must accept whatever is already chosen, and
   everything already chosen must accept it. Packages not yet chosen
   are not consulted."
  [manifest chosen package version]
  (and (every? (fn [[dep allowed]]
                 (or (not (contains? chosen dep))
                     (contains? allowed (chosen dep))))
               (get-in manifest [package version]))
       (every? (fn [[other other-version]]
                 (let [allowed (get-in manifest [other other-version package])]
                   (or (nil? allowed)
                       (contains? allowed version))))
               chosen)))

(defn search
  "Extend `chosen` with a version of each package in `order`, in turn,
   accumulating into `result` every complete assignment reached and a
   count of every version tried."
  [manifest order versions-of chosen result]
  (if (empty? order)
    (update result :solutions conj chosen)
    (let [package (first order)]
      (reduce (fn [result version]
                (let [result (update result :checked inc)]
                  (if (fits? manifest chosen package version)
                    (search manifest (rest order) versions-of
                            (assoc chosen package version) result)
                    result)))
              result
              (versions-of package)))))

(defn alphabetical
  "Package names in sorted order: the simplest search order."
  [manifest]
  (sort (keys manifest)))

(defn fewest-versions-first
  "Package names ordered so that the ones with the fewest versions come
   first, with ties broken alphabetically."
  [manifest]
  (sort-by (fn [package] [(count (manifest package)) package]) (keys manifest)))

(defn most-constrained-first
  "Package names ordered so that the ones mentioned in the most
   constraints, as either side, come first."
  [manifest]
  (let [mentions (frequencies
                  (for [[package versions] manifest
                        [_ depends] versions
                        [dep _] depends
                        name [package dep]]
                    name))]
    (sort-by (fn [package] [(- (get mentions package 0)) package]) (keys manifest))))

(defn solve
  "Find every consistent assignment by backtracking. Options:
   :order, a function from manifest to the sequence of package names to
   decide, in order (default alphabetical); :versions-of, a function
   from package name to the versions to try, in order (default sorted).
   Returns {:solutions [...], :checked number-of-versions-tried}."
  ([manifest] (solve manifest {}))
  ([manifest {:keys [order versions-of]
              :or {order alphabetical}}]
   (let [manifest (ex/check-manifest manifest)
         versions-of (or versions-of (fn [package] (sort (keys (manifest package)))))]
     (search manifest (order manifest) versions-of {} {:solutions [] :checked 0}))))

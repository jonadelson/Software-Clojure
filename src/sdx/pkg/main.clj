(ns sdx.pkg.main
  "Command-line entry point: read a manifest with constraint expressions
   from an EDN file and print every consistent set of versions.

     clojure -M -m sdx.pkg.main resources/pkg/manifest.edn"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [sdx.pkg.constraints :as constraints])
  (:gen-class))

(defn format-solution
  "\"app 1.1, db 2.0, web 3.0\""
  [solution]
  (str/join ", " (for [[package version] (sort solution)] (str package " " version))))

(defn -main [manifest-path]
  (let [manifest (edn/read-string (slurp manifest-path))
        {:keys [solutions checked]} (constraints/solve manifest)]
    (doseq [solution solutions]
      (println (format-solution solution)))
    (println (count solutions) "solutions," checked "versions tried")))

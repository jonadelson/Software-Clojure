(ns sdx.dupes.main
  "A command-line duplicate finder.

   Usage: clojure -M -m sdx.dupes.main DIRECTORY"
  (:require [clojure.string :as str]
            [sdx.dupes.hash :as hash]))

(defn report
  "A printable report of the duplicate groups under `root`: one path per
   line, groups separated by blank lines, or a note that there are none."
  [root]
  (let [groups (hash/duplicate-groups root)]
    (if (empty? groups)
      "No duplicates found."
      (str/join "\n\n" (map #(str/join "\n" %) groups)))))

(defn -main
  "Print the duplicate groups under the directory named on the command line."
  [& args]
  (if (= 1 (count args))
    (println (report (first args)))
    (println "usage: clojure -M -m sdx.dupes.main DIRECTORY")))

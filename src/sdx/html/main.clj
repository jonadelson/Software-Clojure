(ns sdx.html.main
  "Command-line entry point: validate HTML files and print what is wrong.

       clojure -M -m sdx.html.main resources/html/sample.html"
  (:require [sdx.html.check :as c]
            [sdx.html.parser :as p]))

(defn report
  "Validate the file at `filename`, print one line per problem, and return
   the number of problems found. A file that does not parse counts as one."
  [filename]
  (try
    (let [violations (c/validate (p/parse (slurp filename)))]
      (doseq [{:keys [path message]} violations]
        (println (str filename ":" (c/path->string path) ": " message)))
      (count violations))
    (catch clojure.lang.ExceptionInfo e
      (println (str filename ": parse error at " (:pos (ex-data e)) ": " (ex-message e)))
      1)))

(defn -main
  "Validate each file named on the command line; exit 1 if any had problems."
  [& filenames]
  (let [problems (reduce + 0 (map report filenames))]
    (System/exit (if (zero? problems) 0 1))))

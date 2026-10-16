(ns sdx.template.main
  "Command-line entry point: expand a template with variables read from an EDN file.

       clojure -M -m sdx.template.main resources/template/report.html resources/template/report.edn"
  (:require [clojure.edn :as edn]
            [sdx.template :as t]))

(defn expand-files
  "Expand the template in `template-file` with the variables in `vars-file`."
  [template-file vars-file]
  (t/expand (slurp template-file) (edn/read-string (slurp vars-file))))

(defn -main
  "Print the expansion of a template file using an EDN file of variables."
  [template-file vars-file]
  (print (expand-files template-file vars-file))
  (flush))

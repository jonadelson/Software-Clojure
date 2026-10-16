(ns sdx.interp.main
  "Run a program stored as EDN in a file:

       clojure -M -m sdx.interp.main resources/interp/countdown.edn"
  (:require [clojure.edn :as edn]
            [sdx.interp.array]
            [sdx.interp.core :as core]
            [sdx.interp.ops]))

(defn run-file
  "Read one EDN program from `path` and evaluate it, in a fresh
   environment unless one is given. Returns the program's value."
  ([path] (run-file path (core/make-env)))
  ([path env]
   (core/evaluate env (edn/read-string (slurp path)))))

(defn -main
  "Run the program in the named file."
  [path]
  (run-file path)
  (flush))

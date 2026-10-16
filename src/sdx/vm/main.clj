(ns sdx.vm.main
  "Command-line entry point: assemble a file and run it.

       clojure -M -m sdx.vm.main resources/vm/count_down.as"
  (:require [sdx.vm.assembler :as asm]
            [sdx.vm.machine :as vm]))

(defn run-file
  "Assemble and run the program in the file at `path`, returning the
   final state of the machine."
  [path]
  (-> (slurp path) asm/assemble vm/new-machine vm/run))

(defn -main
  "Assemble and run one program, printing whatever it prints."
  [& [path]]
  (if-not path
    (binding [*out* *err*]
      (println "usage: clojure -M -m sdx.vm.main program.as"))
    (try
      (doseq [line (:output (run-file path))]
        (println line))
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*]
          (println "error:" (ex-message e)))))))

(ns sdx.debugger.main
  "Command-line entry point: assemble a file and debug it at the terminal.

       clojure -M -m sdx.debugger.main resources/vm/count_down.as"
  (:require [sdx.debugger.breakpoints :as bp]
            [sdx.vm.assembler :as asm]
            [sdx.vm.machine :as vm]))

(def console-io
  "Read commands from the keyboard, after a prompt, and print replies."
  {:read (fn [] (print "> ") (flush) (read-line))
   :write println})

(defn -main
  "Assemble one program and start a debugging session on it."
  [& [path]]
  (if-not path
    (binding [*out* *err*]
      (println "usage: clojure -M -m sdx.debugger.main program.as"))
    (let [items (asm/parse (slurp path))
          labels (asm/label-addresses items)
          machine (vm/new-machine (asm/assemble-items items))]
      (bp/debug machine labels console-io)
      nil)))

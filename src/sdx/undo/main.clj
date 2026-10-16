(ns sdx.undo.main
  "Command-line entry point: clojure -M -m sdx.undo.main FILE"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sdx.undo.snapshot :as snap]
            [sdx.viewer.ansi :as ansi]
            [sdx.viewer.screen :as scr]))

(defn- read-lines
  "The lines of the file at `path`, or none if it does not exist yet."
  [path]
  (if (.exists (io/file path))
    (str/split-lines (slurp path))
    []))

(defn -main
  "Edit FILE in the terminal. Type to insert, Backspace to delete, Enter
   to split a line, arrows to move, ctrl-z to undo, ctrl-y to redo and
   ctrl-q to quit. The file is written back only if it changed."
  [& [path]]
  (if-not path
    (binding [*out* *err*]
      (println "usage: clojure -M -m sdx.undo.main FILE"))
    (let [original (read-lines path)
          final (ansi/with-terminal
                  (fn [screen]
                    (let [[height _] (scr/dimensions screen)]
                      (snap/run screen (snap/make-state original height)))))]
      (when (not= original (:lines final))
        (spit path (str (str/join "\n" (:lines final)) "\n"))
        (println "wrote" path))
      nil)))

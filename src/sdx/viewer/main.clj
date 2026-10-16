(ns sdx.viewer.main
  "Command-line entry point: clojure -M -m sdx.viewer.main FILE"
  (:require [clojure.string :as str]
            [sdx.viewer.ansi :as ansi]
            [sdx.viewer.core :as core]))

(defn -main
  "View FILE in the terminal. Move with the arrow keys or h/j/k/l, page
   with Page Up and Page Down, and press q to quit."
  [& [path]]
  (if-not path
    (binding [*out* *err*]
      (println "usage: clojure -M -m sdx.viewer.main FILE"))
    (let [lines (str/split-lines (slurp path))]
      (ansi/with-terminal (fn [screen] (core/start screen lines)))
      nil)))

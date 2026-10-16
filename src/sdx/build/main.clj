(ns sdx.build.main
  "The command-line build tool: read an EDN configuration, look at the
   filesystem for timestamps, and print or run the commands needed.

     clojure -M -m sdx.build.main resources/build/example.edn app
     clojure -M -m sdx.build.main --run resources/build/example.edn app

   Without --run the commands are printed but not executed. Commands
   are run with `sh -c` in the configuration file's directory, which
   assumes a Unix-like system."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [sdx.build.pattern :as pattern]
            [sdx.build.stamps :as stamps])
  (:gen-class))

(defn disk-stamps
  "Modification times for every one of `names` that exists as a file
   under `dir`. Names that do not exist are left out."
  [dir names]
  (into {}
        (for [name names
              :let [file (io/file dir name)]
              :when (.exists file)]
          [name (.lastModified file)])))

(defn run-command!
  "Run `command` with the shell in `dir`, inheriting the terminal.
   Throws if it exits with a non-zero status."
  [dir command]
  (let [process (-> (ProcessBuilder. ["sh" "-c" command])
                    (.directory (io/file dir))
                    (.inheritIO)
                    (.start))
        status (.waitFor process)]
    (when-not (zero? status)
      (throw (ex-info (str "command failed with status " status ": " command)
                      {:command command :status status})))))

(defn commands-for
  "The commands needed to bring `targets` in the configuration at
   `config-path` up to date, judging by the files next to it."
  [config-path targets]
  (let [config (edn/read-string (slurp config-path))
        dir (.getParentFile (.getAbsoluteFile (io/file config-path)))
        roots (or (seq targets) (keys (:targets config)))
        plain (pattern/expand config roots)
        result (stamps/build plain (disk-stamps dir (keys plain))
                             (System/currentTimeMillis) roots)]
    {:dir dir :commands (stamps/commands plain result)}))

(defn -main [& args]
  (let [run? (= "--run" (first args))
        [config-path & targets] (if run? (rest args) args)
        {:keys [dir commands]} (commands-for config-path targets)]
    (if (empty? commands)
      (println "nothing to do")
      (doseq [command commands]
        (println command)
        (when run? (run-command! dir command))))))

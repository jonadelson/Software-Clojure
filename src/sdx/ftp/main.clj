(ns sdx.ftp.main
  "Command-line entry point for the file transfer tool.

     clojure -M -m sdx.ftp.main serve DIR [PORT]
     clojure -M -m sdx.ftp.main send HOST PORT FILE..."
  (:require [sdx.ftp.chunked :as chunked]
            [sdx.ftp.concurrent :as concurrent]))

(defn- usage []
  (println "usage: clojure -M -m sdx.ftp.main serve DIR [PORT]")
  (println "       clojure -M -m sdx.ftp.main send HOST PORT FILE...")
  (System/exit 1))

(defn -main
  "Run the server (`serve`) or send files to one (`send`)."
  [& [command & args]]
  (case command
    "serve" (let [[dir port] args
                  server (concurrent/start-server
                          dir {:port (if port (Long/parseLong port) 0)})]
              (println "storing files in" dir "on port" (:port server))
              ;; A promise nobody delivers: block until the process is killed.
              @(promise))
    "send" (let [[host port & files] args]
             (doseq [f files]
               (println f "->" (chunked/send-file host (Long/parseLong port) f)
                        "bytes")))
    (usage)))

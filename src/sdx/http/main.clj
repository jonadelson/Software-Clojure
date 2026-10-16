(ns sdx.http.main
  "Command-line entry point for the web server.

     clojure -M -m sdx.http.main ROOT [PORT]"
  (:require [sdx.http.files :as files]
            [sdx.http.handlers :as handlers]
            [sdx.http.hello :as hello]))

(defn log-line
  "Print one log entry in the usual one-request-per-line style."
  [{:keys [method path status ms]}]
  (println (format "%-4s %-30s %d %.1fms"
                   (.toUpperCase (name method)) path status ms)))

(defn -main
  "Serve the files under ROOT on PORT (default 8080) until killed."
  [& [root port]]
  (when-not root
    (println "usage: clojure -M -m sdx.http.main ROOT [PORT]")
    (System/exit 1))
  (let [handler (handlers/app (files/file-handler root) log-line)
        server (hello/start-server handler {:port (if port (Long/parseLong port) 8080)})]
    (println (str "serving " root " on http://127.0.0.1:" (:port server) "/"))
    ;; A promise nobody delivers: block until the process is killed.
    @(promise)))

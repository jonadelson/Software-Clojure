(ns sdx.ftp.concurrent
  "Stage 3 of the file transfer tool: every connection is handled on its
   own thread, a client that stops talking is cut off by a timeout, and
   stopping the server closes every connection it still has open."
  (:require [clojure.java.io :as io]
            [sdx.ftp.chunked :as chunked])
  (:import [java.io IOException]
           [java.net InetAddress ServerSocket Socket]))

(defn report-error
  "The default error reporter: one line on standard error."
  [^Throwable e]
  (binding [*out* *err*]
    (println "client failed:" (.getMessage e))))

(defn serve
  "Accept connections on `server` until it is closed. Each connection is
   handled by `(handle socket)` on its own thread and dropped if it is
   silent for `timeout-ms`. `connections` is an atom holding a map from
   open socket to the future handling it; `on-error` is called with any
   exception a handler throws."
  [^ServerSocket server handle {:keys [connections timeout-ms on-error]}]
  (while (not (.isClosed server))
    (when-let [^Socket sock (try (.accept server)
                                 (catch IOException _ nil))]
      (.setSoTimeout sock timeout-ms)
      (let [fut (future
                  (try
                    (handle sock)
                    (catch Exception e (on-error e))
                    (finally
                      (.close sock)
                      (swap! connections dissoc sock))))]
        (swap! connections assoc sock fut)))))

(defn start-server
  "Start a concurrent server storing files in `dir`. Options: :port
   (default 0), :timeout-ms (default 10000) and :on-error (default:
   print to standard error). Returns a map with the :port chosen, the
   :connections atom and a :stop function."
  ([dir] (start-server dir {}))
  ([dir {:keys [port timeout-ms on-error]
         :or {port 0 timeout-ms 10000 on-error report-error}}]
   (.mkdirs (io/file dir))
   (let [server (ServerSocket. (int port) 50 (InetAddress/getLoopbackAddress))
         connections (atom {})
         worker (future (serve server #(chunked/receive-file % dir)
                               {:connections connections
                                :timeout-ms timeout-ms
                                :on-error on-error}))]
     {:port (.getLocalPort server)
      :connections connections
      :stop (fn []
              (.close server)
              @worker
              (doseq [[^Socket sock fut] @connections]
                (.close sock)
                (deref fut 1000 nil)))})))

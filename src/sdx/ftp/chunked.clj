(ns sdx.ftp.chunked
  "Stage 2 of the file transfer tool: the file's bytes travel through a
   fixed-size buffer instead of being read into memory all at once, so
   the size of a file is limited by the disk rather than by the heap.

   The name is still a frame. The body is an 8-byte length followed by
   exactly that many bytes, copied through in chunks by both sides."
  (:require [clojure.java.io :as io]
            [sdx.ftp.simple :as simple :refer [data-in data-out read-str
                                               safe-name write-str]])
  (:import [java.io InputStream OutputStream]
           [java.net InetAddress ServerSocket Socket]))

(def buffer-size
  "How many bytes travel in one chunk. Any size works; this one is typical."
  4096)

(defn copy-n
  "Copy exactly `n` bytes from `in` to `out` through a buffer of
   `buffer-size` bytes. Returns `n`. Throws if the input ends early."
  [^InputStream in ^OutputStream out n]
  (let [buf (byte-array buffer-size)]
    (loop [remaining n]
      (if (zero? remaining)
        n
        (let [want (int (min remaining buffer-size))
              got (.read in buf 0 want)]
          (when (neg? got)
            (throw (ex-info "stream ended early"
                            {:expected n :missing remaining})))
          (.write out buf 0 got)
          (recur (- remaining got)))))))

;; ---------------------------------------------------------------------
;; The client.

(defn send-file
  "Send the file at `path` to `host`:`port` in chunks. Returns the number
   of bytes the server says it stored."
  [^String host port path]
  (let [f (io/file path)]
    (with-open [sock (Socket. host (int port))
                fin (io/input-stream f)]
      (let [in (data-in sock)
            out (data-out sock)]
        (write-str out (.getName f))
        (.writeLong out (.length f))
        (copy-n fin out (.length f))
        (.flush out)
        (.readLong in)))))

;; ---------------------------------------------------------------------
;; The server.

(defn receive-file
  "Read one file from the client on `sock` in chunks and store it in
   `dir`. Replies with the number of bytes stored and returns the file."
  [^Socket sock dir]
  (with-open [sock sock]
    (let [in (data-in sock)
          out (data-out sock)
          name (safe-name (read-str in))
          size (.readLong in)
          target (io/file dir name)]
      (when (neg? size)
        (throw (ex-info "bad file size" {:size size})))
      (with-open [fout (io/output-stream target)]
        (copy-n in fout size))
      (.writeLong out size)
      (.flush out)
      target)))

(defn start-server
  "Start a chunked server storing files in `dir`. The accept loop is the
   one from stage 1; only what happens on each connection has changed."
  ([dir] (start-server dir 0))
  ([dir port]
   (.mkdirs (io/file dir))
   (let [server (ServerSocket. (int port) 50 (InetAddress/getLoopbackAddress))
         worker (future (simple/serve server #(receive-file % dir)))]
     {:port (.getLocalPort server)
      :stop (fn []
              (.close server)
              @worker)})))

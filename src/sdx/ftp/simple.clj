(ns sdx.ftp.simple
  "Stage 1 of the file transfer tool: a client that sends one whole file
   and a server that stores it, one client at a time.

   On the wire, the client sends two frames (the file's name, then its
   bytes), each a 4-byte big-endian length followed by that many bytes.
   The server answers with one 8-byte number: how many bytes it stored."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedInputStream BufferedOutputStream
            DataInputStream DataOutputStream]
           [java.net InetAddress ServerSocket Socket]
           [java.nio.file Files]))

;; ---------------------------------------------------------------------
;; Frames: the unit of conversation.

(def max-frame
  "The largest frame we are willing to read: 16 MiB."
  (* 16 1024 1024))

(defn write-frame
  "Write `bs` to `out` as one frame: a 4-byte length, then the bytes."
  [^DataOutputStream out ^bytes bs]
  (.writeInt out (alength bs))
  (.write out bs 0 (alength bs)))

(defn read-frame
  "Read one frame from `in` and return its bytes. Throws if the length
   is negative or larger than `max-frame`, which means the stream is
   corrupt or the sender is not to be trusted."
  ^bytes [^DataInputStream in]
  (let [n (.readInt in)]
    (when (or (neg? n) (> n max-frame))
      (throw (ex-info "bad frame length" {:length n})))
    (let [buf (byte-array n)]
      (.readFully in buf)
      buf)))

(defn write-str
  "Write the string `s` as a UTF-8 frame."
  [out ^String s]
  (write-frame out (.getBytes s "UTF-8")))

(defn read-str
  "Read one frame and decode it as UTF-8."
  [in]
  (String. (read-frame in) "UTF-8"))

;; ---------------------------------------------------------------------
;; Streams: how we talk to a socket.

(defn data-in
  "A buffered DataInputStream reading from `sock`."
  ^DataInputStream [^Socket sock]
  (DataInputStream. (BufferedInputStream. (.getInputStream sock))))

(defn data-out
  "A buffered DataOutputStream writing to `sock`. Nothing reaches the
   network until it is flushed."
  ^DataOutputStream [^Socket sock]
  (DataOutputStream. (BufferedOutputStream. (.getOutputStream sock))))

;; ---------------------------------------------------------------------
;; The client.

(defn send-file
  "Send the file at `path` to the server at `host`:`port`. Returns the
   number of bytes the server says it stored."
  [^String host port path]
  (let [f (io/file path)]
    (with-open [sock (Socket. host (int port))]
      (let [in (data-in sock)
            out (data-out sock)]
        (write-str out (.getName f))
        (write-frame out (Files/readAllBytes (.toPath f)))
        (.flush out)
        (.readLong in)))))

;; ---------------------------------------------------------------------
;; The server.

(defn safe-name
  "Check that a file name sent by a client is a plain name and not a
   path. Returns the name, or throws."
  [^String name]
  (if (or (str/blank? name)
          (#{"." ".."} name)
          (re-find #"[/\\\u0000]" name))
    (throw (ex-info "unsafe file name" {:name name}))
    name))

(defn receive-file
  "Read one file from the client on `sock` and store it in `dir`. Replies
   with the number of bytes stored and returns the stored file."
  [^Socket sock dir]
  (with-open [sock sock]
    (let [in (data-in sock)
          out (data-out sock)
          name (safe-name (read-str in))
          data (read-frame in)
          target (io/file dir name)]
      (with-open [fout (io/output-stream target)]
        (.write fout data))
      (.writeLong out (alength data))
      (.flush out)
      target)))

(defn serve
  "Accept connections on `server` one after another until it is closed,
   calling `(handle socket)` for each. A connection that fails is dropped
   and the loop carries on."
  [^ServerSocket server handle]
  (while (not (.isClosed server))
    (try
      (handle (.accept server))
      (catch Exception _ nil))))

(defn start-server
  "Start a server that stores received files in `dir`, listening on the
   loopback address. Port 0, the default, lets the operating system pick
   a free port. Returns a map with the :port actually chosen and a :stop
   function that shuts the server down and waits for it to finish."
  ([dir] (start-server dir 0))
  ([dir port]
   (.mkdirs (io/file dir))
   (let [server (ServerSocket. (int port) 50 (InetAddress/getLoopbackAddress))
         worker (future (serve server #(receive-file % dir)))]
     {:port (.getLocalPort server)
      :stop (fn []
              (.close server)
              @worker)})))

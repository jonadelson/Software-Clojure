(ns sdx.http.hello
  "Stage 1 of the web server: read an HTTP request from a socket into a
   map, hand the map to a function, and write the map that comes back
   out as an HTTP response. The only function so far says hello.

   A request is {:method :get :uri \"/a?b=1\" :path \"/a\" :query \"b=1\"
   :version \"HTTP/1.1\" :headers {\"host\" \"...\"} :body bytes-or-nil}.
   A response is {:status 200 :headers {\"Content-Type\" \"...\"} :body
   string-or-bytes-or-nil}."
  (:require [clojure.string :as str])
  (:import [java.io BufferedInputStream BufferedOutputStream
            ByteArrayOutputStream InputStream IOException OutputStream]
           [java.net InetAddress ServerSocket Socket]))

;; ---------------------------------------------------------------------
;; Reading a request.

(defn read-http-line
  "Read one line from `in`. HTTP lines end with CRLF; a bare LF is
   tolerated. Returns the line without its terminator, or nil if the
   stream ended before any bytes arrived."
  [^InputStream in]
  (let [buf (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) (when (pos? (.size buf)) (.toString buf "ISO-8859-1"))
          (= b 10) (str/replace (.toString buf "ISO-8859-1") #"\r$" "")
          :else (do (.write buf b) (recur)))))))

(defn parse-request-line
  "Split \"GET /path?q=1 HTTP/1.1\" into a map. Throws ex-info carrying
   {:status 400} if the line is not shaped like that."
  [line]
  (let [[method target version :as parts] (str/split line #" ")]
    (when (or (not= 3 (count parts))
              (not (str/starts-with? version "HTTP/")))
      (throw (ex-info (str "malformed request line: " (pr-str line))
                      {:status 400 :line line})))
    (let [[path query] (str/split target #"\?" 2)]
      {:method (keyword (str/lower-case method))
       :uri target
       :path path
       :query query
       :version version})))

(defn parse-header
  "Split \"Name: value\" into [\"name\" \"value\"], lower-casing the name
   because header names are case-insensitive."
  [line]
  (let [[name value] (str/split line #":" 2)]
    (when (nil? value)
      (throw (ex-info (str "malformed header: " (pr-str line))
                      {:status 400 :line line})))
    [(str/lower-case (str/trim name)) (str/trim value)]))

(defn read-headers
  "Read header lines up to the blank line and return them as a map."
  [in]
  (loop [headers {}]
    (let [line (read-http-line in)]
      (if (str/blank? line)
        headers
        (recur (conj headers (parse-header line)))))))

(defn read-body
  "Read the request body: exactly Content-Length bytes, or nil if the
   request has no Content-Length header."
  [^InputStream in headers]
  (when-let [length (get headers "content-length")]
    (let [n (try (Long/parseLong length)
                 (catch NumberFormatException _
                   (throw (ex-info (str "bad Content-Length: " (pr-str length))
                                   {:status 400}))))
          buf (byte-array n)]
      (loop [offset 0]
        (when (< offset n)
          (let [got (.read in buf offset (- n offset))]
            (when (neg? got)
              (throw (ex-info "body ended early" {:status 400})))
            (recur (+ offset got)))))
      buf)))

(defn parse-request
  "Read one HTTP request from `in` into a map. Returns nil if the stream
   is empty, which happens when a client connects and goes away. Throws
   ex-info carrying {:status 400} if the request is malformed."
  [in]
  (when-let [line (read-http-line in)]
    (let [request (parse-request-line line)
          headers (read-headers in)]
      (assoc request
             :headers headers
             :body (read-body in headers)))))

;; ---------------------------------------------------------------------
;; Writing a response.

(def reason-phrases
  "The words that go after the status code on the first line."
  {200 "OK"
   201 "Created"
   204 "No Content"
   301 "Moved Permanently"
   302 "Found"
   304 "Not Modified"
   400 "Bad Request"
   403 "Forbidden"
   404 "Not Found"
   405 "Method Not Allowed"
   500 "Internal Server Error"})

(defn body-bytes
  "The bytes of a response body, which may be a string, a byte array or nil."
  ^bytes [body]
  (cond
    (nil? body) (byte-array 0)
    (string? body) (.getBytes ^String body "UTF-8")
    (bytes? body) body
    :else (throw (ex-info "unsupported body" {:body body}))))

(defn write-response
  "Write `response` to `out` as an HTTP/1.1 response and flush it.
   Content-Length is computed from the body; Connection: close tells the
   client not to expect a second response on this connection."
  [^OutputStream out {:keys [status headers body] :or {status 200}}]
  (let [data (body-bytes body)
        headers (assoc headers
                       "Content-Length" (str (alength data))
                       "Connection" "close")
        head (str "HTTP/1.1 " status " " (get reason-phrases status "Unknown") "\r\n"
                  (str/join (for [[k v] headers] (str k ": " v "\r\n")))
                  "\r\n")]
    (.write out (.getBytes head "ISO-8859-1"))
    (.write out data)
    (.flush out)))

(defn text-response
  "A plain-text response with the given status."
  [status text]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str text "\n")})

;; ---------------------------------------------------------------------
;; The simplest handler.

(defn hello
  "A handler that greets everyone the same way, whatever they asked for."
  [_request]
  (text-response 200 "Hello, world!"))

;; ---------------------------------------------------------------------
;; One connection.

(defn handle-connection
  "Read one request from `sock`, answer it with `(handler request)`, and
   close the connection. A request we cannot parse gets a 400; an
   exception that carries a :status in its data is turned into a
   response with that status."
  [^Socket sock handler]
  (with-open [sock sock]
    (let [in (BufferedInputStream. (.getInputStream sock))
          out (BufferedOutputStream. (.getOutputStream sock))]
      (try
        (when-let [request (parse-request in)]
          (write-response out (handler request)))
        (catch clojure.lang.ExceptionInfo e
          (if-let [status (:status (ex-data e))]
            (write-response out (text-response status (ex-message e)))
            (throw e)))))))

;; ---------------------------------------------------------------------
;; The server.

(defn report-error
  "The default error reporter: one line on standard error."
  [^Throwable e]
  (binding [*out* *err*]
    (println "connection failed:" (.getMessage e))))

(defn serve
  "Accept connections on `server` until it is closed, serving each one
   on its own thread with `handler` and dropping it if it is silent for
   `timeout-ms`. `connections` is an atom mapping each open socket to
   the future serving it; `on-error` sees any exception that escapes."
  [^ServerSocket server handler {:keys [connections timeout-ms on-error]}]
  (while (not (.isClosed server))
    (when-let [^Socket sock (try (.accept server)
                                 (catch IOException _ nil))]
      (.setSoTimeout sock timeout-ms)
      (let [fut (future
                  (try
                    (handle-connection sock handler)
                    (catch Exception e (on-error e))
                    (finally
                      (.close sock)
                      (swap! connections dissoc sock))))]
        (swap! connections assoc sock fut)))))

(defn start-server
  "Start serving `handler` on the loopback address. Options: :port
   (default 0, meaning let the operating system choose), :timeout-ms
   (default 10000) and :on-error (default: print to standard error).
   Returns a map with the :port chosen and a :stop function."
  ([handler] (start-server handler {}))
  ([handler {:keys [port timeout-ms on-error]
             :or {port 0 timeout-ms 10000 on-error report-error}}]
   (let [server (ServerSocket. (int port) 50 (InetAddress/getLoopbackAddress))
         connections (atom {})
         worker (future (serve server handler {:connections connections
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

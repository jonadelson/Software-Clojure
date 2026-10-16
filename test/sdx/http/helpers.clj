(ns sdx.http.helpers
  "Shared helpers for the web server tests: temporary directories, an
   HTTP client built on java.net.HttpURLConnection, and a way to send
   raw bytes to a server and read back whatever it answers."
  (:require [clojure.java.io :as io])
  (:import [java.io File]
           [java.net HttpURLConnection Socket URI]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn temp-dir
  "A fresh temporary directory."
  ^File []
  (.toFile (Files/createTempDirectory "sdx-http" (make-array FileAttribute 0))))

(defn delete-tree!
  "Delete a file or a directory and everything under it."
  [^File f]
  (when (.isDirectory f)
    (run! delete-tree! (.listFiles f)))
  (.delete f))

(defn with-temp-dir
  "Call `(f dir)` with a fresh temporary directory and delete it afterwards."
  [f]
  (let [dir (temp-dir)]
    (try
      (f dir)
      (finally
        (delete-tree! dir)))))

(defn write-file!
  "Write the string `text` to `f`, creating parent directories. Returns `f`."
  ^File [^File f ^String text]
  (io/make-parents f)
  (spit f text)
  f)

(defn http
  "Make a request with HttpURLConnection and return a map with the
   :status, the :content-type header and the :body as a string."
  ([method port path] (http method port path nil))
  ([method port path ^String body]
   (let [url (.toURL (URI. (str "http://127.0.0.1:" port path)))
         conn ^HttpURLConnection (.openConnection url)]
     (.setRequestMethod conn method)
     (.setConnectTimeout conn 5000)
     (.setReadTimeout conn 5000)
     (when body
       (.setDoOutput conn true)
       (with-open [out (.getOutputStream conn)]
         (.write out (.getBytes body "UTF-8"))))
     (let [status (.getResponseCode conn)
           stream (if (< status 400) (.getInputStream conn) (.getErrorStream conn))
           data (if stream
                  (with-open [in stream] (.readAllBytes in))
                  (byte-array 0))]
       (.disconnect conn)
       {:status status
        :content-type (.getHeaderField conn "Content-Type")
        :server (.getHeaderField conn "Server")
        :body (String. ^bytes data "UTF-8")}))))

(defn raw
  "Send `text` to the server byte for byte, tell it we have nothing more
   to say, and return everything it sends back before it hangs up."
  [port ^String text]
  (with-open [sock (Socket. "127.0.0.1" (int port))]
    (.setSoTimeout sock 5000)
    (let [out (.getOutputStream sock)]
      (.write out (.getBytes text "ISO-8859-1"))
      (.flush out)
      (.shutdownOutput sock)
      (String. (.readAllBytes (.getInputStream sock)) "ISO-8859-1"))))

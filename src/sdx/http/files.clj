(ns sdx.http.files
  "Stage 2 of the web server: a handler that serves files from a
   directory, with a content type chosen by extension, 404 for files
   that do not exist and 403 for paths that try to climb out of the
   directory."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sdx.http.hello :as hello])
  (:import [java.io ByteArrayOutputStream File]
           [java.nio.file Files Path]))

;; ---------------------------------------------------------------------
;; What kind of thing is this file?

(def mime-types
  "Content types by file extension. Anything else is sent as plain bytes."
  {"html" "text/html; charset=utf-8"
   "htm"  "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "json" "application/json"
   "txt"  "text/plain; charset=utf-8"
   "md"   "text/markdown; charset=utf-8"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "svg"  "image/svg+xml"
   "ico"  "image/x-icon"
   "pdf"  "application/pdf"})

(defn content-type
  "The content type for a file name, chosen by its extension."
  [name]
  (let [ext (second (re-find #"\.([^./]+)$" name))]
    (get mime-types (some-> ext str/lower-case) "application/octet-stream")))

;; ---------------------------------------------------------------------
;; From a request path to a file, carefully.

(defn percent-decode
  "Replace each %XX escape in `s` with the byte it stands for and decode
   the result as UTF-8, so that \"/caf%C3%A9.txt\" becomes \"/café.txt\"."
  [^String s]
  (let [out (ByteArrayOutputStream.)
        n (count s)]
    (loop [i 0]
      (if (>= i n)
        (.toString out "UTF-8")
        (let [c (.charAt s i)]
          (if (and (= c \%) (<= (+ i 3) n))
            (let [code (try (Integer/parseInt (subs s (inc i) (+ i 3)) 16)
                            (catch NumberFormatException _
                              (throw (ex-info (str "bad escape in " (pr-str s))
                                              {:status 400 :path s}))))]
              (.write out (int code))
              (recur (+ i 3)))
            (do (.write out (.getBytes (str c) "UTF-8"))
                (recur (inc i)))))))))

(defn resolve-path
  "Turn a request path into a File under `root`, or nil if the path
   would escape the root. A directory stands for its index.html."
  [^Path root path]
  (let [relative (str/replace (percent-decode path) #"^/+" "")
        target (.normalize (.resolve root relative))]
    (when (.startsWith target root)
      (let [file (.toFile target)]
        (if (.isDirectory file)
          (io/file file "index.html")
          file)))))

;; ---------------------------------------------------------------------
;; The handler.

(defn file-response
  "A 200 response carrying the contents of `file`."
  [^File file]
  {:status 200
   :headers {"Content-Type" (content-type (.getName file))}
   :body (Files/readAllBytes (.toPath file))})

(defn file-handler
  "A handler that serves the files below the directory `root`."
  [root]
  (let [root (-> (io/file root) .toPath .toAbsolutePath .normalize)]
    (fn [{:keys [method path]}]
      (if (not= :get method)
        (hello/text-response 405 "Method Not Allowed")
        (let [file (resolve-path root path)]
          (cond
            (nil? file) (hello/text-response 403 "Forbidden")
            (not (.isFile ^File file)) (hello/text-response 404 "Not Found")
            :else (file-response file)))))))

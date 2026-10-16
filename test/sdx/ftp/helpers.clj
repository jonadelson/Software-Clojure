(ns sdx.ftp.helpers
  "Shared helpers for the file transfer tests: temporary directories,
   files with known contents, and waiting for a condition."
  (:require [clojure.java.io :as io])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn temp-dir
  "A fresh temporary directory."
  ^File []
  (.toFile (Files/createTempDirectory "sdx-ftp" (make-array FileAttribute 0))))

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
  "Write `data` to `f`, creating parent directories. Returns `f`."
  ^File [^File f ^bytes data]
  (io/make-parents f)
  (with-open [out (io/output-stream f)]
    (.write out data))
  f)

(defn read-file
  "All the bytes of `f`."
  ^bytes [^File f]
  (Files/readAllBytes (.toPath f)))

(defn pseudo-random-bytes
  "`n` bytes from a seeded generator, so every run sees the same data."
  ^bytes [n]
  (let [buf (byte-array n)]
    (.nextBytes (java.util.Random. 42) buf)
    buf))

(defn wait-until
  "Poll `pred` every 10 ms for up to `ms` milliseconds. Returns true as
   soon as it is truthy, false if time runs out."
  [pred ms]
  (loop [left ms]
    (cond
      (pred) true
      (<= left 0) false
      :else (do (Thread/sleep 10) (recur (- left 10))))))

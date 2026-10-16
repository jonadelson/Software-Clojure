(ns sdx.archive.backup
  "Stage 1 of the archiver: hash every file under a directory, copy
   each distinct piece of content into the backup directory exactly
   once, and write a timestamped manifest saying which file had which
   content.

   The backup directory is content-addressed: a stored file's name is
   the SHA-256 hash of its bytes, so two files with the same contents
   are stored once, and a stored file can always be checked against
   its own name."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File InputStream]
           [java.security MessageDigest]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------
;; Hashing

(defn hex
  "Render a byte array as a lower-case hexadecimal string."
  [^bytes bs]
  (apply str (map #(format "%02x" %) bs)))

(defn sha256
  "The SHA-256 digest of everything readable from `in`, as 64 hex characters."
  [^InputStream in]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 8192)]
    (loop []
      (let [n (.read in buffer)]
        (when-not (neg? n)
          (.update digest buffer 0 n)
          (recur))))
    (hex (.digest digest))))

(defn hash-file
  "The SHA-256 digest of a file's contents."
  [file]
  (with-open [in (io/input-stream file)]
    (sha256 in)))

;; ---------------------------------------------------------------------
;; Finding the files to back up

(defn relative-path
  "The path of `file` relative to `root`, using / as the separator
   whatever the operating system prefers."
  [^File root ^File file]
  (-> (.relativize (.toPath root) (.toPath file))
      str
      (str/replace File/separator "/")))

(defn files-under
  "Every regular file below `root`, as maps {:path relative-path :file File},
   sorted by path so that the result does not depend on directory order."
  [root]
  (let [root (io/file root)]
    (->> (file-seq root)
         (filter #(.isFile ^File %))
         (map (fn [f] {:path (relative-path root f) :file f}))
         (sort-by :path))))

(defn hash-tree
  "Hash every file below `root`: a seq of {:path :file :hash}."
  [root]
  (map #(assoc % :hash (hash-file (:file %))) (files-under root)))

;; ---------------------------------------------------------------------
;; Time

(defn now
  "The current instant according to the system clock."
  []
  (Instant/now))

(def ^:private stamp-format
  (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") ZoneOffset/UTC))

(defn timestamp
  "Format an instant as a string that sorts chronologically and is safe
   in a file name: 2026-01-16T09:30:00Z becomes 20260116-093000."
  [^Instant instant]
  (.format stamp-format instant))

;; ---------------------------------------------------------------------
;; Storing files and writing the manifest

(defn stored-file
  "Where the content with digest `hash` lives inside `backup-dir`."
  [backup-dir hash]
  (io/file backup-dir hash))

(defn store!
  "Copy the file in `entry` into `backup-dir` under its hash, unless a
   file with that hash is already there. Returns true if a copy was made."
  [backup-dir {:keys [file hash]}]
  (let [target (stored-file backup-dir hash)]
    (if (.exists ^File target)
      false
      (do (io/copy file target)
          true))))

(defn manifest-text
  "Render hashed entries as manifest text: one `path,hash` line per file."
  [entries]
  (apply str (map (fn [{:keys [path hash]}] (str path "," hash "\n")) entries)))

(defn backup
  "Back up every file below `source-dir` into `backup-dir`.

   Each distinct file content is copied once, under its hash. A manifest
   named after the current time is written listing every file's path and
   hash. Returns {:manifest File :entries [...] :copied n}.

   The optional map may supply a :clock, a function of no arguments
   returning a java.time.Instant, so that callers (and tests) can decide
   what time it is."
  ([source-dir backup-dir]
   (backup source-dir backup-dir {}))
  ([source-dir backup-dir {:keys [clock] :or {clock now}}]
   (let [backup-dir (io/file backup-dir)
         entries (vec (hash-tree source-dir))
         manifest (io/file backup-dir (str (timestamp (clock)) ".csv"))]
     (.mkdirs backup-dir)
     (let [copied (reduce (fn [n entry] (if (store! backup-dir entry) (inc n) n))
                          0 entries)]
       (spit manifest (manifest-text entries))
       {:manifest manifest :entries entries :copied copied}))))

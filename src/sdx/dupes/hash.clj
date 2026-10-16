(ns sdx.dupes.hash
  "Finding duplicates by grouping files on a cryptographic hash of their
   contents. Each file is read once and compared with nothing."
  (:require [clojure.java.io :as io]
            [sdx.dupes.files :as files])
  (:import [java.security MessageDigest]))

(defn hex
  "A byte array as a lowercase hexadecimal string, two characters per byte."
  [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn sha256
  "The SHA-256 digest of a byte array, as a 64-character hex string."
  [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (hex (.digest md bs))))

(def ^:private buffer-size 8192)

(defn file-sha256
  "The SHA-256 digest of the file at `path`, read in chunks so that a
   file larger than memory can still be hashed."
  [path]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array buffer-size)]
    (with-open [in (io/input-stream path)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (hex (.digest md))))

(defn duplicate-groups
  "Groups of files under `root` with identical contents, found by
   hashing each file once and grouping on the hash."
  [root]
  (->> (files/list-files root)
       (group-by file-sha256)
       vals
       (filter #(> (count %) 1))
       (map (comp vec sort))
       (sort-by first)))

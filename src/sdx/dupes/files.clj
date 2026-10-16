(ns sdx.dupes.files
  "Finding the files to compare and reading what is in them.

   Every stage of the duplicate finder needs the same two things: the
   list of regular files under a directory, and the bytes in a file.
   They live here so that each stage can concentrate on its own idea."
  (:require [clojure.java.io :as io]))

(defn list-files
  "The paths of every regular file under `root`, as strings, sorted."
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       sort
       vec))

(defn read-bytes
  "The contents of the file at `path` as a byte array."
  ^bytes [path]
  (java.nio.file.Files/readAllBytes (.toPath (io/file path))))

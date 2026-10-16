(ns sdx.dupes.fixture
  "Helpers for tests that need real files: build a temporary directory
   from a map of names to contents, run a function on it, and delete it."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn delete-tree
  "Delete `f` and everything under it."
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-tree child)))
  (.delete f))

(defn with-files
  "Create a temporary directory holding `files` (a map from relative path
   to string contents), call `f` with the directory's path, and delete
   the directory afterwards even if `f` throws."
  [files f]
  (let [dir (.toFile (Files/createTempDirectory "dupes" (make-array FileAttribute 0)))]
    (try
      (doseq [[name contents] files]
        (let [target (io/file dir name)]
          (io/make-parents target)
          (spit target contents)))
      (f (.getPath dir))
      (finally
        (delete-tree dir)))))

(defn relative
  "Strip the directory prefix from every path in every group, so that
   tests can compare against the names they created."
  [dir groups]
  (mapv (fn [group] (mapv #(subs % (inc (count dir))) group)) groups))

(def sample
  "A small tree with three sets of duplicates and one unique file."
  {"a.txt"     "hello"
   "b.txt"     "hello"
   "sub/c.txt" "hello"
   "d.txt"     "world"
   "e.txt"     "world"
   "f.txt"     "unique"
   "empty1"    ""
   "empty2"    ""})

(def sample-groups
  "The duplicate groups every stage should find in `sample`."
  [["a.txt" "b.txt" "sub/c.txt"]
   ["d.txt" "e.txt"]
   ["empty1" "empty2"]])

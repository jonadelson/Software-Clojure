(ns sdx.archive.backup-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sdx.archive.backup :as b]
            [sdx.protocols.macros :refer [with-temp-dir]])
  (:import [java.time Instant]))

(def hello-hash "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
(def empty-hash "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(defn write!
  "Create `path` (relative to `dir`) with `text`, making parent directories."
  [dir path text]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f text)
    f))

(def fixed-clock
  (constantly (Instant/parse "2024-01-02T03:04:05Z")))

(deftest hashing-matches-the-standard
  (is (= hello-hash (b/sha256 (io/input-stream (.getBytes "hello")))))
  (is (= empty-hash (b/sha256 (io/input-stream (byte-array 0))))))

(deftest hashing-a-file
  (with-temp-dir [dir]
    (is (= hello-hash (b/hash-file (write! dir "a.txt" "hello"))))))

(deftest listing-files-with-relative-paths
  (with-temp-dir [dir]
    (write! dir "b.txt" "2")
    (write! dir "sub/a.txt" "1")
    (write! dir "a.txt" "1")
    (is (= ["a.txt" "b.txt" "sub/a.txt"] (map :path (b/files-under dir))))))

(deftest timestamps-sort-and-fit-in-file-names
  (is (= "20240102-030405" (b/timestamp (Instant/parse "2024-01-02T03:04:05Z")))))

(deftest manifest-text-is-one-line-per-file
  (is (= "a.txt,aaa\nsub/b.txt,bbb\n"
         (b/manifest-text [{:path "a.txt" :hash "aaa"} {:path "sub/b.txt" :hash "bbb"}]))))

(deftest backing-up-with-the-clock-as-a-parameter
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")]
      (write! src "a.txt" "hello")
      (write! src "docs/b.txt" "hello")
      (write! src "docs/c.txt" "")
      (let [{:keys [manifest entries copied]} (b/backup src dst {:clock fixed-clock})]
        (testing "the manifest is named after the clock"
          (is (= "20240102-030405.csv" (.getName manifest))))
        (testing "every file is listed, but identical contents are stored once"
          (is (= 3 (count entries)))
          (is (= 2 copied))
          (is (= #{"a.txt" "docs/b.txt" "docs/c.txt"} (set (map :path entries))))
          (is (.exists (io/file dst hello-hash)))
          (is (.exists (io/file dst empty-hash))))
        (testing "the manifest text records path and hash"
          (is (= (str "a.txt," hello-hash "\n"
                      "docs/b.txt," hello-hash "\n"
                      "docs/c.txt," empty-hash "\n")
                 (slurp manifest))))))))

(deftest backing-up-with-the-clock-redefined
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")]
      (write! src "a.txt" "hello")
      (with-redefs [b/now fixed-clock]
        (is (= "20240102-030405.csv" (.getName (:manifest (b/backup src dst)))))))))

(deftest backing-up-twice-copies-nothing-new
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")
          clock-1 (constantly (Instant/parse "2024-01-01T00:00:00Z"))
          clock-2 (constantly (Instant/parse "2024-01-02T00:00:00Z"))]
      (write! src "a.txt" "hello")
      (is (= 1 (:copied (b/backup src dst {:clock clock-1}))))
      (is (= 0 (:copied (b/backup src dst {:clock clock-2}))))
      (testing "but each run leaves its own manifest"
        (is (= #{"20240101-000000.csv" "20240102-000000.csv" hello-hash}
               (set (map #(.getName %) (.listFiles dst))))))
      (testing "a changed file is stored under its new hash"
        (write! src "a.txt" "goodbye")
        (is (= 1 (:copied (b/backup src dst {:clock clock-2}))))
        (is (str/includes? (slurp (io/file dst "20240102-000000.csv"))
                           (b/hash-file (io/file src "a.txt"))))))))

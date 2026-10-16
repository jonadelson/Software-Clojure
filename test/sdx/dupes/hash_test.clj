(ns sdx.dupes.hash-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.dupes.brute :as brute]
            [sdx.dupes.files :as files]
            [sdx.dupes.fixture :as fx]
            [sdx.dupes.hash :as hash]
            [sdx.dupes.naive :as naive]))

(deftest hex-encoding
  (is (= "" (hash/hex (byte-array []))))
  (is (= "000fff" (hash/hex (byte-array [0 15 -1])))))

(deftest known-digests
  (testing "published test vectors for SHA-256"
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (hash/sha256 (.getBytes ""))))
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (hash/sha256 (.getBytes "abc"))))))

(deftest small-changes-change-everything
  (let [a (hash/sha256 (.getBytes "hello"))
        b (hash/sha256 (.getBytes "hellp"))]
    (is (not= a b))
    (testing "roughly half the hex digits differ"
      (is (< 20 (count (filter false? (map = a b))))))))

(deftest hashing-a-file-in-chunks
  (let [big (apply str (repeat 20000 "x"))]
    (fx/with-files {"big" big "small" "abc"}
      (fn [dir]
        (is (= (hash/sha256 (files/read-bytes (str dir "/big")))
               (hash/file-sha256 (str dir "/big"))))
        (is (= (hash/sha256 (.getBytes "abc"))
               (hash/file-sha256 (str dir "/small"))))))))

(deftest finding-duplicates-by-hash
  (fx/with-files fx/sample
    (fn [dir]
      (is (= fx/sample-groups
             (fx/relative dir (hash/duplicate-groups dir))))
      (testing "all three stages agree"
        (is (= (brute/duplicate-groups dir)
               (naive/duplicate-groups dir)
               (hash/duplicate-groups dir)))))))

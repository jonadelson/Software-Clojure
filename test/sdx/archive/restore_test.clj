(ns sdx.archive.restore-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [sdx.archive.backup :as b]
            [sdx.archive.restore :as r]
            [sdx.protocols.macros :refer [with-temp-dir]])
  (:import [java.time Instant]))

(defn write! [dir path text]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f text)
    f))

(defn clock-at [iso]
  (constantly (Instant/parse iso)))

(deftest parsing-a-manifest
  (is (= [{:path "a.txt" :hash "aaa"} {:path "x,y.txt" :hash "bbb"}]
         (r/parse-manifest "a.txt,aaa\nx,y.txt,bbb\n")))
  (is (= [] (r/parse-manifest ""))))

(deftest round-trip
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")
          out (io/file root "restored")]
      (write! src "a.txt" "hello")
      (write! src "docs/b.txt" "hello")
      (write! src "docs/c.txt" "other")
      (let [{:keys [manifest]} (b/backup src dst {:clock (clock-at "2024-01-01T00:00:00Z")})]
        (is (= {:restored 3} (r/restore dst manifest out)))
        (is (= "hello" (slurp (io/file out "a.txt"))))
        (is (= "hello" (slurp (io/file out "docs/b.txt"))))
        (is (= "other" (slurp (io/file out "docs/c.txt"))))
        (testing "the restored tree has exactly the files that were backed up"
          (is (= (map :path (b/files-under src))
                 (map :path (b/files-under out)))))))))

(deftest choosing-a-manifest
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")]
      (write! src "a.txt" "v1")
      (b/backup src dst {:clock (clock-at "2024-01-01T00:00:00Z")})
      (write! src "a.txt" "v2")
      (b/backup src dst {:clock (clock-at "2024-06-01T00:00:00Z")})
      (is (= ["20240101-000000.csv" "20240601-000000.csv"]
             (map #(.getName %) (r/manifests dst))))
      (is (= "20240601-000000.csv" (.getName (r/latest-manifest dst))))
      (testing "an older manifest restores the older contents"
        (let [out (io/file root "old")]
          (r/restore dst (first (r/manifests dst)) out)
          (is (= "v1" (slurp (io/file out "a.txt"))))))
      (testing "the latest manifest restores the newest"
        (let [out (io/file root "new")]
          (r/restore dst (r/latest-manifest dst) out)
          (is (= "v2" (slurp (io/file out "a.txt")))))))))

(deftest a-damaged-store-is-noticed
  (with-temp-dir [root]
    (let [src (io/file root "src")
          dst (io/file root "backup")
          out (io/file root "restored")]
      (write! src "a.txt" "hello")
      (let [{:keys [manifest entries]} (b/backup src dst {:clock (clock-at "2024-01-01T00:00:00Z")})
            stored (b/stored-file dst (:hash (first entries)))]
        (spit stored "tampered")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"corrupt"
                              (r/restore dst manifest out)))
        (.delete stored)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing"
                              (r/restore dst manifest out)))))))

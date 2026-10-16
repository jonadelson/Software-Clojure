(ns sdx.dupes.naive-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.dupes.brute :as brute]
            [sdx.dupes.fixture :as fx]
            [sdx.dupes.naive :as naive]))

(defn- bytes-of [s] (.getBytes ^String s "UTF-8"))

(deftest hashing-bytes
  (is (= 0 (naive/naive-hash (bytes-of ""))))
  (is (= (mod (+ 104 105) 13) (naive/naive-hash (bytes-of "hi"))))
  (testing "bytes above 127 count as positive numbers"
    (is (= (mod 200 13) (naive/naive-hash (byte-array [(unchecked-byte 200)]))))))

(deftest the-hash-collides
  (testing "rearranging the bytes does not change the sum"
    (is (= (naive/naive-hash (bytes-of "listen"))
           (naive/naive-hash (bytes-of "silent")))))
  (testing "there are only thirteen possible values"
    (is (>= 13 (count (distinct (map #(naive/naive-hash (bytes-of (str %)))
                                     (range 1000))))))))

(deftest bucketing-files
  (fx/with-files fx/sample
    (fn [dir]
      (let [b (naive/buckets (map #(str dir "/" %) (keys fx/sample)))]
        (testing "files with the same contents land in the same bucket"
          (is (= (naive/naive-hash (bytes-of "hello")) (naive/naive-hash (bytes-of "hello"))))
          (is (every? (fn [[_ paths]] (vector? paths)) b)))
        (testing "every file is in exactly one bucket"
          (is (= (count fx/sample) (count (apply concat (vals b))))))))))

(deftest finding-duplicates-with-buckets
  (fx/with-files fx/sample
    (fn [dir]
      (is (= fx/sample-groups
             (fx/relative dir (naive/duplicate-groups dir))))
      (testing "the answer is the same as brute force"
        (is (= (brute/duplicate-groups dir)
               (naive/duplicate-groups dir)))))))

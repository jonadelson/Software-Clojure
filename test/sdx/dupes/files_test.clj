(ns sdx.dupes.files-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.dupes.files :as files]
            [sdx.dupes.fixture :as fx]))

(deftest listing-files
  (fx/with-files fx/sample
    (fn [dir]
      (let [paths (files/list-files dir)]
        (testing "every file is found, including those in subdirectories"
          (is (= (sort (keys fx/sample))
                 (map #(subs % (inc (count dir))) paths))))
        (testing "directories themselves are not listed"
          (is (not-any? #(.isDirectory (java.io.File. ^String %)) paths)))))))

(deftest listing-an-empty-directory
  (fx/with-files {}
    (fn [dir]
      (is (= [] (files/list-files dir))))))

(deftest reading-bytes
  (fx/with-files {"x" "hi"}
    (fn [dir]
      (is (= [104 105] (vec (files/read-bytes (str dir "/x"))))))))

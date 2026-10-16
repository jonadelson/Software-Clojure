(ns sdx.dupes.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sdx.dupes.fixture :as fx]
            [sdx.dupes.main :as main]))

(deftest reporting-groups
  (fx/with-files {"a" "same" "b" "same" "c" "other"}
    (fn [dir]
      (is (= (str dir "/a\n" dir "/b")
             (main/report dir))))))

(deftest reporting-nothing
  (fx/with-files {"a" "1" "b" "2"}
    (fn [dir]
      (is (= "No duplicates found." (main/report dir))))))

(deftest running-main
  (fx/with-files {"a" "same" "b" "same"}
    (fn [dir]
      (is (= [(str dir "/a") (str dir "/b")]
             (str/split-lines (with-out-str (main/-main dir)))))))
  (is (str/starts-with? (with-out-str (main/-main)) "usage:")))

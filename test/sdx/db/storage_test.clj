(ns sdx.db.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [sdx.db.record :as rec]
            [sdx.db.storage :as s])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn with-temp-dir
  "Call `f` with a fresh temporary directory, then delete the directory
   and everything in it."
  [f]
  (let [dir (.toFile (Files/createTempDirectory "sdx-db" (make-array FileAttribute 0)))]
    (try
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (io/delete-file file true))))))

(def r1 {:id 1 :sensor "lab-a" :reading 21.5 :when 100})
(def r2 {:id 2 :sensor "lab-b" :reading 19.0 :when 101})
(def r1-later {:id 1 :sensor "lab-a" :reading 22.0 :when 102})

(defn exercise-storage
  "The behaviour every kind of storage must have."
  [storage]
  (let [db (-> (s/open storage) (s/add! r1) (s/add! r2) (s/add! r1-later))]
    (is (= r1-later (s/lookup db 1)))
    (is (= r2 (s/lookup db 2)))
    (is (nil? (s/lookup db 3)))
    (is (= 3 (s/size db)))
    (is (= [r1 r1-later] (s/history db 1)))
    (s/close! storage)))

(deftest memory-storage-behaves-like-stage-one
  (exercise-storage (s/memory-storage)))

(deftest file-storage-behaves-the-same
  (with-temp-dir
    (fn [dir]
      (exercise-storage (s/file-storage (io/file dir "readings.db") rec/block-size)))))

(deftest file-storage-survives-reopening
  (with-temp-dir
    (fn [dir]
      (let [path (io/file dir "readings.db")
            first-storage (s/file-storage path rec/block-size)]
        (-> (s/open first-storage) (s/add! r1) (s/add! r2) (s/add! r1-later))
        (s/close! first-storage)
        (testing "the file holds exactly three fixed-size blocks"
          (is (= (* 3 rec/block-size) (.length path))))
        (let [storage (s/file-storage path rec/block-size)
              db (s/open storage)]
          (testing "the index is rebuilt from the log"
            (is (= {1 2, 2 1} (:index db)))
            (is (= r1-later (s/lookup db 1))))
          (testing "and the reopened database can keep growing"
            (let [db (s/add! db {:id 3 :sensor "lab-c" :reading 0.0 :when 103})]
              (is (= 4 (s/size db)))
              (is (= "lab-c" (:sensor (s/lookup db 3))))))
          (s/close! storage))))))

(deftest the-index-is-a-value
  (testing "an old database value still works, though it sees the storage as it is now"
    (let [storage (s/memory-storage)
          db0 (s/open storage)
          db1 (s/add! db0 r1)]
      (is (nil? (s/lookup db0 1)))
      (is (= r1 (s/lookup db1 1)))
      (is (= 1 (s/size db0))))))

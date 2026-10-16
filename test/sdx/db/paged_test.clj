(ns sdx.db.paged-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [sdx.db.record :as rec]
            [sdx.db.storage :as s]
            [sdx.db.storage-test :refer [with-temp-dir]]
            [sdx.db.paged :as p]))

(deftest cache-keeps-the-most-recent-pages
  (let [c (-> (p/empty-cache 2)
              (p/insert 0 "page zero")
              (p/insert 1 "page one"))]
    (is (= [0 1] (p/resident c)))
    (testing "touching a page makes it the most recent"
      (is (= [1 0] (p/resident (p/hit c 0)))))
    (testing "inserting past capacity drops the least recently used"
      (is (= [1 2] (p/resident (p/insert c 2 "page two"))))
      (is (= [0 2] (p/resident (p/insert (p/hit c 0) 2 "page two")))))
    (testing "forgetting removes a page without counting as anything"
      (let [c (p/forget c 0)]
        (is (= [1] (p/resident c)))
        (is (= {:hits 0 :misses 2} (select-keys c [:hits :misses])))))))

(defn reading [i]
  {:id i :sensor (str "s" i) :reading (double i) :when (* 10 i)})

(def options {:block-size rec/block-size :per-page 4 :capacity 2})

(deftest reading-neighbours-hits-the-cache
  (with-temp-dir
    (fn [dir]
      (let [storage (p/page-storage (io/file dir "readings.db") options)
            db (reduce s/add! (s/open storage) (map reading (range 10)))]
        (testing "the first read of a page is a miss, the rest of the page are hits"
          (is (= (reading 0) (s/lookup db 0)))
          (is (= {:hits 0 :misses 1 :resident [0]} (p/stats storage)))
          (is (= (reading 3) (s/lookup db 3)))
          (is (= {:hits 1 :misses 1 :resident [0]} (p/stats storage))))
        (testing "a third page pushes out the least recently used"
          (s/lookup db 4)
          (s/lookup db 9)
          (is (= [1 2] (:resident (p/stats storage))))
          (s/lookup db 0)
          (is (= {:hits 1 :misses 4 :resident [2 0]} (p/stats storage))))
        (s/close! storage)))))

(deftest appending-invalidates-the-page
  (with-temp-dir
    (fn [dir]
      (let [storage (p/page-storage (io/file dir "readings.db") options)
            db (-> (s/open storage) (s/add! (reading 0)))]
        (s/lookup db 0)
        (is (= [0] (:resident (p/stats storage))))
        (let [db (s/add! db (reading 1))]
          (is (= [] (:resident (p/stats storage))))
          (is (= (reading 1) (s/lookup db 1)))
          (is (= (reading 0) (s/lookup db 0)))
          (is (= {:hits 1 :misses 2} (select-keys (p/stats storage) [:hits :misses]))))
        (s/close! storage)))))

(deftest paged-storage-is-still-storage
  (with-temp-dir
    (fn [dir]
      (let [path (io/file dir "readings.db")
            storage (p/page-storage path options)]
        (reduce s/add! (s/open storage) (map reading (range 7)))
        (s/close! storage)
        (testing "a plain file storage can read what the paged one wrote"
          (let [plain (s/file-storage path rec/block-size)
                db (s/open plain)]
            (is (= 7 (s/size db)))
            (is (= (reading 6) (s/lookup db 6)))
            (s/close! plain)))
        (testing "and the paged one can reopen it"
          (let [again (p/page-storage path options)
                db (s/open again)]
            (is (= (map reading (range 7)) (map #(s/lookup db %) (range 7))))
            (is (= {:hits 12 :misses 2} (select-keys (p/stats again) [:hits :misses])))
            (s/close! again)))))))

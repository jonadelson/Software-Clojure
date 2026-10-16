(ns sdx.db.memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.db.memory :as db]))

(def r1 {:id 1 :sensor "lab-a" :reading 21.5 :when 100})
(def r2 {:id 2 :sensor "lab-b" :reading 19.0 :when 101})
(def r1-later {:id 1 :sensor "lab-a" :reading 22.0 :when 102})

(deftest adding-and-looking-up
  (let [db (-> (db/create) (db/add r1) (db/add r2))]
    (is (= r1 (db/lookup db 1)))
    (is (= r2 (db/lookup db 2)))
    (is (nil? (db/lookup db 3)))
    (is (= 2 (db/size db)))
    (is (= #{1 2} (db/ids db)))))

(deftest the-old-database-is-untouched
  (let [before (db/add (db/create) r1)
        after (db/add before r2)]
    (is (= 1 (db/size before)))
    (is (= 2 (db/size after)))))

(deftest updating-appends-a-new-version
  (let [db (-> (db/create) (db/add r1) (db/add r2) (db/add r1-later))]
    (testing "lookup sees the latest version"
      (is (= r1-later (db/lookup db 1))))
    (testing "the log keeps every version"
      (is (= 3 (db/size db)))
      (is (= [r1 r1-later] (db/history db 1))))
    (testing "the index is the same whether built incrementally or from scratch"
      (is (= (:index db) (db/rebuild-index (:records db)))))))

(deftest records-need-an-id
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :id"
                        (db/add (db/create) {:sensor "x"}))))

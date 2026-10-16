(ns sdx.persist.extensible-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.persist.extensible :as p]
            [sdx.persist.basic-test :refer [samples]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time LocalDate]))

;; ---------------------------------------------------------------------
;; Two types the library knows nothing about, added from outside.

(defrecord Point [x y]
  p/Persistable
  (save-value [_ state]
    (p/save-all (p/emit state "point") [x y])))

(defmethod p/load-value "point" [_ _ state]
  (let [[[x y] state] (p/load-n state 2)]
    [(->Point x y) state]))

(extend-protocol p/Persistable
  LocalDate
  (save-value [d state] (p/emit state (str "date:" d))))

(defmethod p/load-value "date" [_ arg state]
  [(LocalDate/parse arg) state])

;; A record that does not implement the protocol.
(defrecord Plain [a])

;; ---------------------------------------------------------------------

(deftest everything-still-round-trips
  (doseq [v samples]
    (testing (pr-str v)
      (is (= v (p/from-lines (p/to-lines v))))
      (is (= v (p/from-string (p/to-string v)))))))

(deftest sharing-is-still-preserved
  (let [shared [1 2]
        [a b] (p/from-lines (p/to-lines [shared shared]))]
    (is (= [1 2] a))
    (is (identical? a b))))

(deftest a-record-can-say-how-it-is-saved
  (let [point (->Point 3 4)]
    (is (= ["point" "int:3" "int:4"] (p/to-lines point)))
    (let [loaded (p/from-lines (p/to-lines point))]
      (is (instance? Point loaded))
      (is (= point loaded)))))

(deftest records-take-part-in-sharing
  (let [point (->Point 1 2)
        [a b] (p/from-lines (p/to-lines [point point]))]
    (is (= ["vec:2" "point" "int:1" "int:2" "ref:0"] (p/to-lines [point point])))
    (is (identical? a b))))

(deftest a-java-class-can-be-extended-too
  (let [day (LocalDate/of 2026 10 16)]
    (is (= ["date:2026-10-16"] (p/to-lines day)))
    (is (= day (p/from-lines (p/to-lines day))))
    (is (= {:when day :points [(->Point 0 0)]}
           (p/from-string (p/to-string {:when day :points [(->Point 0 0)]}))))))

(deftest a-record-without-an-implementation-comes-back-as-a-map
  (let [loaded (p/from-lines (p/to-lines (->Plain 1)))]
    (is (= {:a 1} loaded))
    (is (not (instance? Plain loaded)))))

(deftest unsupported-and-unknown-things-are-reported
  (is (thrown? IllegalArgumentException (p/to-lines (fn [x] x))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown tag"
                        (p/from-lines ["blob:1"]))))

(deftest files-round-trip
  (let [dir (Files/createTempDirectory "persist" (make-array FileAttribute 0))
        path (str (.resolve dir "value.txt"))
        value {:origin (->Point 0 0) :when (LocalDate/of 2000 1 1) :note "two\nlines"}]
    (try
      (p/save-to path value)
      (is (= value (p/load-from path)))
      (finally
        (Files/deleteIfExists (.resolve dir "value.txt"))
        (Files/deleteIfExists dir)))))

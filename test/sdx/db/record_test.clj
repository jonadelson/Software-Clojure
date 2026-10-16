(ns sdx.db.record-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.db.record :as rec]))

(def reading {:id 7 :sensor "roof" :reading 3.25 :when 1700000000123})

(deftest block-size-comes-from-the-schema
  (is (= 36 rec/block-size))
  (is (= 4 (rec/field-size :int)))
  (is (= 16 (rec/field-size [:str 16])))
  (is (thrown? clojure.lang.ExceptionInfo (rec/field-size :complex))))

(deftest packing-produces-a-fixed-size-block
  (is (= rec/block-size (alength (rec/pack reading))))
  (is (= rec/block-size (alength (rec/pack (assoc reading :sensor "")))))
  (testing "the same record always packs to the same bytes"
    (is (= (vec (rec/pack reading)) (vec (rec/pack reading))))))

(deftest round-trip
  (is (= reading (rec/unpack (rec/pack reading))))
  (testing "strings shorter than the field come back without padding"
    (is (= "" (:sensor (rec/unpack (rec/pack (assoc reading :sensor ""))))))
    (is (= "sixteen chars!!!" (:sensor (rec/unpack (rec/pack (assoc reading :sensor "sixteen chars!!!")))))))
  (testing "negative and large numbers survive"
    (let [r {:id -1 :sensor "x" :reading -0.5 :when Long/MAX_VALUE}]
      (is (= r (rec/unpack (rec/pack r)))))))

(deftest packing-rejects-bad-records
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not fit"
                        (rec/pack (assoc reading :sensor "seventeen chars!!"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :when"
                        (rec/pack (dissoc reading :when)))))

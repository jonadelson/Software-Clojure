(ns sdx.binary.text-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.binary.pack :as p]
            [sdx.binary.text :as t]))

(deftest encodings-differ-in-size
  (is (= [104 105] (vec (t/encode "hi" "UTF-8"))))
  (is (= [0 104 0 105] (vec (t/encode "hi" "UTF-16BE"))))
  (testing "plain UTF-16 starts with a byte-order mark"
    (is (= [-2 -1 0 104 0 105] (vec (t/encode "hi" "UTF-16")))))
  (testing "one character, three encodings"
    (is (= [-61 -87] (vec (t/encode "é" "UTF-8"))))
    (is (= [0 -23] (vec (t/encode "é" "UTF-16BE"))))
    (is (= [-23] (vec (t/encode "é" "ISO-8859-1"))))))

(deftest decoding-must-use-the-same-encoding
  (is (= "héllo" (t/decode (t/encode "héllo" "UTF-8") "UTF-8")))
  (is (= "héllo" (t/decode (t/encode "héllo" "UTF-16") "UTF-16")))
  (testing "the wrong encoding gives the wrong text, not an error"
    (is (= "hÃ©llo" (t/decode (t/encode "héllo" "UTF-8") "ISO-8859-1")))))

(deftest simple-checksum
  (is (= 6 (t/checksum (byte-array [1 2 3]))))
  (is (= 0 (t/checksum (byte-array [-1 1]))))
  (is (= 0 (t/checksum (byte-array []))))
  (testing "it cannot tell reordered bytes apart"
    (is (= (t/checksum (byte-array [1 2])) (t/checksum (byte-array [2 1]))))))

(deftest fletcher-checksum
  (is (= 0 (t/fletcher16 (byte-array []))))
  (is (= 0x0101 (t/fletcher16 (byte-array [1]))))
  (testing "reordered bytes give a different result"
    (is (not= (t/fletcher16 (byte-array [1 2])) (t/fletcher16 (byte-array [2 1])))))
  (testing "it fits in sixteen bits"
    (is (<= 0 (t/fletcher16 (t/encode "any text at all" "UTF-8")) 0xFFFF))))

(deftest messages-carry-their-checksum
  (let [payload (t/encode "hello" "UTF-8")
        message (t/with-checksum payload)]
    (is (= (+ 2 (alength payload)) (alength message)))
    (is (= (vec payload) (vec (t/verify message))))
    (testing "a damaged byte is noticed"
      (let [damaged (aclone message)]
        (aset-byte damaged 1 (byte 0))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"checksum mismatch"
                              (t/verify damaged)))))
    (testing "two swapped bytes are noticed"
      (let [swapped (aclone message)]
        (aset-byte swapped 0 (aget message 1))
        (aset-byte swapped 1 (aget message 0))
        (is (thrown? clojure.lang.ExceptionInfo (t/verify swapped)))))
    (testing "a message too short to carry a checksum"
      (is (thrown? clojure.lang.ExceptionInfo (t/verify (byte-array [1])))))))

(deftest a-packed-record-can-be-checksummed
  (let [fmt "i s h"
        values [7 "temperature" -3]
        message (t/with-checksum (p/pack fmt values))]
    (is (= values (p/unpack fmt (t/verify message))))))

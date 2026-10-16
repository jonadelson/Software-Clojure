(ns sdx.binary.bits-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.binary.bits :as b])
  (:import [java.nio ByteBuffer]))

(deftest numbers-to-bit-strings-and-back
  (is (= "00000101" (b/bits 5 8)))
  (is (= "11001000" (b/bits 200 8)))
  (is (= "101" (b/bits 5 3)))
  (is (= "0000000000000101" (b/bits 5 16)))
  (is (= 5 (b/unbits "101")))
  (is (= 200 (b/unbits "11001000")))
  (doseq [n [0 1 7 100 255 65535]]
    (is (= n (b/unbits (b/bits n 16))))))

(deftest negative-numbers-are-twos-complement
  (is (= "11111111" (b/bits -1 8)))
  (is (= "11111110" (b/bits -2 8)))
  (is (= "10000000" (b/bits -128 8)))
  (testing "the same eight bits read two ways"
    (is (= -56 (b/to-byte 200)))
    (is (= 200 (b/unsigned (b/to-byte 200))))
    (is (= (b/bits 200 8) (b/bits (b/to-byte 200) 8))))
  (testing "to-byte keeps only the low eight bits"
    (is (= 127 (b/to-byte 127)))
    (is (= -128 (b/to-byte 128)))
    (is (= 1 (b/to-byte 257)))))

(deftest hex-dump
  (is (= "00 01 c8 ff" (b/hex (byte-array [0 1 -56 -1])))))

(deftest integers-to-bytes-in-either-order
  (is (= [0 0 1 -56] (vec (b/int->bytes 456 4 :big))))
  (is (= [-56 1 0 0] (vec (b/int->bytes 456 4 :little))))
  (is (= [1 -56] (vec (b/int->bytes 456 2 :big))))
  (is (= [-1 -2] (vec (b/int->bytes -2 2 :big))))
  (is (= [-1 -1 -1 -1] (vec (b/int->bytes -1 4 :little)))))

(deftest bytes-to-integers
  (is (= 456 (b/bytes->int (byte-array [0 0 1 -56]) :big)))
  (is (= 456 (b/bytes->int (byte-array [-56 1 0 0]) :little)))
  (is (= -2 (b/bytes->int (byte-array [-1 -2]) :big)))
  (is (= 200 (b/bytes->int (byte-array [0 -56]) :big)))
  (testing "a lone high byte is a negative number"
    (is (= -56 (b/bytes->int (byte-array [-56]) :big)))))

(deftest round-trips-at-every-width
  (doseq [[width samples] {1 [0 1 -1 127 -128]
                           2 [0 300 -300 32767 -32768]
                           4 [0 123456789 -123456789 Integer/MAX_VALUE Integer/MIN_VALUE]
                           8 [0 1 -1 Long/MAX_VALUE Long/MIN_VALUE]}
          endian [:big :little]
          n samples]
    (testing (str n " in " width " bytes " endian)
      (is (= n (b/bytes->int (b/int->bytes n width endian) endian))))))

(deftest agrees-with-the-jdk
  (let [n 123456789
        jdk (.array (.putInt (ByteBuffer/allocate 4) n))]
    (is (= (vec jdk) (vec (b/int->bytes n 4 :big))))
    (is (= n (.getInt (ByteBuffer/wrap (b/int->bytes n 4 :big)))))))

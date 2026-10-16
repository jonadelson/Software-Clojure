(ns sdx.binary.pack-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.binary.pack :as p]))

(deftest parsing-formats
  (is (= {:order :big :items [{:code \i :count 1}]} (p/parse-format "i")))
  (is (= {:order :little :items [{:code \h :count 1}]} (p/parse-format "<h")))
  (is (= {:order :big :items [{:code \i :count 2} {:code \s :count 1} {:code \f :count 1}]}
         (p/parse-format "> 2i s f")))
  (is (= {:order :big :items []} (p/parse-format "")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad format"
                        (p/parse-format "2i x"))))

(deftest packing-single-values
  (is (= [0 0 0 1] (vec (p/pack "i" [1]))))
  (is (= [1 0 0 0] (vec (p/pack "<i" [1]))))
  (is (= [-1 -2] (vec (p/pack "h" [-2]))))
  (is (= [-1] (vec (p/pack "b" [-1]))))
  (is (= [0 0 0 2 104 105] (vec (p/pack "s" ["hi"]))))
  (testing "a float is its IEEE 754 bit pattern"
    (is (= [63 -128 0 0] (vec (p/pack "f" [1.0]))))))

(deftest packing-several-values
  (is (= [0 1 0 2 0 0 0 3] (vec (p/pack "2h i" [1 2 3]))))
  (is (= [0 0 0 1 97 0 0 0 2 98 99] (vec (p/pack "s s" ["a" "bc"])))))

(deftest round-trips
  (doseq [[fmt values] [["i" [42]]
                        ["<i" [-42]]
                        ["b h i" [-1 -300 -70000]]
                        ["3i" [1 2 3]]
                        ["s" ["héllo, wörld"]]
                        ["s s" ["" "two\nlines"]]
                        ["f" [1.5]]
                        ["<2i s f b h" [1 2 "mixed" 0.25 7 -7]]]]
    (testing fmt
      (is (= values (p/unpack fmt (p/pack fmt values)))))))

(deftest floats-are-only-32-bits
  (let [[f] (p/unpack "f" (p/pack "f" [0.1]))]
    (is (not= 0.1 f))
    (is (< (Math/abs (- 0.1 f)) 1e-7))))

(deftest byte-order-changes-the-meaning
  (is (= [16777216] (p/unpack "<i" (p/pack ">i" [1]))))
  (is (= [256] (p/unpack "<h" (p/pack ">h" [1])))))

(deftest mistakes-are-caught
  (testing "wrong number of values"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes 2 values"
                          (p/pack "2i" [1]))))
  (testing "values that do not fit"
    (is (thrown? IllegalArgumentException (p/pack "b" [200])))
    (is (thrown? IllegalArgumentException (p/pack "h" [70000]))))
  (testing "bytes left over"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"left over"
                          (p/unpack "h" (p/pack "i" [1])))))
  (testing "not enough bytes"
    (is (thrown? java.nio.BufferUnderflowException
                 (p/unpack "i" (p/pack "h" [1]))))))

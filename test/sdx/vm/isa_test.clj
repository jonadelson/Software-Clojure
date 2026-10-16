(ns sdx.vm.isa-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.vm.isa :as isa]))

(deftest encoding-packs-bytes
  (testing "op code in the low byte, arguments above it"
    (is (= 0x050002 (isa/encode :ldc 0 5)))
    (is (= 0x010205 (isa/encode :add 2 1)))
    (is (= 0x000001 (isa/encode :hlt)))
    (is (= 0x000309 (isa/encode :prr 3)))))

(deftest decoding-unpacks-bytes
  (is (= {:op :ldc :a 0 :b 5} (isa/decode 0x050002)))
  (is (= {:op :hlt :a 0 :b 0} (isa/decode 1)))
  (testing "a word whose low byte is not an op code is not an instruction"
    (is (nil? (isa/decode 0)))
    (is (nil? (isa/decode 0x0000FF)))))

(deftest encode-then-decode-is-identity
  (doseq [[op {:keys [args]}] isa/instructions
          a [0 3]
          b [0 255]]
    (let [given (take (count args) [a b])
          [a b] given]
      (is (= {:op op :a (or a 0) :b (or b 0)}
             (isa/decode (apply isa/encode op given)))))))

(deftest encoding-rejects-bad-input
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown operation"
                        (isa/encode :jmp 0 0)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out of range"
                        (isa/encode :ldc 0 256)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out of range"
                        (isa/encode :ldc -1 0))))

(deftest every-op-code-is-distinct
  (is (= (count isa/instructions) (count isa/code->op)))
  (is (nil? (isa/code->op 0)) "zero is reserved for empty memory"))

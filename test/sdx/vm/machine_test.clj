(ns sdx.vm.machine-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.vm.isa :as isa]
            [sdx.vm.machine :as vm]))

(defn- program
  "Encode a vector of [op a b] vectors into words."
  [& instrs]
  (mapv #(apply isa/encode %) instrs))

(deftest stepping-one-instruction
  (let [m (vm/new-machine (program [:ldc 2 42] [:hlt]))
        m' (vm/step m)]
    (testing "the step moves the pc and changes exactly what it should"
      (is (= 1 (:pc m')))
      (is (= [0 0 42 0] (:regs m'))))
    (testing "the original state is untouched"
      (is (= 0 (:pc m)))
      (is (= [0 0 0 0] (:regs m))))))

(deftest branching
  (testing "beq branches only on zero"
    (let [m (vm/run (vm/new-machine (program [:ldc 0 0]
                                             [:beq 0 3]
                                             [:ldc 1 1]      ; skipped
                                             [:hlt])))]
      (is (= 0 (vm/reg m 1)))))
  (testing "bne branches only on non-zero, so this loop runs three times"
    (let [m (vm/run (vm/new-machine (program [:ldc 0 3] [:ldc 1 1]
                                             [:prr 0]        ; 2
                                             [:sub 0 1]
                                             [:bne 0 2]
                                             [:hlt])))]
      (is (= ["3" "2" "1"] (:output m))))))

(deftest a-fresh-machine
  (let [m (vm/new-machine (program [:hlt]))]
    (is (= 0 (:pc m)))
    (is (= [0 0 0 0] (:regs m)))
    (is (= 256 (count (:ram m))))
    (is (= 1 (first (:ram m))))
    (is (= [] (:output m)))
    (is (not (:halted? m)))))

(deftest halting
  (let [m (vm/run (vm/new-machine (program [:ldc 0 1] [:hlt] [:ldc 0 2])))]
    (is (:halted? m))
    (is (= 2 (:pc m)) "the instruction after hlt never runs")
    (is (= 1 (vm/reg m 0)))
    (is (= m (vm/step m)) "stepping a halted machine does nothing")))

(deftest arithmetic-and-copying
  (let [m (vm/run (vm/new-machine (program [:ldc 0 7] [:ldc 1 5]
                                           [:cpy 2 0]
                                           [:add 0 1]
                                           [:sub 2 1]
                                           [:hlt])))]
    (is (= [12 5 2 0] (:regs m)))))

(deftest memory-round-trip
  (let [m (vm/run (vm/new-machine (program [:ldc 0 99] [:ldc 1 100]
                                           [:str 0 1]
                                           [:ldr 2 1]
                                           [:prm 1]
                                           [:hlt])))]
    (is (= 99 (vm/load-word m 100)))
    (is (= 99 (vm/reg m 2)))
    (is (= ["99"] (:output m)))))

(deftest printing
  (let [m (vm/run (vm/new-machine (program [:ldc 3 8] [:prr 3] [:prr 3] [:hlt])))]
    (is (= ["8" "8"] (:output m)))))

(deftest running-into-empty-memory
  (testing "a program that forgets to halt hits a zero word"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an instruction: 0 at address 1"
                          (vm/run (vm/new-machine (program [:ldc 0 1])))))))

(deftest bad-addresses
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"address out of range"
                        (vm/run (vm/new-machine (program [:ldc 0 255] [:ldc 1 1]
                                                         [:add 0 1]
                                                         [:ldr 2 0] [:hlt])))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"program too long"
                        (vm/new-machine (vec (repeat 257 1))))))

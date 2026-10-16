(ns sdx.vm.assembler-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sdx.vm.assembler :as asm]
            [sdx.vm.isa :as isa]
            [sdx.vm.machine :as vm]))

(defn- sample [name]
  (slurp (io/resource (str "vm/" name))))

;; ---------------------------------------------------------------------
;; Parsing

(deftest parsing-lines
  (is (nil? (asm/parse-line 1 "")))
  (is (nil? (asm/parse-line 1 "   # just a comment")))
  (is (= {:kind :instr :op :ldc :args ["R0" "5"] :line 3}
         (asm/parse-line 3 "  ldc R0 5   # trailing comment")))
  (is (= {:kind :label :name "loop" :line 4} (asm/parse-line 4 "loop:")))
  (is (= {:kind :data :name "buf" :size 8 :line 5} (asm/parse-line 5 ".data buf 8")))
  (is (= {:kind :word :value 7 :line 6} (asm/parse-line 6 ".word 7"))))

(deftest parsing-a-program
  (is (= [{:kind :instr :op :ldc :args ["R0" "1"] :line 1}
          {:kind :label :name "top" :line 3}
          {:kind :instr :op :hlt :args [] :line 4}]
         (asm/parse "ldc R0 1\n\ntop:\nhlt\n"))))

;; ---------------------------------------------------------------------
;; Labels and layout

(deftest label-addresses
  (testing "labels name the next word; data blocks come after the code"
    (is (= {"top" 0 "end" 2 "a" 3 "b" 5}
           (asm/label-addresses
            (asm/parse "top:\nldc R0 1\n.data a 2\nprr R0\nend:\nhlt\n.data b 1"))))))

(deftest duplicate-labels-are-an-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"line 3: duplicate label x"
                        (asm/label-addresses (asm/parse "x:\nhlt\nx:\n")))))

;; ---------------------------------------------------------------------
;; Assembling

(deftest assembling-instructions
  (is (= [(isa/encode :ldc 0 5) (isa/encode :prr 0) (isa/encode :hlt)]
         (asm/assemble "ldc R0 5\nprr R0\nhlt"))))

(deftest assembling-labels-and-data
  (let [words (asm/assemble "ldc R0 @buf\nbne R0 @here\nhere:\nhlt\n.data buf 3")]
    (is (= [(isa/encode :ldc 0 3) (isa/encode :bne 0 2) (isa/encode :hlt) 0 0 0]
           words))))

(deftest assembler-errors-name-the-line
  (doseq [[text message] [["frob R0" #"line 1: unknown operation frob"]
                          ["hlt\nadd R0" #"line 2: add takes 2 argument\(s\), got 1"]
                          ["ldc R7 1" #"line 1: no such register R7"]
                          ["ldc 5 1" #"line 1: expected a register, got 5"]
                          ["ldc R0 R1" #"line 1: expected a number or @label, got R1"]
                          ["ldc R0 256" #"line 1: value too large"]
                          ["bne R0 @nowhere" #"line 1: undefined label @nowhere"]
                          [".data x" #"line 1: expected .data <name> <size>"]
                          [".frob 1" #"line 1: unknown directive .frob"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo message (asm/assemble text))
        text)))

(deftest programs-that-do-not-fit
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"memory holds 256"
                        (asm/assemble "hlt\n.data big 256"))))

;; ---------------------------------------------------------------------
;; Disassembling

(deftest disassembling-words
  (is (= "ldc R0 5" (asm/disassemble-word (isa/encode :ldc 0 5))))
  (is (= "bne R2 17" (asm/disassemble-word (isa/encode :bne 2 17))))
  (is (= "hlt" (asm/disassemble-word (isa/encode :hlt))))
  (is (= ".word 0" (asm/disassemble-word 0))))

(deftest disassembly-round-trips
  (doseq [name ["count_down.as" "fill_array.as" "sum_array.as"]]
    (let [words (asm/assemble (sample name))]
      (is (= words (asm/assemble (str/join "\n" (asm/disassemble words))))
          name))))

;; ---------------------------------------------------------------------
;; The sample programs, end to end

(deftest count-down
  (let [m (vm/run (vm/new-machine (asm/assemble (sample "count_down.as"))))]
    (is (= ["5" "4" "3" "2" "1"] (:output m)))
    (is (= 0 (vm/reg m 0)))))

(deftest fill-array
  (let [text (sample "fill_array.as")
        start (get (asm/label-addresses (asm/parse text)) "array")
        m (vm/run (vm/new-machine (asm/assemble text)))]
    (is (= [1 2 3 4 5 6 7 8 9 10] (subvec (:ram m) start (+ start 10))))
    (is (= 0 (nth (:ram m) (+ start 10))) "nothing written past the array")))

(deftest sum-array
  (let [m (vm/run (vm/new-machine (asm/assemble (sample "sum_array.as"))))]
    (is (= ["15"] (:output m)))))

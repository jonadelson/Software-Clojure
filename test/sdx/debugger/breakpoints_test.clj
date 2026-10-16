(ns sdx.debugger.breakpoints-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sdx.debugger.breakpoints :as bp]
            [sdx.vm.assembler :as asm]
            [sdx.vm.machine :as vm]))

(defn- load-program
  "The machine and label table for a sample program."
  [name]
  (let [items (asm/parse (slurp (io/resource (str "vm/" name))))]
    [(vm/new-machine (asm/assemble-items items))
     (asm/label-addresses items)]))

(defn- session [name lines]
  (let [[m labels] (load-program name)]
    (bp/run-script m labels lines)))

(deftest run-until-always-moves
  (let [[m _] (load-program "count_down.as")
        stopped (bp/run-until m #(= 2 (:pc %)))]
    (is (= 2 (:pc stopped)))
    (testing "continuing from the stop goes round the loop once"
      (let [again (bp/run-until stopped #(= 2 (:pc %)))]
        (is (= 2 (:pc again)))
        (is (= ["5"] (:output again)))))))

(deftest stopping-at-a-breakpoint
  (is (= ["  0: ldc R0 5"
          "breakpoint at 3"
          "5"                  ; printed by prr before we stop
          "  3: sub R0 R1"
          "4"
          "  3: sub R0 R1"
          "R0 = 4  R1 = 1  R2 = 0  R3 = 0"]
         (session "count_down.as" ["b 3" "c" "c" "r"]))))

(deftest breakpoints-by-label
  (is (= ["  0: ldc R0 5"
          "breakpoint at 2"
          "  2: prr R0"
          "breakpoints: 2"
          "cleared breakpoint at 2"
          "no breakpoints"
          "5" "4" "3" "2" "1"
          "halted"]
         (session "count_down.as" ["b loop" "c" "b" "b 2" "b" "c"]))))

(deftest unknown-breakpoint-targets
  (is (= ["  0: ldc R0 5" "no such address or label: nowhere"]
         (session "count_down.as" ["b nowhere"]))))

(deftest listing-shows-breakpoints
  (is (= ["  0: ldc R0 5"
          "breakpoint at 4"
          "  1: ldc R1 1"
          "  2: prr R0"
          " >  2: prr R0"
          "    3: sub R0 R1"
          "*   4: bne R0 2"
          "    5: hlt"]
         (session "count_down.as" ["b 4" "s" "s" "d 2 5"])))
  (testing "the pc and a breakpoint can share a line"
    (is (= ["  0: ldc R0 5" "breakpoint at 0" "*>  0: ldc R0 5"]
           (session "count_down.as" ["b 0" "d 0 0"])))))

(deftest the-core-commands-still-work
  (is (= ["  0: ldc R0 0"
          "  1: ldc R1 13"     ; the address of the array
          "15"                 ; the program's output
          "halted"]
         (session "sum_array.as" ["s" "c"]))))

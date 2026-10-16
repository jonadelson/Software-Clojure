(ns sdx.debugger.core-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sdx.debugger.core :as dbg]
            [sdx.vm.assembler :as asm]
            [sdx.vm.machine :as vm]))

(defn- machine [name]
  (vm/new-machine (asm/assemble (slurp (io/resource (str "vm/" name))))))

(deftest a-scripted-session
  (is (= ["  0: ldc R0 5"      ; where we start
          "  1: ldc R1 1"      ; after s
          "  2: prr R0"        ; after s
          " 10: 0"             ; m 10
          "5" "4" "3" "2" "1"  ; the program's output, after c
          "halted"]
         (dbg/run-script (machine "count_down.as") ["s" "s" "m 10" "c"]))))

(deftest commands-are-pure-functions
  (let [d (dbg/new-debugger (machine "count_down.as"))
        d' (dbg/dispatch d "s")]
    (testing "the result carries the new machine and the lines to show"
      (is (= 1 (get-in d' [:vm :pc])))
      (is (= ["  1: ldc R1 1"] (:messages d'))))
    (testing "the input is untouched"
      (is (= 0 (get-in d [:vm :pc])))
      (is (= [] (:messages d))))))

(deftest showing-registers-and-memory
  (let [m (machine "fill_array.as")]
    (is (= ["  0: ldc R0 10"
            "R0 = 0  R1 = 0  R2 = 0  R3 = 0"
            "  1: ldc R1 1"
            "R0 = 10  R1 = 0  R2 = 0  R3 = 0"]
           (dbg/run-script m ["r" "s" "r"])))
    (testing "memory after the whole program has run"
      (is (= ["  0: ldc R0 10"
              "halted"
              " 10: 1" " 11: 2" " 12: 3"]
             (dbg/run-script m ["c" "m 10 12"]))))))

(deftest listing-marks-the-program-counter
  (is (= ["  0: ldc R0 5"
          "  1: ldc R1 1"      ; after s
          "  2: prr R0"        ; after s
          ">  2: prr R0"       ; d 2 5
          "   3: sub R0 R1"
          "   4: bne R0 2"
          "   5: hlt"]
         (dbg/run-script (machine "count_down.as") ["s" "s" "d 2 5"])))
  (testing "without arguments the listing starts at the program counter"
    (is (= [">  0: ldc R0 5"
            "   1: ldc R1 1"
            "   2: prr R0"]
           (take 3 (rest (dbg/run-script (machine "count_down.as") ["d"])))))))

(deftest bad-input-is-reported-not-fatal
  (is (= ["  0: ldc R0 5"
          "unknown command x (h for help)"
          "usage: m <address> [<address>]"
          "usage: m <address> [<address>]"
          "  1: ldc R1 1"]
         (dbg/run-script (machine "count_down.as") ["x" "m" "m 999" "" "s"]))))

(deftest stepping-a-halted-machine
  (is (= ["  0: ldc R0 5" "5" "4" "3" "2" "1" "halted" "machine is halted" "machine is halted"]
         (dbg/run-script (machine "count_down.as") ["c" "s" "c"]))))

(deftest quitting-stops-reading
  (let [io (dbg/scripted-io ["q" "s" "s"])
        final (dbg/debug (machine "count_down.as") io)]
    (is (:done? final))
    (is (= 0 (get-in final [:vm :pc])) "the lines after q were never read")
    (is (= ["  0: ldc R0 5"] @(:written io)))))

(deftest help-comes-from-the-table
  (let [lines (dbg/run-script (machine "count_down.as") ["h"])]
    (is (= (inc (count dbg/commands)) (count lines)))
    (is (some #(re-find #"^s +step one instruction" %) lines))))

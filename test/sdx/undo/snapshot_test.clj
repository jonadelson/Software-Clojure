(ns sdx.undo.snapshot-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.undo.history :as hist]
            [sdx.undo.snapshot :as snap]
            [sdx.viewer.screen :as scr]))

(def lines ["abc" "de"])

(defn- after
  "The state after pressing each of `keys` in turn."
  [keys]
  (reduce snap/step (snap/make-state lines 5) keys))

(deftest undo-and-redo
  (let [typed (after ["x" "y"])]
    (is (= ["xyabc" "de"] (:lines typed)))
    (is (= 2 (count (:past typed))))
    (let [once (snap/undo typed)]
      (is (= ["xabc" "de"] (:lines once)))
      (is (= [0 1] (:cursor once)))
      (is (= 1 (count (:future once))))
      (let [twice (snap/undo once)]
        (is (= lines (:lines twice)))
        (is (= ["xyabc" "de"] (:lines (snap/redo (snap/redo twice)))))))))

(deftest nothing-to-undo-or-redo
  (let [start (snap/make-state lines 5)]
    (is (= start (snap/undo start)))
    (is (= start (snap/redo start)))))

(deftest a-new-edit-discards-the-future
  (let [state (after ["x" :ctrl-z "y"])]
    (is (= ["yabc" "de"] (:lines state)))
    (is (= [] (:future state)))))

(deftest snapshots-share-unchanged-lines
  (let [big (vec (map #(str "line " %) (range 1000)))
        before (snap/make-state big 5)
        after (snap/perform before {:op :insert :row 0 :col 0 :char "x"})
        saved (:lines (peek (:past after)))]
    (is (= "xline 0" (get-in after [:lines 0])))
    (is (= "line 0" (saved 0)))
    (testing "every line but the edited one is the very same string object"
      (is (identical? (saved 1) (get-in after [:lines 1])))
      (is (identical? (saved 999) (get-in after [:lines 999]))))))

(deftest the-two-designs-agree
  (let [scripts [["x" "y" :ctrl-z]
                 ["a" :enter "b" :ctrl-z :ctrl-z :ctrl-y]
                 [:down :backspace :ctrl-z]
                 [:right :right :backspace :ctrl-z :ctrl-y "q"]
                 [:ctrl-z :ctrl-y "z" :ctrl-z :ctrl-z :ctrl-y :ctrl-y]]]
    (doseq [keys scripts]
      (testing (pr-str keys)
        (let [by-history (reduce hist/step (hist/make-state lines 5) keys)
              by-snapshot (reduce snap/step (snap/make-state lines 5) keys)]
          (is (= (:lines by-history) (:lines by-snapshot)))
          (is (= (:cursor by-history) (:cursor by-snapshot))))))))

(deftest a-scripted-session
  (let [keys ["h" "i" :enter "!" :ctrl-z :ctrl-z :ctrl-z :ctrl-y "?" :ctrl-q]
        screen (scr/headless 3 5 keys)
        final (snap/run screen (snap/make-state [] 3))]
    (testing "three undos leave \"h\", the redo brings \"i\" back, then ? is typed"
      (is (= ["hi?"] (:lines final))))
    (is (= ["hi?  " "     " "     "] (scr/rows screen)))
    (is (= [0 3] (scr/cursor screen)))))

(deftest main-without-a-file-prints-usage
  (require 'sdx.undo.main)
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      ((resolve 'sdx.undo.main/-main)))
    (is (re-find #"usage" (str err)))))

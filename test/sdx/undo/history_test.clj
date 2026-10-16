(ns sdx.undo.history-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.undo.edit :as edit]
            [sdx.undo.history :as hist]
            [sdx.viewer.screen :as scr]))

(def lines ["abc" "de"])

(defn- after
  "The state after pressing each of `keys` in turn."
  [keys]
  (reduce hist/step (hist/make-state lines 5) keys))

(deftest inverting-actions
  (let [actions [{:op :insert :row 0 :col 1 :char "X"}
                 {:op :delete :row 0 :col 1 :char "b"}
                 {:op :split :row 0 :col 1}
                 {:op :join :row 0 :col 3}]]
    (doseq [action actions]
      (testing (pr-str action)
        (is (= action (hist/invert (hist/invert action)))
            "inverting twice gives the action back")
        (let [start (hist/make-state lines 5)
              done (edit/apply-action start action)
              undone (edit/apply-action done (hist/invert action))]
          (is (= lines (:lines undone))
              "applying an action and then its inverse restores the text"))))))

(deftest undo-and-redo
  (let [typed (after ["x" "y"])]
    (is (= ["xyabc" "de"] (:lines typed)))
    (is (= 2 (count (:done typed))))
    (let [once (hist/undo typed)]
      (is (= ["xabc" "de"] (:lines once)))
      (is (= [0 1] (:cursor once)))
      (is (= 1 (count (:undone once))))
      (let [twice (hist/undo once)]
        (is (= lines (:lines twice)))
        (is (= [0 0] (:cursor twice)))
        (is (= ["xabc" "de"] (:lines (hist/redo twice))))
        (is (= ["xyabc" "de"] (:lines (hist/redo (hist/redo twice)))))))))

(deftest nothing-to-undo-or-redo
  (let [start (hist/make-state lines 5)]
    (is (= start (hist/undo start)))
    (is (= start (hist/redo start)))))

(deftest a-new-edit-discards-the-redo-list
  (let [state (after ["x" :ctrl-z "y"])]
    (is (= ["yabc" "de"] (:lines state)))
    (is (= [] (:undone state)))
    (is (= ["yabc" "de"] (:lines (hist/redo state))))))

(deftest undoing-a-join-restores-both-lines
  (let [state (after [:down :backspace])]
    (is (= ["abcde"] (:lines state)))
    (let [back (hist/undo state)]
      (is (= ["abc" "de"] (:lines back)))
      (is (= [1 0] (:cursor back))))))

(deftest a-scripted-session
  (let [keys ["h" "i" :enter "!" :ctrl-z :ctrl-z :ctrl-z :ctrl-y "?" :ctrl-q]
        screen (scr/headless 3 5 keys)
        final (hist/run screen (hist/make-state [] 3))]
    (testing "three undos leave \"h\", the redo brings \"i\" back, then ? is typed"
      (is (= ["hi?"] (:lines final))))
    (is (= ["hi?  " "     " "     "] (scr/rows screen)))
    (is (= [0 3] (scr/cursor screen)))))

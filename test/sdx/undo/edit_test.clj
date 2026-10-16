(ns sdx.undo.edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.undo.edit :as edit]
            [sdx.viewer.screen :as scr]))

(def lines ["abc" "de"])

(defn- state
  "A small editor state with the cursor at `row`, `col`."
  [row col]
  (assoc (edit/make-state lines 5) :cursor [row col]))

(deftest applying-actions
  (doseq [[action expected-lines expected-cursor]
          [[{:op :insert :row 0 :col 1 :char "X"} ["aXbc" "de"] [0 2]]
           [{:op :insert :row 1 :col 2 :char "!"} ["abc" "de!"] [1 3]]
           [{:op :delete :row 0 :col 1 :char "b"} ["ac" "de"] [0 1]]
           [{:op :split :row 0 :col 1} ["a" "bc" "de"] [1 0]]
           [{:op :split :row 1 :col 2} ["abc" "de" ""] [2 0]]
           [{:op :join :row 0 :col 3} ["abcde"] [0 3]]]]
    (testing (pr-str action)
      (let [result (edit/apply-action (state 0 0) action)]
        (is (= expected-lines (:lines result)))
        (is (= expected-cursor (:cursor result)))))))

(deftest keys-become-actions
  (is (= {:op :insert :row 0 :col 2 :char "z"} (edit/key->action (state 0 2) "z")))
  (is (= {:op :split :row 1 :col 1} (edit/key->action (state 1 1) :enter)))
  (testing "backspace deletes the character before the cursor"
    (is (= {:op :delete :row 0 :col 1 :char "b"} (edit/key->action (state 0 2) :backspace))))
  (testing "backspace at the start of a line joins it to the one above"
    (is (= {:op :join :row 0 :col 3} (edit/key->action (state 1 0) :backspace))))
  (testing "nothing to delete at the very start"
    (is (nil? (edit/key->action (state 0 0) :backspace))))
  (testing "movement and control keys are not edits"
    (is (nil? (edit/key->action (state 0 0) :down)))
    (is (nil? (edit/key->action (state 0 0) :ctrl-q)))))

(deftest letters-are-text-not-commands
  (is (nil? (get edit/bindings "j")))
  (is (nil? (get edit/bindings "q")))
  (is (= [1 0] (:cursor (edit/step (state 0 0) :down))))
  (is (:quit (edit/step (state 0 0) :ctrl-q))))

(deftest an-empty-file-has-one-line-to-type-on
  (is (= [""] (:lines (edit/make-state [] 5))))
  (is (= ["x"] (:lines (edit/step (edit/make-state [] 5) "x")))))

(deftest a-scripted-session
  (let [keys ["h" "i" :enter "t" "h" "e" "r" "e" :backspace :backspace :up :right :right "!" :ctrl-q]
        screen (scr/headless 3 6 keys)
        final (edit/run screen (edit/make-state [] 3))]
    (is (= ["hi!" "the"] (:lines final)))
    (is (= [0 3] (:cursor final)))
    (is (= ["hi!   " "the   " "      "] (scr/rows screen)))
    (is (= [0 3] (scr/cursor screen)))))

(deftest typing-past-the-bottom-scrolls
  (let [screen (scr/headless 2 4 [:right :enter :enter :enter "x" :ctrl-q])
        final (edit/run screen (edit/make-state ["a"] 2))]
    (is (= ["a" "" "" "x"] (:lines final)))
    (is (= 2 (:top final)))
    (is (= ["    " "x   "] (scr/rows screen)))))

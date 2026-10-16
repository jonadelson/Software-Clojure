(ns sdx.viewer.cursor-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.viewer.screen :as scr]
            [sdx.viewer.cursor :as cur]))

(def lines ["abc" "" "defgh" "ij"])

(defn- after
  "The cursor after pressing each of `keys` in turn, with no screen at all."
  [keys]
  (:cursor (reduce cur/step (cur/make-state lines) keys)))

(deftest moving-around
  (is (= [1 0] (after ["j"])))
  (is (= [1 0] (after [:down])))
  (is (= [2 2] (after ["j" "j" "l" "l"])))
  (testing "passing through an empty line loses the column for good"
    (is (= [0 0] (after [:right :down :down :up :up])))))

(deftest cannot-leave-the-text
  (is (= [0 0] (after ["k" "h"])))
  (is (= [3 0] (after (repeat 10 "j"))))
  (testing "the column may rest just past the last character"
    (is (= [0 3] (after (repeat 10 "l"))))))

(deftest column-follows-the-line
  (testing "moving from a long line to a short one pulls the column in"
    (is (= [1 0] (after ["l" "l" "j"])))
    (is (= [3 2] (after ["j" "j" "l" "l" "l" "l" "j"])))))

(deftest unknown-keys-are-ignored
  (is (= [0 0] (after ["z" :page-down :enter]))))

(deftest quitting
  (is (:quit (cur/step (cur/make-state lines) "q")))
  (is (:quit (cur/step (cur/make-state lines) :ctrl-c)))
  (is (not (:quit (cur/step (cur/make-state lines) "j")))))

(deftest render-places-the-cursor
  (let [screen (scr/headless 4 6 ["j" "j" "l" "q"])
        state (cur/run screen (cur/make-state lines))]
    (is (= [2 1] (:cursor state)))
    (is (= ["abc   " "      " "defgh " "ij    "] (scr/rows screen)))
    (is (= [2 1] (scr/cursor screen)))))

(deftest the-cursor-can-fall-off-the-screen
  (testing "stage 2 has no scrolling: on a two-row screen the cursor ends up on row 3"
    (let [screen (scr/headless 2 6 ["j" "j" "j" "q"])]
      (cur/run screen (cur/make-state lines))
      (is (= [3 0] (scr/cursor screen)))
      (is (= ["abc   " "      "] (scr/rows screen))))))

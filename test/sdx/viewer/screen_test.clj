(ns sdx.viewer.screen-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.viewer.screen :as scr]))

(deftest overlay-cases
  (doseq [[row col text expected]
          [["....." 0 "ab" "ab..."]
           ["....." 2 "ab" "..ab."]
           ["....." 4 "ab" "....a"]    ; clipped at the right edge
           ["....." 5 "ab" "....."]    ; starts off the row: dropped
           ["....." -1 "ab" "....."]   ; starts before the row: dropped
           ["....." 1 "" "....."]
           ["....." 0 "abcdefg" "abcde"]]]
    (testing (pr-str [row col text])
      (is (= expected (scr/overlay row col text))))))

(deftest a-fresh-screen-is-blank
  (let [screen (scr/headless 2 4 [])]
    (is (= [2 4] (scr/dimensions screen)))
    (is (= ["    " "    "] (scr/rows screen)))
    (is (= [0 0] (scr/cursor screen)))))

(deftest writing-and-clearing
  (let [screen (scr/headless 3 5 [])]
    (scr/write-at screen 0 0 "hello world")
    (scr/write-at screen 1 2 "xy")
    (scr/write-at screen 7 0 "off the bottom")
    (scr/write-at screen -1 0 "off the top")
    (is (= ["hello" "  xy " "     "] (scr/rows screen)))
    (scr/move-cursor screen 1 3)
    (is (= [1 3] (scr/cursor screen)))
    (scr/erase screen)
    (is (= ["     " "     " "     "] (scr/rows screen)))))

(deftest scripted-keys
  (let [screen (scr/headless 1 1 ["j" :down "q"])]
    (is (= "j" (scr/read-key screen)))
    (is (= :down (scr/read-key screen)))
    (is (= "q" (scr/read-key screen)))
    (is (nil? (scr/read-key screen)))
    (is (nil? (scr/read-key screen)))))

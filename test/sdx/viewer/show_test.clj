(ns sdx.viewer.show-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.viewer.screen :as scr]
            [sdx.viewer.show :as show]))

(def lines ["one" "two" "three" "four" "five"])

(deftest render-shows-what-fits
  (let [screen (scr/headless 3 4 [])]
    (show/render screen (show/make-state lines))
    (testing "rows beyond the screen's height are not drawn"
      (is (= ["one " "two " "thre"] (scr/rows screen))))
    (is (= [0 0] (scr/cursor screen)))))

(deftest render-short-file
  (let [screen (scr/headless 3 4 [])]
    (show/render screen (show/make-state ["hi"]))
    (is (= ["hi  " "    " "    "] (scr/rows screen)))))

(deftest run-waits-for-q
  (let [screen (scr/headless 3 4 ["x" "y" "q" "z"])
        state (show/run screen (show/make-state lines))]
    (is (= lines (:lines state)))
    (testing "keys after q are never read"
      (is (= "z" (scr/read-key screen))))))

(deftest run-stops-when-keys-run-out
  (let [screen (scr/headless 3 4 ["x"])]
    (is (map? (show/run screen (show/make-state lines))))))

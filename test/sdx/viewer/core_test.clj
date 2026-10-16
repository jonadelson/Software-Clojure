(ns sdx.viewer.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.viewer.screen :as scr]
            [sdx.viewer.core :as core]))

(def lines (vec (map #(str "line " %) (range 10))))

(defn- after
  "The state after pressing each of `keys` on a screen `height` rows tall."
  [height keys]
  (reduce core/step (core/make-state lines height) keys))

(deftest moving-without-scrolling
  (let [state (after 5 ["j" "j" "l"])]
    (is (= [2 1] (:cursor state)))
    (is (= 0 (:top state)))))

(deftest scrolling-down-and-back
  (testing "the window moves only when the cursor would leave it"
    (is (= 0 (:top (after 3 ["j" "j"]))))
    (is (= 1 (:top (after 3 ["j" "j" "j"]))))
    (is (= 7 (:top (after 3 (repeat 20 "j"))))))
  (testing "moving up above the window pulls it back"
    (let [state (after 3 (concat (repeat 5 "j") (repeat 4 "k")))]
      (is (= [1 0] (:cursor state)))
      (is (= 1 (:top state))))))

(deftest paging
  (let [down (after 4 [:page-down])]
    (is (= [4 0] (:cursor down)))
    (is (= 4 (:top down))))
  (let [twice (after 4 [:page-down :page-down])]
    (is (= [8 0] (:cursor twice)))
    (is (= 6 (:top twice)) "the window stops at the end of the text"))
  (let [back (after 4 [:page-down :page-down :page-up])]
    (is (= [4 0] (:cursor back)))
    (is (= 2 (:top back)))))

(deftest scroll-to-cursor-is-minimal
  (let [state (core/make-state lines 3)]
    (is (= 0 (:top (core/scroll-to-cursor (assoc state :cursor [2 0])))))
    (is (= 1 (:top (core/scroll-to-cursor (assoc state :cursor [3 0])))))
    (is (= 2 (:top (core/scroll-to-cursor (assoc state :cursor [2 0] :top 5)))))))

(deftest render-shows-the-window
  (let [screen (scr/headless 3 6 [])]
    (core/render screen (assoc (core/make-state lines 3) :top 4 :cursor [5 2]))
    (is (= ["line 4" "line 5" "line 6"] (scr/rows screen)))
    (is (= [1 2] (scr/cursor screen)))))

(deftest a-scripted-session
  (let [screen (scr/headless 3 6 ["j" "j" "j" "j" :right "q" "k"])
        state (core/run screen (core/make-state lines 3))]
    (is (= [4 1] (:cursor state)))
    (is (= ["line 2" "line 3" "line 4"] (scr/rows screen)))
    (is (= [2 1] (scr/cursor screen)))
    (testing "the loop stopped at q"
      (is (= "k" (scr/read-key screen))))))

(deftest start-fits-the-screen
  (let [screen (scr/headless 4 6 [:page-down])
        state (core/start screen lines)]
    (is (= 4 (:height state)))
    (is (= [4 0] (:cursor state)))
    (is (= ["line 4" "line 5" "line 6" "line 7"] (scr/rows screen)))))

(deftest custom-bindings
  (let [state (core/step {"n" core/page-down} (core/make-state lines 2) "n")]
    (is (= [2 0] (:cursor state))))
  (is (= [0 0] (:cursor (core/step {"n" core/page-down} (core/make-state lines 2) "j")))))

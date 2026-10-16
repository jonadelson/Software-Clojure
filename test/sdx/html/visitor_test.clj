(ns sdx.html.visitor-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.html.parser :as p]
            [sdx.html.visitor :as v]))

(def page
  (p/parse "<html><body><h1>Title</h1><ul><li>a</li><li>b</li></ul></body></html>"))

(deftest enter-and-exit-order
  (let [log (v/walk page
                    {:enter (fn [log node _] (conj log [:enter (:tag node)]))
                     :exit (fn [log node _] (conj log [:exit (:tag node)]))}
                    [])]
    (is (= [[:enter "html"] [:enter "body"]
            [:enter "h1"] [:exit "h1"]
            [:enter "ul"] [:enter "li"] [:exit "li"] [:enter "li"] [:exit "li"] [:exit "ul"]
            [:exit "body"] [:exit "html"]]
           log))))

(deftest paths-describe-where-a-node-is
  (let [paths (v/walk page
                      {:enter (fn [paths _ path] (conj paths path))}
                      [])]
    (is (= [["html"]
            ["html" "body"]
            ["html" "body" "h1"]
            ["html" "body" "ul"]
            ["html" "body" "ul" "li"]
            ["html" "body" "ul" "li"]]
           paths)))
  (testing "text nodes get the path of their enclosing element"
    (is (= [["html" "body" "h1"] ["html" "body" "ul" "li"] ["html" "body" "ul" "li"]]
           (v/walk page {:text (fn [paths _ path] (conj paths path))} [])))))

(deftest missing-callbacks-are-skipped
  (is (= 42 (v/walk page {} 42))))

(deftest tools-built-on-walk
  (is (= {"html" 1 "body" 1 "h1" 1 "ul" 1 "li" 2} (v/tag-counts page)))
  (is (= ["Title" "a" "b"] (v/text-of page)))
  (is (= 4 (v/max-depth page)))
  (is (= 0 (v/max-depth (p/parse "just text")))))

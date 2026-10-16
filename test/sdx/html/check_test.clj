(ns sdx.html.check-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.html.check :as c]
            [sdx.html.parser :as p]))

(defn- messages
  "Just the messages of the violations found in some HTML."
  [html]
  (mapv :message (c/validate (p/parse html))))

(deftest a-clean-page-has-no-violations
  (is (= [] (messages "<html lang=\"en\"><head><title>T</title></head><body><p class=\"x\">Hi <a href=\"a\">there</a></p><ul><li>one</li></ul></body></html>"))))

(deftest unknown-tags
  (is (= ["unknown tag <blink>"] (messages "<p><blink>no</blink></p>"))))

(deftest disallowed-attributes
  (is (= ["<a> may not have attribute onclick"]
         (messages "<a href=\"x\" onclick=\"run()\">x</a>")))
  (testing "global attributes are fine on any known tag"
    (is (= [] (messages "<p id=\"a\" class=\"b\" style=\"c\" title=\"d\">x</p>"))))
  (testing "attributes of unknown tags are not reported twice"
    (is (= ["unknown tag <blink>"] (messages "<blink onclick=\"x\">no</blink>")))))

(deftest nesting-rules
  (is (= ["<li> must be inside <ol> or <ul>, not <div>"]
         (messages "<div><li>x</li></div>")))
  (is (= ["<li> must be inside <ol> or <ul>, not the top level"]
         (messages "<li>x</li>")))
  (is (= ["<td> must be inside <tr>, not <table>"]
         (messages "<table><td>x</td></table>")))
  (is (= [] (messages "<ol><li>x</li></ol><table><tr><td>y</td></tr></table>"))))

(deftest duplicate-ids
  (is (= ["id \"top\" is used more than once"]
         (messages "<div id=\"top\"><p id=\"top\">x</p></div>")))
  (is (= [] (messages "<div id=\"a\"><p id=\"b\">x</p></div>"))))

(deftest violations-carry-paths-and-rules
  (is (= [{:rule :nesting
           :path ["div" "li"]
           :message "<li> must be inside <ol> or <ul>, not <div>"}]
         (c/validate (p/parse "<div><li>x</li></div>"))))
  (is (= "div/li" (c/path->string ["div" "li"]))))

(deftest checks-can-be-chosen
  (let [tree (p/parse "<div><li onclick=\"x\">y</li></div>")]
    (is (= [:nesting] (map :rule (c/validate tree [c/check-nesting] []))))
    (is (= [:attribute] (map :rule (c/validate tree [c/check-attributes] []))))
    (is (= [] (c/validate tree [] [])))))

(deftest a-custom-check
  (testing "any (fn [node path]) is a check"
    (let [no-empty-paragraphs (fn [node path]
                                (when (and (= "p" (:tag node)) (empty? (:children node)))
                                  [{:rule :empty-p :path path :message "empty paragraph"}]))]
      (is (= ["empty paragraph"]
             (map :message (c/validate (p/parse "<p></p><p>ok</p>")
                                       [no-empty-paragraphs] [])))))))

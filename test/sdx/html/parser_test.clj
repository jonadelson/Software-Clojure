(ns sdx.html.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.html.parser :as p]))

(deftest tokenizing-tags-and-text
  (is (= [{:type :open :tag "p" :attrs {} :pos 0}
          {:type :text :text "hello" :pos 3}
          {:type :close :tag "p" :pos 8}]
         (p/tokenize "<p>hello</p>"))))

(deftest tokenizing-attributes
  (is (= {"href" "x.html" "class" "big" "hidden" ""}
         (:attrs (first (p/tokenize "<a href=\"x.html\" class=\"big\" hidden>")))))
  (testing "tags may span lines"
    (is (= {"src" "a.png" "alt" "A"}
           (:attrs (first (p/tokenize "<img\n  src=\"a.png\"\n  alt=\"A\">")))))))

(deftest tokenizing-self-closing-tags
  (is (= {:type :open :tag "br" :attrs {} :pos 0 :self-closing true}
         (first (p/tokenize "<br/>"))))
  (is (= {:type :open :tag "img" :attrs {"src" "a.png"} :pos 0 :self-closing true}
         (first (p/tokenize "<img src=\"a.png\" />")))))

(deftest tokenizing-errors
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"never closed"
                        (p/tokenize "<p oops")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no name"
                        (p/tokenize "<>"))))

(deftest parsing-a-nested-document
  (is (= (p/element :document {}
                    (p/element "ul" {"class" "x"}
                               (p/element "li" {} "one")
                               (p/element "li" {} "two")))
         (p/parse "<ul class=\"x\"><li>one</li><li>two</li></ul>"))))

(deftest parsing-keeps-whitespace-text
  (is (= [(p/element "p" {} "a") "\n" (p/element "p" {} "b")]
         (:children (p/parse "<p>a</p>\n<p>b</p>")))))

(deftest parsing-self-closing-elements
  (is (= (p/element :document {} "a" (p/element "br" {}) "b")
         (p/parse "a<br/>b"))))

(deftest parsing-reports-mismatched-tags
  (let [e (try (p/parse "<p><em>x</p>")
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= "expected </em> but found </p>" (ex-message e)))
    (is (= {:pos 8 :expected "em" :found "p"} (ex-data e)))))

(deftest parsing-reports-unclosed-and-unopened-tags
  (let [e (try (p/parse "<div><p>text</p>")
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= "<div> is never closed" (ex-message e)))
    (is (= 0 (:pos (ex-data e)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing is open"
                        (p/parse "text</p>"))))

(deftest round-trip
  (testing "parsing and unparsing gives back the text when attributes are sorted"
    (doseq [html ["<p>hello</p>"
                  "<a class=\"big\" href=\"x.html\">link</a>"
                  "<ul>\n  <li>one</li>\n  <li>two</li>\n</ul>"]]
      (is (= html (p/->html (p/parse html)))))))

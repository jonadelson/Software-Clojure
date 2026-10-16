(ns sdx.template-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.template :as t]))

(deftest looking-up-variables
  (let [env [{:a 1 :b 2} {:b 3 :c nil}]]
    (is (= 1 (t/lookup env :a)))
    (testing "the innermost frame wins"
      (is (= 3 (t/lookup env :b))))
    (testing "bound to nil is still bound"
      (is (nil? (t/lookup env :c))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown variable :d"
                          (t/lookup env :d)))))

(deftest plain-html-passes-through
  (let [html "<p class=\"x\">Hi <em>there</em></p>\n"]
    (is (= html (t/expand html {})))))

(deftest z-var-replaces-content
  (is (= "<p class=\"greeting\">Hello</p>"
         (t/expand "<p class=\"greeting\" z-var=\"word\">placeholder</p>" {:word "Hello"})))
  (testing "values are escaped so they cannot inject markup"
    (is (= "<p>a &lt;b&gt; &amp; c</p>"
           (t/expand "<p z-var=\"x\">?</p>" {:x "a <b> & c"}))))
  (testing "non-strings are shown with str"
    (is (= "<p>42</p>" (t/expand "<p z-var=\"x\">?</p>" {:x 42})))))

(deftest z-num-formats-numbers
  (is (= "1,234,567" (t/format-number 1234567)))
  (is (= "999" (t/format-number 999)))
  (is (= "-1,000" (t/format-number -1000)))
  (is (= "1,234.50" (t/format-number 1234.5)))
  (is (= "3.14" (t/format-number 3.14159)))
  (is (= "0.50" (t/format-number 1/2)))
  (is (= "<td>12,000</td>" (t/expand "<td z-num=\"n\">0</td>" {:n 12000}))))

(deftest z-if-keeps-or-drops
  (let [template "<p z-if=\"show\">yes</p><p>always</p>"]
    (is (= "<p>yes</p><p>always</p>" (t/expand template {:show true})))
    (is (= "<p>always</p>" (t/expand template {:show false})))
    (is (= "<p>always</p>" (t/expand template {:show nil})))
    (testing "any truthy value counts, including empty collections"
      (is (= "<p>yes</p><p>always</p>" (t/expand template {:show []}))))))

(deftest z-loop-repeats
  (is (= "<ul><li>a</li><li>b</li><li>c</li></ul>"
         (t/expand "<ul><li z-loop=\"x:xs\" z-var=\"x\">?</li></ul>" {:xs ["a" "b" "c"]})))
  (testing "an empty collection produces nothing"
    (is (= "<ul></ul>" (t/expand "<ul><li z-loop=\"x:xs\" z-var=\"x\">?</li></ul>" {:xs []}))))
  (testing "the loop variable shadows an outer one and is gone afterwards"
    (is (= "<p>outer</p><p>1</p><p>2</p><p>outer</p>"
           (t/expand "<p z-var=\"x\">?</p><p z-loop=\"x:xs\" z-var=\"x\">?</p><p z-var=\"x\">?</p>"
                     {:x "outer" :xs [1 2]}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"z-loop needs item:items"
                        (t/expand "<p z-loop=\"xs\">?</p>" {:xs []}))))

(deftest directives-nest
  (let [template "<table><tr z-loop=\"row:rows\"><td z-loop=\"cell:row\" z-var=\"cell\">?</td></tr></table>"]
    (is (= "<table><tr><td>1</td><td>2</td></tr><tr><td>3</td><td>4</td></tr></table>"
           (t/expand template {:rows [[1 2] [3 4]]}))))
  (testing "a loop body can see variables from outside the loop"
    (is (= "<p><span>x</span>-<span>1</span></p><p><span>x</span>-<span>2</span></p>"
           (t/expand "<p z-loop=\"n:ns\"><span z-var=\"prefix\">?</span>-<span z-var=\"n\">?</span></p>"
                     {:prefix "x" :ns [1 2]})))))

(deftest several-directives-on-one-element
  (testing "z-if is applied before z-loop, which is applied before z-var"
    (let [template "<li z-var=\"x\" z-loop=\"x:xs\" z-if=\"show\">?</li>"]
      (is (= "<li>a</li><li>b</li>" (t/expand template {:show true :xs ["a" "b"]})))
      (is (= "" (t/expand template {:show false :xs ["a" "b"]}))))))

(deftest unknown-directives-are-errors
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown directive z-oops on <p>"
                        (t/expand "<p z-oops=\"x\">?</p>" {}))))

(deftest adding-a-directive
  (testing "a new directive is one defmethod, with no change to the expander"
    (defmethod t/directive "z-upper" [_ env node value]
      (t/expand-node env (-> node
                             (update :attrs dissoc "z-upper")
                             (assoc :children [(clojure.string/upper-case (t/lookup env (keyword value)))]))))
    (is (= "<p>LOUD</p>" (t/expand "<p z-upper=\"x\">?</p>" {:x "loud"})))
    (remove-method t/directive "z-upper")))

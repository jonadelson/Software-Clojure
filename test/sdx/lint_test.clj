(ns sdx.lint-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [sdx.lint :as lint]))

(deftest reading-keeps-line-numbers
  (let [forms (lint/read-forms "(defn f [x]\n  x)\n\n(def y\n  2)")]
    (is (= 2 (count forms)))
    (is (= '(defn f [x] x) (first forms)))
    (is (= 1 (lint/line-of (first forms))))
    (is (= 4 (lint/line-of (second forms))))))

(deftest reading-does-not-evaluate
  (is (= '[(System/exit 1)] (lint/read-forms "(System/exit 1)")))
  (is (thrown? Exception (lint/read-forms "#=(System/exit 1)"))))

(deftest reading-is-not-fooled-by-strings-or-comments
  (is (= 1 (count (lint/read-forms "; (ignored)\n\"(also ignored)\"")))))

(deftest subforms-walks-everything
  (is (= '[(a [b {:c d}]) a [b {:c d}] b {:c d} [:c d] :c d]
         (lint/subforms '(a [b {:c d}])))))

(deftest counting-parameters
  (is (= '[[a b]] (lint/arities '(defn f [a b] a))))
  (is (= '[[] [x] [x y]]
         (lint/arities '(defn f "doc" {:private true} ([] 0) ([x] x) ([x y] y)))))
  (is (= 2 (lint/param-count '[a & rest]))))

(deftest too-many-params
  (let [text "(defn ok [a b] a)\n(defn wide [a b c d e] a)\n(defn multi ([a] a) ([a b c] c))"]
    (is (= [{:line 2 :message "wide takes 5 parameters (limit 4)"}]
           (lint/too-many-params 4 (lint/read-forms text))))
    (testing "the limit is a parameter, and every arity is checked"
      (is (= [{:line 2 :message "wide takes 5 parameters (limit 2)"}
              {:line 3 :message "multi takes 3 parameters (limit 2)"}]
             (lint/too-many-params 2 (lint/read-forms text)))))))

(deftest too-deep
  (let [text "(defn f [x]\n  (when x\n    (let [y 1]\n      (if y\n        (do (println y))))))"]
    (is (= [] (lint/too-deep 6 (lint/read-forms text))))
    (is (= [{:line 5 :message "nesting is 5 levels deep (limit 4)"}]
           (lint/too-deep 4 (lint/read-forms text))))
    (testing "one finding per top-level form, at the first place the limit is crossed"
      (is (= [{:line 3 :message "nesting is 3 levels deep (limit 2)"}]
             (lint/too-deep 2 (lint/read-forms text)))))))

(deftest missing-docstring
  (let [text "(defn good\n  \"Documented.\"\n  [x] x)\n(defn bad [x] x)\n(defmacro worse [x] x)\n(defn- private-too [x] x)"]
    (is (= [{:line 4 :message "bad has no docstring"}
            {:line 5 :message "worse has no docstring"}
            {:line 6 :message "private-too has no docstring"}]
           (lint/missing-docstring (lint/read-forms text))))))

(deftest binding-names-see-through-destructuring
  (is (= '[a] (lint/binding-names 'a)))
  (is (= '[a b rest] (lint/binding-names '[a [b] & rest])))
  (is (= '[x y whole] (lint/binding-names '{:keys [x y] :as whole}))))

(deftest unused-bindings
  (testing "a binding used in the body or a later binding is fine"
    (is (= [] (lint/unused-bindings (lint/read-forms "(let [a 1 b (inc a)] b)")))))
  (testing "a binding nobody mentions is reported with the let's line"
    (is (= [{:line 2 :message "b is bound but never used"}]
           (lint/unused-bindings (lint/read-forms "(defn f []\n  (let [a 1 b 2] a))")))))
  (testing "destructured names are checked one by one"
    (is (= [{:line 1 :message "y is bound but never used"}]
           (lint/unused-bindings (lint/read-forms "(let [{:keys [x y]} m] x)")))))
  (testing "underscore names are deliberately ignored"
    (is (= [] (lint/unused-bindings (lint/read-forms "(let [_ 1 _tmp 2] 3)")))))
  (testing "a quoted symbol is data, not a use"
    (is (= [{:line 1 :message "a is bound but never used"}]
           (lint/unused-bindings (lint/read-forms "(let [a 1] 'a)")))))
  (testing "lets nested inside other forms are found"
    (is (= [{:line 1 :message "z is bound but never used"}]
           (lint/unused-bindings (lint/read-forms "(defn f [x] (when x (let [z x] 1)))"))))))

(deftest duplicate-names
  (let [text "(def a 1)\n(defn b [] 2)\n(def a 3)\n(defmethod area :square [s] s)\n(defmethod area :circle [c] c)\n(defn a [] 4)"]
    (is (= [{:line 3 :message "a is already defined on line 1"}
            {:line 6 :message "a is already defined on line 1"}]
           (lint/duplicate-names (lint/read-forms text))))))

(deftest the-linter-is-a-list-of-checks
  (let [text "(defn f [a b c d e]\n  (let [unused 1] a))"]
    (testing "findings from every check, sorted by line"
      (is (= [{:line 1 :message "f has no docstring"}
              {:line 1 :message "f takes 5 parameters (limit 4)"}
              {:line 2 :message "unused is bound but never used"}]
             (lint/lint text))))
    (testing "a different list of checks gives a different linter"
      (is (= [{:line 1 :message "f has no docstring"}]
             (lint/lint text [lint/missing-docstring])))
      (is (= [] (lint/lint text []))))))

(deftest a-check-can-be-written-by-anyone
  (let [no-tabs (fn [forms]
                  (for [form forms
                        :when (some #(and (string? %) (.contains ^String % "\t")) (lint/subforms form))]
                    {:line (lint/line-of form) :message "string contains a tab"}))]
    (is (= [{:line 1 :message "string contains a tab"}]
           (lint/lint "(def s \"a\tb\")" [no-tabs])))))

(deftest linting-the-sample-file
  (let [findings (lint/lint-file (io/resource "lint/messy.clj"))]
    (is (= ["8: report has no docstring"
            "8: report takes 6 parameters (limit 4)"
            "9: unused is bound but never used"
            "13: area is already defined on line 3"
            "23: nesting is 7 levels deep (limit 6)"]
           (map #(str (:line %) ": " (:message %)) findings)))))

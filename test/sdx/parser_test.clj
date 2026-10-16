(ns sdx.parser-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sdx.glob.data :as glob]
            [sdx.parser :as p]))

;; ---------------------------------------------------------------------
;; Tokenizer

(deftest tokenizing
  (testing "nothing in, nothing out"
    (is (= [] (p/tokenize ""))))
  (testing "a run of ordinary characters is one literal"
    (is (= [{:type :lit :text "abc" :pos 0}] (p/tokenize "abc"))))
  (testing "special characters are tokens of their own"
    (is (= [{:type :any :pos 0}] (p/tokenize "*")))
    (is (= [{:type :one :pos 0}] (p/tokenize "?"))))
  (testing "literals and specials interleave, with positions"
    (is (= [{:type :lit :text "a" :pos 0}
            {:type :any :pos 1}
            {:type :lit :text ".txt" :pos 2}]
           (p/tokenize "a*.txt"))))
  (testing "braces and commas"
    (is (= [{:type :open :pos 0}
            {:type :lit :text "a" :pos 1}
            {:type :comma :pos 2}
            {:type :lit :text "b" :pos 3}
            {:type :close :pos 4}]
           (p/tokenize "{a,b}"))))
  (testing "adjacent specials produce no empty literals"
    (is (= [:any :any :one] (map :type (p/tokenize "**?"))))))

;; ---------------------------------------------------------------------
;; Parser

(deftest parsing-well-formed-patterns
  (is (= [] (p/parse "")))
  (is (= [[:lit "abc"]] (p/parse "abc")))
  (is (= [[:lit "a"] [:any] [:lit ".txt"]] (p/parse "a*.txt")))
  (is (= [[:lit "a"] [:one] [:lit "c"]] (p/parse "a?c")))
  (is (= [[:either [[:lit "a"]] [[:lit "b"]]]] (p/parse "{a,b}")))
  (is (= [[:lit "2023-"] [:any] [:lit "."] [:either [[:lit "pdf"]] [[:lit "txt"]]]]
         (p/parse "2023-*.{pdf,txt}")))
  (testing "alternatives can be whole patterns"
    (is (= [[:either [[:any] [:lit ".c"]] [[:lit "Makefile"]]]]
           (p/parse "{*.c,Makefile}"))))
  (testing "alternatives can be empty"
    (is (= [[:lit "a"] [:either [] [[:lit "-draft"]]]] (p/parse "a{,-draft}"))))
  (testing "groups nest"
    (is (= [[:either [[:lit "a"]] [[:either [[:lit "b"]] [[:lit "c"]]]]]]
           (p/parse "{a,{b,c}}")))))

(defn- error-of
  "The message and :pos of the parse error that `s` causes."
  [s]
  (try
    (p/parse s)
    nil
    (catch clojure.lang.ExceptionInfo e
      [(ex-message e) (:pos (ex-data e))])))

(deftest parsing-malformed-patterns
  (testing "an unclosed group reports where it was opened"
    (is (= ["unclosed '{' opened at position 3" 3] (error-of "abc{d,e")))
    (is (= ["unclosed '{' opened at position 1" 1] (error-of "a{{b}"))))
  (testing "a stray closing brace or comma is an error where it appears"
    (is (= ["unexpected '}' at position 1" 1] (error-of "a}b")))
    (is (= ["unexpected ',' at position 2" 2] (error-of "ab,c"))))
  (testing "well-formed patterns do not throw"
    (is (nil? (error-of "{a,b}c")))))

;; ---------------------------------------------------------------------
;; End to end

(def cases
  "Pattern text, a name, and whether the name should match."
  [["abc" "abc" true]
   ["abc" "abd" false]
   ["a*.txt" "abc.txt" true]
   ["a*.txt" "abc.md" false]
   ["*.txt" ".txt" true]
   ["a?c" "abc" true]
   ["a?c" "ac" false]
   ["2023-*.{pdf,txt}" "2023-report.pdf" true]
   ["2023-*.{pdf,txt}" "2023-notes.txt" true]
   ["2023-*.{pdf,txt}" "2022-notes.txt" false]
   ["2023-*.{pdf,txt}" "2023-notes.doc" false]
   ["{*.c,Makefile}" "main.c" true]
   ["{*.c,Makefile}" "Makefile" true]
   ["{*.c,Makefile}" "main.h" false]
   ["a{,-draft}.md" "a.md" true]
   ["a{,-draft}.md" "a-draft.md" true]
   ["{a,{b,c}}x" "cx" true]
   ["" "" true]
   ["" "x" false]])

(deftest matching-parsed-patterns
  (doseq [[pattern text expected] cases]
    (testing (str pattern " against " text)
      (is (= expected (glob/match (p/parse pattern) text)))
      (is (= expected (p/matches? pattern text))))))

(deftest round-trip
  (doseq [[pattern] cases]
    (is (= pattern (glob/pattern->string (p/parse pattern))))))

(deftest running-main
  (is (= ["b.txt" "c.txt"]
         (str/split-lines (with-out-str (p/-main "*.txt" "a.md" "b.txt" "c.txt")))))
  (is (= "" (with-out-str (p/-main "*.txt" "a.md"))))
  (is (str/starts-with? (with-out-str (p/-main)) "usage:")))

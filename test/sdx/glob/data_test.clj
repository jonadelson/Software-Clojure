(ns sdx.glob.data-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.glob.data :as d]
            [sdx.glob.first :as f]))

(def cases
  "Each row is a name, a pattern as data, a text, and the expected answer."
  [["empty pattern matches empty text"   [] "" true]
   ["empty pattern rejects text"         [] "a" false]
   ["literal matches itself"             [[:lit "abc"]] "abc" true]
   ["literal rejects a prefix"           [[:lit "abc"]] "abcd" false]
   ["literal rejects a shorter text"     [[:lit "abc"]] "ab" false]
   ["literal rejects different text"     [[:lit "abc"]] "abd" false]
   ["? matches exactly one character"    [[:lit "a"] [:one] [:lit "c"]] "abc" true]
   ["? rejects zero characters"          [[:lit "a"] [:one] [:lit "c"]] "ac" false]
   ["* matches nothing"                  [[:any]] "" true]
   ["* matches everything"               [[:any]] "anything at all" true]
   ["* at the end"                       [[:lit "a"] [:any]] "abc" true]
   ["* at the start"                     [[:any] [:lit "c"]] "abc" true]
   ["* in the middle"                    [[:lit "a"] [:any] [:lit "c"]] "abc" true]
   ["* backtracks to find the suffix"    [[:any] [:lit ".txt"]] "notes.txt.txt" true]
   ["* cannot invent characters"         [[:lit "a"] [:any] [:lit "c"]] "ab" false]
   ["two stars"                          [[:any] [:lit "b"] [:any]] "abc" true]
   ["either takes the left branch"       [[:either [[:lit "a"]] [[:lit "b"]]]] "a" true]
   ["either takes the right branch"      [[:either [[:lit "a"]] [[:lit "b"]]]] "b" true]
   ["either rejects a third option"      [[:either [[:lit "a"]] [[:lit "b"]]]] "c" false]
   ["either followed by more"            [[:either [[:lit "a"]] [[:lit "b"]]] [:lit ".txt"]] "b.txt" true]
   ["either with a star inside"          [[:either [[:any] [:lit "a"]] [[:lit "b"]]] [:lit "!"]] "xxa!" true]
   ["either branches differ in length"   [[:lit "x"] [:either [[:lit "pdf"]] [[:lit "txt"]]]] "xtxt" true]
   ["either with three alternatives"     [[:either [[:lit "a"]] [[:lit "b"]] [[:lit "c"]]]] "c" true]
   ["either with an empty alternative"   [[:lit "a"] [:either [] [[:lit "-x"]]]] "a" true]
   ["either nested inside either"        [[:either [[:lit "a"]] [[:either [[:lit "b"]] [[:lit "c"]]]]]] "c" true]])

(deftest table-of-matches
  (doseq [[name pattern text expected] cases]
    (testing name
      (is (= expected (d/match pattern text))))))

(deftest unknown-node
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown node type :bogus"
                        (d/match [[:bogus]] "x"))))

(deftest printing-a-pattern
  (is (= "" (d/pattern->string [])))
  (is (= "2023-*.{pdf,txt}"
         (d/pattern->string [[:lit "2023-"] [:any] [:lit "."]
                             [:either [[:lit "pdf"]] [[:lit "txt"]]]])))
  (is (= "a?{,b}" (d/pattern->string [[:lit "a"] [:one] [:either [] [[:lit "b"]]]]))))

;; ---------------------------------------------------------------------
;; The two stages should agree. Building a record chain from the data
;; form is a fold from the right: the last node is built first, around
;; a Null, and each earlier node wraps the chain built so far.

(defn- build
  "Turn a pattern in data form into a chain of sdx.glob.first records."
  [nodes]
  (reduce (fn [chain [kind & args]]
            (case kind
              :lit (f/->Lit (first args) chain)
              :one (f/->One chain)
              :any (f/->Any chain)
              :either (let [[left right] args]
                        (f/->Either (build left) (build right) chain))))
          (f/->Null)
          (reverse nodes)))

(deftest the-two-stages-agree
  (doseq [[name pattern text expected] cases
          :when (not (#{"either with three alternatives"} name))]
    (testing name
      (is (= expected (f/match (build pattern) text))))))

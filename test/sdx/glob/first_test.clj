(ns sdx.glob.first-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.glob.first :as g :refer [->Null ->Lit ->One ->Any ->Either]]))

(def cases
  "Each row is a name, a matcher, a text, and the expected answer."
  [["empty pattern matches empty text"   (->Null) "" true]
   ["empty pattern rejects text"         (->Null) "a" false]
   ["literal matches itself"             (->Lit "abc" (->Null)) "abc" true]
   ["literal rejects a prefix"           (->Lit "abc" (->Null)) "abcd" false]
   ["literal rejects a shorter text"     (->Lit "abc" (->Null)) "ab" false]
   ["literal rejects different text"     (->Lit "abc" (->Null)) "abd" false]
   ["? matches exactly one character"    (->Lit "a" (->One (->Lit "c" (->Null)))) "abc" true]
   ["? rejects zero characters"          (->Lit "a" (->One (->Lit "c" (->Null)))) "ac" false]
   ["* matches nothing"                  (->Any (->Null)) "" true]
   ["* matches everything"               (->Any (->Null)) "anything at all" true]
   ["* at the end"                       (->Lit "a" (->Any (->Null))) "abc" true]
   ["* at the start"                     (->Any (->Lit "c" (->Null))) "abc" true]
   ["* in the middle"                    (->Lit "a" (->Any (->Lit "c" (->Null)))) "abc" true]
   ["* backtracks to find the suffix"    (->Any (->Lit ".txt" (->Null))) "notes.txt.txt" true]
   ["* cannot invent characters"         (->Lit "a" (->Any (->Lit "c" (->Null)))) "ab" false]
   ["two stars"                          (->Any (->Lit "b" (->Any (->Null)))) "abc" true]
   ["either takes the left branch"       (->Either (->Lit "a" (->Null)) (->Lit "b" (->Null)) (->Null)) "a" true]
   ["either takes the right branch"      (->Either (->Lit "a" (->Null)) (->Lit "b" (->Null)) (->Null)) "b" true]
   ["either rejects a third option"      (->Either (->Lit "a" (->Null)) (->Lit "b" (->Null)) (->Null)) "c" false]
   ["either followed by more"            (->Either (->Lit "a" (->Null)) (->Lit "b" (->Null)) (->Lit ".txt" (->Null))) "b.txt" true]
   ["either with a star inside"          (->Either (->Any (->Lit "a" (->Null))) (->Lit "b" (->Null)) (->Lit "!" (->Null))) "xxa!" true]
   ["either branches differ in length"   (->Lit "x" (->Either (->Lit "pdf" (->Null)) (->Lit "txt" (->Null)) (->Null))) "xtxt" true]])

(deftest table-of-matches
  (doseq [[name matcher text expected] cases]
    (testing name
      (is (= expected (g/match matcher text))))))

(deftest every-answer-is-a-boolean
  (doseq [[_ matcher text] cases]
    (is (boolean? (g/match matcher text)))))

(deftest records-are-maps
  (let [m (->Lit "a" (->Null))]
    (is (= "a" (:chars m)))
    (is (= (->Null) (:rest m)))
    (is (= m (->Lit "a" (->Null))))))

(ns sdx.closures-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sdx.closures :as c]
            [sdx.interp.core :as core]
            [sdx.interp.main :as main]))

(defn- run
  "Evaluate `program` in a fresh global frame."
  [program]
  (core/evaluate (c/make-env) program))

;; ---------------------------------------------------------------------
;; Frames

(deftest lookups-walk-the-chain
  (let [outer (c/push-frame (c/make-env) {:x 1 :y 2})
        inner (c/push-frame outer {:x 10})]
    (is (= 10 (core/lookup inner :x)))
    (is (= 2 (core/lookup inner :y)))
    (is (= 1 (core/lookup outer :x)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown variable :z"
                          (core/lookup inner :z)))))

(deftest assignment-finds-the-owning-frame
  (let [outer (c/push-frame (c/make-env) {:x 1})
        inner (c/push-frame outer {})]
    (core/assign! inner :x 5)
    (core/assign! inner :local 7)
    (testing "x changed where it lived; local was created in the innermost frame"
      (is (= [{:local 7} {:x 5} {}] (c/snapshot inner))))))

;; ---------------------------------------------------------------------
;; Defining and calling

(deftest defining-and-calling
  (is (= 7 (run [:seq
                 [:def :add3 [:n] [:add [:get :n] 3]]
                 [:call :add3 4]])))
  (is (= "#closure[:n]" (pr-str (run [:def :f [:n] 1])))))

(deftest argument-count-is-checked
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected 1 arguments, got 2"
                        (run [:seq [:def :f [:n] 1] [:call :f 1 2]]))))

(deftest only-functions-can-be-called
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a function: 5"
                        (run [:seq [:set :f 5] [:call :f]]))))

(deftest locals-vanish-after-the-call
  (let [env (c/make-env)]
    (core/evaluate env [:seq [:def :f [] [:set :tmp 99]] [:call :f]])
    (is (thrown? clojure.lang.ExceptionInfo (core/lookup env :tmp)))))

;; ---------------------------------------------------------------------
;; Recursion

(def factorial
  [:seq
   [:def :fact [:n]
    [:if [:lt [:get :n] 2]
     1
     [:mul [:get :n] [:call :fact [:sub [:get :n] 1]]]]]
   [:call :fact 10]])

(deftest recursion
  (is (= 3628800 (run factorial))))

;; ---------------------------------------------------------------------
;; Scope

(def scope-program
  [:seq
   [:set :x :global]
   [:def :show-x [] [:get :x]]
   [:def :caller [:x] [:call :show-x]]
   [:call :caller :callers]])

(deftest lexical-scope
  (testing "show-x sees the x where it was defined, not the caller's x"
    (is (= :global (run scope-program)))))

(deftest dynamic-scope-by-hand
  (let [env (c/make-env)]
    (core/evaluate env [:seq [:set :x :global] [:def :show-x [] [:get :x]]])
    (let [f (core/lookup env :show-x)
          caller-frame (c/push-frame env {:x :callers})]
      (testing "the parent frame passed to invoke decides which x is visible"
        (is (= :global (c/invoke f [] (:env f))))
        (is (= :callers (c/invoke f [] caller-frame)))))))

(deftest parameters-shadow-outer-variables
  (let [env (c/make-env)]
    (core/evaluate env [:seq
                        [:set :x 1]
                        [:def :f [:x] [:seq [:set :x [:add [:get :x] 10]]
                                            [:get :x]]]])
    (is (= 15 (core/evaluate env [:call :f 5])))
    (testing "the global x was shadowed, not changed"
      (is (= 1 (core/lookup env :x))))))

(deftest assigning-without-a-parameter-reaches-outward
  (let [env (c/make-env)]
    (core/evaluate env [:seq
                        [:set :x 1]
                        [:def :bump [] [:set :x [:add [:get :x] 1]]]])
    (core/evaluate env [:call :bump])
    (is (= 2 (core/lookup env :x)))))

;; ---------------------------------------------------------------------
;; Closures

(def make-counter
  [:def :make-counter []
   [:seq
    [:set :count 0]
    [:def :next [] [:seq [:set :count [:add [:get :count] 1]]
                         [:get :count]]]
    [:get :next]]])

(deftest a-counter-remembers-its-own-state
  (let [env (c/make-env)]
    (core/evaluate env make-counter)
    (core/evaluate env [:set :a [:call :make-counter]])
    (core/evaluate env [:set :b [:call :make-counter]])
    (is (= [1 2 3] [(core/evaluate env [:call :a])
                    (core/evaluate env [:call :a])
                    (core/evaluate env [:call :a])]))
    (testing "each counter has its own frame"
      (is (= 1 (core/evaluate env [:call :b]))))
    (testing "count never reached the global frame"
      (is (thrown? clojure.lang.ExceptionInfo (core/lookup env :count))))))

(deftest functions-are-values
  (is (= 16 (run [:seq
                  [:def :twice [:f :v] [:call :f [:call :f [:get :v]]]]
                  [:def :double [:n] [:mul 2 [:get :n]]]
                  [:call :twice [:get :double] 4]]))))

;; ---------------------------------------------------------------------
;; Files

(deftest running-a-file
  (is (= "5! = 120\n10! = 3628800\n"
         (with-out-str
           (main/run-file (io/resource "closures/fact.edn") (c/make-env))))))

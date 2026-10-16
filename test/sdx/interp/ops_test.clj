(ns sdx.interp.ops-test
  (:require [clojure.test :refer [deftest is]]
            [sdx.interp.core :as core]
            [sdx.interp.ops]))

(defn- run
  "Evaluate `program` in a fresh environment."
  [program]
  (core/evaluate (core/make-env) program))

(deftest arithmetic-and-comparison
  (is (= 7 (run [:add 1 [:mul 2 3]])))
  (is (= 3 (run [:div 7 2])))
  (is (= 1 (run [:mod 7 2])))
  (is (true? (run [:lt 1 2])))
  (is (false? (run [:eq 1 2])))
  (is (true? (run [:not false]))))

(deftest if-with-a-real-condition
  (is (= :small (run [:if [:lt 3 10] :small :big])))
  (is (= 0 (run [:seq [:set :x 0]
                 [:if [:eq [:get :x] 0] 0 [:div 10 [:get :x]]]]))))

(def sum-to-ten
  [:seq
   [:set :i 1]
   [:set :total 0]
   [:while [:not [:gt [:get :i] 10]]
    [:seq
     [:set :total [:add [:get :total] [:get :i]]]
     [:set :i [:add [:get :i] 1]]]]
   [:get :total]])

(deftest looping
  (is (= 55 (run sum-to-ten))))

(def factorial-of-five
  [:seq
   [:set :n 5]
   [:set :acc 1]
   [:while [:gt [:get :n] 1]
    [:seq
     [:set :acc [:mul [:get :acc] [:get :n]]]
     [:set :n [:sub [:get :n] 1]]]]
   [:get :acc]])

(deftest factorial
  (is (= 120 (run factorial-of-five))))

(deftest countdown-prints
  (is (= "3\n2\n1\n"
         (with-out-str
           (run [:seq
                 [:set :n 3]
                 [:while [:gt [:get :n] 0]
                  [:seq [:print [:get :n]]
                        [:set :n [:sub [:get :n] 1]]]]])))))

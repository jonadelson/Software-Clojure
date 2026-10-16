(ns sdx.interp.array-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.interp.array]
            [sdx.interp.core :as core]
            [sdx.interp.ops]))

(defn- run
  "Evaluate `program` in a fresh environment."
  [program]
  (core/evaluate (core/make-env) program))

(deftest making-arrays
  (is (= [nil nil nil] (run [:array 3])))
  (is (= 3 (run [:array-len [:array 3]]))))

(deftest getting-and-setting
  (let [env (core/make-env)]
    (core/evaluate env [:set :a [:array 3]])
    (is (= 7 (core/evaluate env [:array-set :a 1 7])))
    (is (= 7 (core/evaluate env [:array-get [:get :a] 1])))
    (is (= [nil 7 nil] (core/lookup env :a)))))

(deftest bounds-are-checked
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"index 3 out of range"
                        (run [:array-get [:array 3] 3])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"index -1 out of range"
                        (run [:seq [:set :a [:array 3]] [:array-set :a -1 0]]))))

(def squares
  [:seq
   [:set :sq [:array 5]]
   [:set :i 0]
   [:while [:lt [:get :i] [:array-len [:get :sq]]]
    [:seq
     [:array-set :sq [:get :i] [:mul [:get :i] [:get :i]]]
     [:set :i [:add [:get :i] 1]]]]
   [:get :sq]])

(deftest filling-an-array
  (is (= [0 1 4 9 16] (run squares))))

(deftest old-values-are-untouched
  (testing "setting an element rebinds the variable; the old vector is unchanged"
    (let [env (core/make-env)
          before (core/evaluate env [:set :a [:array 2]])]
      (core/evaluate env [:array-set :a 0 :changed])
      (is (= [nil nil] before))
      (is (= [:changed nil] (core/lookup env :a))))))

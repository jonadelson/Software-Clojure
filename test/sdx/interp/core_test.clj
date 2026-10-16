(ns sdx.interp.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.interp.core :as core]))

(deftest the-environment
  (let [env (core/make-env)]
    (is (= 5 (core/assign! env :x 5)))
    (is (= 5 (core/lookup env :x)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown variable :y"
                          (core/lookup env :y)))))

(deftest literals
  (is (= 5 (core/evaluate (core/make-env) 5)))
  (is (= "hi" (core/evaluate (core/make-env) "hi")))
  (is (nil? (core/evaluate (core/make-env) nil))))

(deftest set-and-get
  (let [env (core/make-env)]
    (is (= 5 (core/evaluate env [:set :x 5])))
    (is (= 5 (core/evaluate env [:get :x])))
    (is (= {:x 5} @(:vars env)))))

(deftest seq-returns-its-last-value
  (is (= 3 (core/evaluate (core/make-env) [:seq [:set :a 1] [:set :b 2] 3])))
  (is (nil? (core/evaluate (core/make-env) [:seq]))))

(deftest if-evaluates-one-branch
  (let [env (core/make-env)]
    (is (= :yes (core/evaluate env [:if true :yes [:explode]])))
    (is (= :no (core/evaluate env [:if false [:explode] :no])))
    (testing "nil and false are false; everything else is true"
      (is (= :no (core/evaluate env [:if nil :yes :no])))
      (is (= :yes (core/evaluate env [:if 0 :yes :no]))))))

(deftest while-with-only-core-operations
  (let [env (core/make-env)]
    (core/evaluate env [:seq
                        [:set :go true]
                        [:while [:get :go]
                         [:seq [:set :ran true] [:set :go false]]]])
    (is (= {:go false :ran true} @(:vars env)))))

(deftest print-writes-to-out
  (is (= "x is 5\n"
         (with-out-str
           (core/evaluate (core/make-env)
                          [:seq [:set :x 5] [:print "x is" [:get :x]]])))))

(deftest unknown-operation
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown operation :explode"
                        (core/evaluate (core/make-env) [:explode]))))

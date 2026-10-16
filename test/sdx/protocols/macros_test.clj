(ns sdx.protocols.macros-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [sdx.protocols.macros :as m]
            [sdx.protocols.mock :as mock]))

(deftest timed-returns-the-value-and-the-time
  (let [{:keys [value millis]} (m/timed (Thread/sleep 5) (+ 1 2))]
    (is (= 3 value))
    (is (>= millis 5.0))))

(deftest with-timer-prints-and-returns
  (let [{:keys [value output]} (m/with-captured-output
                                 (m/with-timer "adding" (+ 1 2)))]
    (is (= 3 value))
    (is (re-matches #"adding: \d+\.\d ms\n" output))))

(deftest temp-files-are-cleaned-up
  (let [kept (atom nil)]
    (m/with-temp-file [f]
      (reset! kept f)
      (spit f "hello")
      (is (= "hello" (slurp f))))
    (is (not (.exists ^java.io.File @kept)))))

(deftest temp-files-are-cleaned-up-after-an-exception
  (let [kept (atom nil)]
    (is (thrown? ArithmeticException
                 (m/with-temp-file [f]
                   (reset! kept f)
                   (/ 1 0))))
    (is (not (.exists ^java.io.File @kept)))))

(deftest temp-dirs-are-removed-with-their-contents
  (let [kept (atom nil)]
    (m/with-temp-dir [dir]
      (reset! kept dir)
      (let [nested (io/file dir "a" "b.txt")]
        (io/make-parents nested)
        (spit nested "x")
        (is (.exists nested))))
    (is (not (.exists ^java.io.File @kept)))))

(deftest captured-output-keeps-both-halves
  (let [{:keys [value output]} (m/with-captured-output
                                 (println "working...")
                                 :done)]
    (is (= :done value))
    (is (= "working...\n" output)))
  (testing "nothing printed means an empty string, not nil"
    (is (= "" (:output (m/with-captured-output 42))))))

(deftest with-replaced-looks-like-with-redefs
  (is (= 40 (m/with-replaced [mock/exchange-rate (constantly 4)]
              (mock/convert 10 "USD" "EUR"))))
  (is (thrown? clojure.lang.ExceptionInfo (mock/convert 10 "USD" "EUR"))))

(deftest macros-expand-to-ordinary-code
  (let [expansion (macroexpand-1 '(sdx.protocols.macros/with-temp-file [f] (slurp f)))]
    (is (= 'sdx.protocols.macros/call-with-temp-file (first expansion)))
    (is (= '(clojure.core/fn [f] (slurp f)) (second expansion))))
  (let [expansion (macroexpand-1 '(sdx.protocols.macros/timed (+ 1 2)))]
    (is (= 'clojure.core/let (first expansion)))))

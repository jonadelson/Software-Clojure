(ns sdx.persist.basic-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.persist.basic :as p])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest scalars-become-one-line
  (is (= ["nil"] (p/save nil)))
  (is (= ["bool:true"] (p/save true)))
  (is (= ["int:42"] (p/save 42)))
  (is (= ["int:-7"] (p/save -7)))
  (is (= ["float:2.5"] (p/save 2.5)))
  (is (= ["kw:name"] (p/save :name)))
  (is (= ["kw:sdx/name"] (p/save :sdx/name))))

(deftest strings-are-counted-in-lines
  (is (= ["str:1" "hello"] (p/save "hello")))
  (is (= ["str:1" ""] (p/save "")))
  (is (= ["str:3" "one" "two" "three"] (p/save "one\ntwo\nthree")))
  (testing "a trailing newline is an extra, empty line"
    (is (= ["str:2" "one" ""] (p/save "one\n"))))
  (testing "a line that looks like a header is not mistaken for one"
    (is (= "int:5" (p/from-lines (p/save "int:5"))))))

(deftest collections-are-headers-followed-by-elements
  (is (= ["vec:3" "int:1" "int:2" "int:3"] (p/save [1 2 3])))
  (is (= ["vec:0"] (p/save [])))
  (is (= ["map:1" "kw:a" "int:1"] (p/save {:a 1})))
  (is (= ["set:1" "str:1" "x"] (p/save #{"x"})))
  (is (= ["vec:2" "vec:1" "int:1" "map:1" "kw:k" "nil"] (p/save [[1] {:k nil}]))))

(def samples
  "Values that must survive a round trip unchanged."
  [nil true false 0 42 -17 3.5 -0.25 1.0e10
   "" "hi" "two\nlines" "ends with newline\n" "   padded   "
   :k :ns/k
   [] [1 2 3] [[[]]]
   {} {:a 1 :b "two" :c [3]} {"string key" 1 7 :seven}
   #{} #{1 2 3} #{[1 2] {:a 1}}
   {:name "sample" :tags #{:a :b} :scores [1.5 2.5] :nested {:deep {:deeper nil}}}])

(deftest everything-round-trips
  (doseq [v samples]
    (testing (pr-str v)
      (is (= v (p/from-lines (p/save v))))
      (is (= v (p/from-string (p/to-string v)))))))

(deftest a-big-map-round-trips
  (let [m (into {} (map (fn [i] [(keyword (str "k" i)) i])) (range 100))]
    (is (= m (p/from-string (p/to-string m))))))

(deftest load-returns-what-is-left
  (is (= [[1 2] ["int:3"]] (p/load ["vec:2" "int:1" "int:2" "int:3"]))))

(deftest bad-input-is-reported
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown tag"
                        (p/from-lines ["blob:1"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ran out of lines"
                        (p/from-lines ["vec:2" "int:1"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"shorter than its header"
                        (p/from-lines ["str:2" "only one"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"extra lines"
                        (p/from-lines ["int:1" "int:2"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"don't know how to save"
                        (p/save (fn [x] x)))))

(deftest files-round-trip
  (let [dir (Files/createTempDirectory "persist" (make-array FileAttribute 0))
        path (str (.resolve dir "value.txt"))
        value {:title "A file" :body "line one\nline two\n" :count 2}]
    (try
      (p/save-to path value)
      (is (= (p/to-string value) (slurp path)))
      (is (= value (p/load-from path)))
      (finally
        (Files/deleteIfExists (.resolve dir "value.txt"))
        (Files/deleteIfExists dir)))))

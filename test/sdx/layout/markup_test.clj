(ns sdx.layout.markup-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sdx.layout.main :as main]
            [sdx.layout.markup :as m]
            [sdx.layout.size :refer [leaf row col]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest markup-becomes-blocks
  (is (= (row (leaf 2 1 \a) (leaf 1 2 \b))
         (m/parse-layout "<row><leaf width=\"2\" height=\"1\" fill=\"a\"/><leaf width=\"1\" height=\"2\" fill=\"b\"/></row>")))
  (testing "fill defaults to x and whitespace between elements is ignored"
    (is (= (col (leaf 3 1) (row))
           (m/parse-layout "<col>\n  <leaf width=\"3\" height=\"1\"></leaf>\n  <row></row>\n</col>\n")))))

(deftest markup-errors
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"<leaf> needs a whole-number height"
                        (m/parse-layout "<leaf width=\"2\"/>")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"<leaf> needs a whole-number width"
                        (m/parse-layout "<leaf width=\"two\" height=\"1\"/>")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown block <box>"
                        (m/parse-layout "<row><box/></row>")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected one top-level block, found 2"
                        (m/parse-layout "<row></row><row></row>"))))

(deftest rendering-a-file
  (let [dir (.toFile (Files/createTempDirectory "sdx-layout" (make-array FileAttribute 0)))
        file (io/file dir "page.html")]
    (try
      (spit file "<row><leaf width=\"2\" height=\"1\" fill=\"a\"/><leaf width=\"2\" height=\"1\" fill=\"b\"/><leaf width=\"2\" height=\"1\" fill=\"c\"/></row>")
      (is (= ["aabbcc"] (main/render-file (.getPath file) 6)))
      (is (= ["aabb" "cc.."] (main/render-file (.getPath file) 4)))
      (finally
        (io/delete-file file true)
        (io/delete-file dir true)))))

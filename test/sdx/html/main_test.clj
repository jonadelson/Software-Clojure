(ns sdx.html.main-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [sdx.html.main :as m])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-file
  "Write `text` to a file in a fresh temp directory, call (f filename),
   and delete everything afterwards."
  [text f]
  (let [dir (Files/createTempDirectory "sdx-html" (make-array FileAttribute 0))
        file (io/file (.toFile dir) "page.html")]
    (try
      (spit file text)
      (f (.getPath file))
      (finally
        (io/delete-file file true)
        (io/delete-file (.toFile dir) true)))))

(deftest report-prints-one-line-per-problem
  (with-temp-file "<div><li>x</li><blink>y</blink></div>"
    (fn [filename]
      (let [out (java.io.StringWriter.)
            n (binding [*out* out] (m/report filename))]
        (is (= 2 n))
        (is (= [(str filename ":div/li: <li> must be inside <ol> or <ul>, not <div>")
                (str filename ":div/blink: unknown tag <blink>")]
               (str/split-lines (str out))))))))

(deftest report-counts-a-parse-error-as-one-problem
  (with-temp-file "<p>never closed"
    (fn [filename]
      (let [out (java.io.StringWriter.)
            n (binding [*out* out] (m/report filename))]
        (is (= 1 n))
        (is (str/includes? (str out) "parse error at 0: <p> is never closed"))))))

(deftest report-is-quiet-for-a-clean-file
  (with-temp-file "<p>fine</p>"
    (fn [filename]
      (let [out (java.io.StringWriter.)]
        (is (= 0 (binding [*out* out] (m/report filename))))
        (is (= "" (str out)))))))

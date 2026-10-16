(ns sdx.template.main-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [sdx.template.main :as m])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest expanding-files
  (let [dir (.toFile (Files/createTempDirectory "sdx-template" (make-array FileAttribute 0)))
        template (io/file dir "t.html")
        vars (io/file dir "v.edn")]
    (try
      (spit template "<h1 z-var=\"title\">?</h1><p z-loop=\"n:ns\" z-num=\"n\">?</p>")
      (spit vars "{:title \"Totals\" :ns [1000 2000.5]}")
      (is (= "<h1>Totals</h1><p>1,000</p><p>2,000.50</p>"
             (m/expand-files (.getPath template) (.getPath vars))))
      (finally
        (io/delete-file template true)
        (io/delete-file vars true)
        (io/delete-file dir true)))))

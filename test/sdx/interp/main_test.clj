(ns sdx.interp.main-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [sdx.interp.main :as main])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest running-the-sample-programs
  (is (= "n is 3\nn is 2\nn is 1\nliftoff\n"
         (with-out-str (main/run-file (io/resource "interp/countdown.edn")))))
  (is (= "[0 1 4 9 16]\n"
         (with-out-str (main/run-file (io/resource "interp/squares.edn"))))))

(deftest running-a-program-from-a-temporary-file
  (let [dir (Files/createTempDirectory "interp" (make-array FileAttribute 0))
        file (io/file (.toFile dir) "prog.edn")]
    (try
      (spit file (pr-str [:seq [:set :x 20] [:add [:get :x] 1]]))
      (is (= 21 (main/run-file (.getPath file))))
      (finally
        (io/delete-file file)
        (Files/delete dir)))))

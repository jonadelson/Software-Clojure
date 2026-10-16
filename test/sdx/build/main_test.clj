(ns sdx.build.main-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sdx.build.main :as main])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn with-temp-dir [f]
  (let [dir (.toFile (Files/createTempDirectory "sdx-build" (make-array FileAttribute 0)))]
    (try
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (io/delete-file file true))))))

(def config
  '{:vars {"SHOUT" "tr a-z A-Z"}
    :patterns {"%.txt" {:depends ["%.md"] :rule "${SHOUT} < $< > $@"}}
    :targets {"book.txt" {:depends ["intro.txt" "body.txt"] :rule "cat $^ > $@"}}})

(defn write-project! [dir]
  (spit (io/file dir "build.edn") (pr-str config))
  (spit (io/file dir "intro.md") "hello\n")
  (spit (io/file dir "body.md") "world\n"))

(deftest disk-stamps-only-mention-files-that-exist
  (with-temp-dir
    (fn [dir]
      (write-project! dir)
      (let [stamps (main/disk-stamps dir ["intro.md" "body.md" "intro.txt"])]
        (is (= #{"intro.md" "body.md"} (set (keys stamps))))
        (is (every? pos? (vals stamps)))))))

(deftest dry-run-prints-the-commands
  (with-temp-dir
    (fn [dir]
      (write-project! dir)
      (let [out (with-out-str (main/-main (str (io/file dir "build.edn")) "book.txt"))]
        (is (= ["tr a-z A-Z < body.md > body.txt"
                "tr a-z A-Z < intro.md > intro.txt"
                "cat intro.txt body.txt > book.txt"]
               (str/split-lines out)))
        (testing "nothing was created"
          (is (not (.exists (io/file dir "book.txt")))))))))

(deftest running-makes-the-files
  (with-temp-dir
    (fn [dir]
      (write-project! dir)
      (let [path (str (io/file dir "build.edn"))]
        (with-out-str (main/-main "--run" path "book.txt"))
        (is (= "HELLO\nWORLD\n" (slurp (io/file dir "book.txt"))))
        (testing "a second run has nothing to do"
          (is (= "nothing to do\n" (with-out-str (main/-main "--run" path "book.txt")))))
        (testing "a failing command stops the build"
          (spit (io/file dir "build.edn")
                (pr-str (assoc-in config [:targets "bad"] {:rule "exit 3"})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"status 3"
                                (with-out-str (main/-main "--run" path "bad")))))))))

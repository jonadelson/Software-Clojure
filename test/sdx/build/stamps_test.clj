(ns sdx.build.stamps-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.build.stamps :as stamps]))

(def config
  {"app"    {:depends ["main.o" "util.o"] :rule "link app"}
   "main.o" {:depends ["main.c" "defs.h"] :rule "compile main"}
   "util.o" {:depends ["util.c" "defs.h"] :rule "compile util"}
   "main.c" {}
   "util.c" {}
   "defs.h" {}})

(def sources {"main.c" 1 "util.c" 1 "defs.h" 1})

(deftest everything-is-built-from-scratch
  (let [{:keys [stamps rebuilt]} (stamps/build config sources 10 ["app"])]
    (is (= ["main.o" "util.o" "app"] rebuilt))
    (is (= (merge sources {"main.o" 10 "util.o" 10 "app" 10}) stamps))
    (is (= ["compile main" "compile util" "link app"]
           (stamps/commands config {:rebuilt rebuilt})))))

(deftest nothing-to-do-when-up-to-date
  (let [built (:stamps (stamps/build config sources 10 ["app"]))
        again (stamps/build config built 20 ["app"])]
    (is (= [] (:rebuilt again)))
    (is (= built (:stamps again)))))

(deftest touching-a-source-rebuilds-what-depends-on-it
  (let [built (:stamps (stamps/build config sources 10 ["app"]))]
    (testing "one changed .c file rebuilds its object and the app"
      (is (= ["main.o" "app"]
             (:rebuilt (stamps/build config (assoc built "main.c" 15) 20 ["app"])))))
    (testing "a changed header rebuilds everything"
      (is (= ["main.o" "util.o" "app"]
             (:rebuilt (stamps/build config (assoc built "defs.h" 15) 20 ["app"])))))
    (testing "building only an object ignores the app"
      (is (= ["util.o"]
             (:rebuilt (stamps/build config (assoc built "util.c" 15) 20 ["util.o"])))))))

(deftest rebuilt-dependencies-propagate-in-the-same-run
  (testing "app is rebuilt because main.o was, even though both are stamped `now`"
    (let [built (:stamps (stamps/build config sources 10 ["app"]))
          result (stamps/build config (dissoc built "main.o") 20 ["app"])]
      (is (= ["main.o" "app"] (:rebuilt result)))
      (is (= 20 (get-in result [:stamps "app"]))))))

(deftest stale-is-a-pure-question
  (is (stamps/stale? config {} #{} "main.o"))
  (is (not (stamps/stale? config {"main.o" 5 "main.c" 1 "defs.h" 1} #{} "main.o")))
  (is (stamps/stale? config {"main.o" 5 "main.c" 9 "defs.h" 1} #{} "main.o"))
  (is (stamps/stale? config {"main.o" 5 "main.c" 1 "defs.h" 1} #{"defs.h"} "main.o")))

(deftest a-missing-source-cannot-be-made
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no rule to make util.c"
                        (stamps/build config (dissoc sources "util.c") 10 ["app"]))))

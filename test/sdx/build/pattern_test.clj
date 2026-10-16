(ns sdx.build.pattern-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.build.pattern :as pattern]
            [sdx.build.stamps :as stamps]))

(def config
  {:vars {"CC" "cc" "CFLAGS" "-O2"}
   :patterns {"%.o" {:depends ["%.c" "defs.h"] :rule "${CC} ${CFLAGS} -c $< -o $@"}}
   :targets {"app" {:depends ["main.o" "util.o"] :rule "${CC} $^ -o $@"}}})

(deftest matching-patterns
  (is (= "main" (pattern/match-pattern "%.o" "main.o")))
  (is (= "src/main" (pattern/match-pattern "%.o" "src/main.o")))
  (is (= "x" (pattern/match-pattern "lib%.a" "libx.a")))
  (is (nil? (pattern/match-pattern "%.o" "main.c")))
  (testing "the stem must be non-empty"
    (is (nil? (pattern/match-pattern "%.o" ".o"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one %"
                        (pattern/match-pattern "%.%" "a.b"))))

(deftest instantiating-a-pattern
  (is (= {:depends ["main.c" "defs.h"] :rule "r"}
         (pattern/instantiate {:depends ["%.c" "defs.h"] :rule "r"} "main"))))

(deftest substituting-variables
  (is (= "cc -c main.c -o main.o"
         (pattern/substitute "${CC} -c $< -o $@" "main.o" ["main.c" "defs.h"] {"CC" "cc"})))
  (is (= "link main.o util.o"
         (pattern/substitute "link $^" "app" ["main.o" "util.o"] {})))
  (is (= "touch out" (pattern/substitute "touch $@$<" "out" [] {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown variable LD"
                        (pattern/substitute "${LD}" "app" [] {}))))

(deftest expanding-a-configuration
  (let [plain (pattern/expand config ["app"])]
    (is (= {"app"    {:depends ["main.o" "util.o"] :rule "cc main.o util.o -o app"}
            "main.o" {:depends ["main.c" "defs.h"] :rule "cc -O2 -c main.c -o main.o"}
            "util.o" {:depends ["util.c" "defs.h"] :rule "cc -O2 -c util.c -o util.o"}
            "main.c" {}
            "util.c" {}
            "defs.h" {}}
           plain))
    (testing "an explicit target wins over a pattern"
      (let [plain (pattern/expand (assoc-in config [:targets "util.o"] {:rule "special"}) ["app"])]
        (is (= {:rule "special"} (get plain "util.o")))))
    (testing "the expanded configuration is what stage 2 builds"
      (is (= ["cc -O2 -c main.c -o main.o" "cc -O2 -c util.c -o util.o" "cc main.o util.o -o app"]
             (stamps/commands plain (stamps/build plain {"main.c" 1 "util.c" 1 "defs.h" 1} 5 ["app"])))))))

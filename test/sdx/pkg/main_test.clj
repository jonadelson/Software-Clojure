(ns sdx.pkg.main-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [sdx.pkg.main :as main]))

(deftest formatting-a-solution
  (is (= "app 1.1, db 2.0, web 3.0"
         (main/format-solution {"web" "3.0" "app" "1.1" "db" "2.0"}))))

(deftest running-on-the-example-manifest
  (let [lines (str/split-lines (with-out-str (main/-main "resources/pkg/manifest.edn")))]
    (is (= "app 1.1, db 2.0, web 3.0" (first lines)))
    (is (= 9 (count lines)))
    (is (str/starts-with? (last lines) "8 solutions, 22 versions tried"))))

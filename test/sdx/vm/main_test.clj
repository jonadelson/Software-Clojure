(ns sdx.vm.main-test
  (:require [clojure.test :refer [deftest is]]
            [sdx.vm.main :as main]))

(deftest main-prints-the-program-output
  (is (= "5\n4\n3\n2\n1\n"
         (with-out-str (main/-main "resources/vm/count_down.as")))))

(deftest run-file-returns-the-final-state
  (is (= ["15"] (:output (main/run-file "resources/vm/sum_array.as")))))

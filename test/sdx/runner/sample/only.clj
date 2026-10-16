(ns sdx.runner.sample.only
  "A namespace where one test is marked ^:only, so the others are skipped.")

(defn test-first
  "Skipped: something else is marked only."
  []
  (assert false "test-first must not run"))

(defn ^:only test-second
  "The one test that runs."
  []
  (assert (= 2 (+ 1 1))))

(defn test-third
  "Skipped."
  []
  (assert false "test-third must not run"))

(defn ^{:only true :skip true} test-fourth
  "Skipped: skip wins over only."
  []
  (assert false "test-fourth must not run"))

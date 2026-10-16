(ns sdx.runner.sample.basic
  "A namespace of tests for the runner to find. Two pass, one fails,
   one errors, and two are not tests at all.")

(defn test-addition
  "Passes: the assertion holds."
  []
  (assert (= 4 (+ 2 2))))

(defn test-subtraction
  "Fails: the assertion is false."
  []
  (assert (= 1 (- 2 2)) "subtraction is broken"))

(defn test-division
  "Errors: dividing by zero throws before any assertion runs."
  []
  (/ 1 0))

(defn test-strings
  "Passes."
  []
  (assert (= "ab" (str "a" "b"))))

(defn helper
  "Not a test: its name does not start with test-."
  []
  (assert false "the runner must not call helper"))

(defn- test-private
  "Not a test either: private vars are not in ns-publics."
  []
  (assert false "the runner must not call test-private"))

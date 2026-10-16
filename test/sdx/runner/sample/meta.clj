(ns sdx.runner.sample.meta
  "Tests that use metadata: a setup hook, a teardown hook, and a test
   that is skipped. Everything that runs appends to `log` so that a test
   of the runner can check what happened and in what order.")

(def log
  "What has run so far. An atom because the hooks and the tests are
   called one after another and have to share it."
  (atom []))

(defn ^:setup before-each
  "Runs before every test."
  []
  (swap! log conj :setup))

(defn ^:teardown after-each
  "Runs after every test, even one that failed."
  []
  (swap! log conj :teardown))

(defn test-one
  "Passes."
  []
  (swap! log conj :one))

(defn test-two
  "Fails, so that we can see teardown still runs."
  []
  (swap! log conj :two)
  (assert false "two fails"))

(defn ^:skip test-slow
  "Skipped: would take far too long if it ran."
  []
  (swap! log conj :slow)
  (Thread/sleep 100000))

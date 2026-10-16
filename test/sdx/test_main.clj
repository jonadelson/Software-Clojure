(ns sdx.test-main
  "Finds every *_test.clj file under test/, loads it, and runs its tests.
   With arguments, runs only the named namespaces."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- test-files
  "All test files under the given root directory."
  [root]
  (->> (file-seq (io/file root))
       (filter #(str/ends-with? (.getName ^java.io.File %) "_test.clj"))))

(defn- file->ns
  "Turn test/sdx/glob/lit_test.clj into the symbol sdx.glob.lit-test."
  [root ^java.io.File f]
  (-> (.getPath f)
      (subs (inc (count root)))
      (str/replace #"\.clj$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn -main [& args]
  (let [root "test"
        nses (if (seq args)
               (map symbol args)
               (sort (map #(file->ns root %) (test-files root))))]
    (doseq [n nses] (require n))
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (shutdown-agents)
      (System/exit (if (zero? (+ fail error)) 0 1)))))

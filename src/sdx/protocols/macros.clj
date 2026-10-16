(ns sdx.protocols.macros
  "Context managers as macros: do some setup, run the caller's code,
   and clean up afterwards no matter how that code exits.

   Each macro here expands into code the caller could have written by
   hand. The macro's job is to make sure nobody has to."
  (:require [sdx.protocols.mock :as mock])
  (:import [java.io File StringWriter]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------
;; Timing: setup before, bookkeeping after.

(defmacro timed
  "Evaluate `body` and return {:value v :millis ms}, where ms is how
   long the evaluation took."
  [& body]
  `(let [start# (System/nanoTime)
         value# (do ~@body)]
     {:value value#
      :millis (/ (- (System/nanoTime) start#) 1e6)}))

(defmacro with-timer
  "Evaluate `body`, print how long it took under `label`, and return
   the value of `body`."
  [label & body]
  `(let [result# (timed ~@body)]
     (println (format "%s: %.1f ms" ~label (:millis result#)))
     (:value result#)))

;; ---------------------------------------------------------------------
;; Temporary files: the cleanup must run even when the body throws.

(defn call-with-temp-file
  "Create an empty temporary file, call `f` with it, and delete the
   file afterwards whether or not `f` returned normally."
  [f]
  (let [file (File/createTempFile "sdx" ".tmp")]
    (try
      (f file)
      (finally
        (.delete file)))))

(defmacro with-temp-file
  "Bind `name` to a fresh temporary java.io.File while `body` runs.
   The file is deleted when `body` finishes, even if it throws."
  [[name] & body]
  `(call-with-temp-file (fn [~name] ~@body)))

(defn delete-tree!
  "Delete `root`, which may be a file or a directory with contents."
  [^File root]
  (doseq [^File f (reverse (file-seq root))]
    (.delete f)))

(defn call-with-temp-dir
  "Create an empty temporary directory, call `f` with it, and delete
   it and everything inside it afterwards."
  [f]
  (let [dir (.toFile (Files/createTempDirectory "sdx" (make-array FileAttribute 0)))]
    (try
      (f dir)
      (finally
        (delete-tree! dir)))))

(defmacro with-temp-dir
  "Bind `name` to a fresh temporary directory (a java.io.File) while
   `body` runs. The directory and its contents are deleted afterwards."
  [[name] & body]
  `(call-with-temp-dir (fn [~name] ~@body)))

;; ---------------------------------------------------------------------
;; Capturing output: a thread-local rebinding of a dynamic var.

(defmacro with-captured-output
  "Evaluate `body` with everything printed to *out* collected in a
   string. Returns {:value v :output s}."
  [& body]
  `(let [writer# (StringWriter.)
         value# (binding [*out* writer#] ~@body)]
     {:value value#
      :output (str writer#)}))

;; ---------------------------------------------------------------------
;; The function from the mocking section, given the shape of with-redefs.

(defmacro with-replaced
  "Point the var named by symbol `v` at `value` while `body` runs,
   then restore it. A one-var with-redefs built on with-replacement."
  [[v value] & body]
  `(mock/with-replacement (var ~v) ~value (fn [] ~@body)))

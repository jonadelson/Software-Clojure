(ns sdx.persist.extensible
  "Stage 3: anyone can teach the saver and loader about a new type.

   Saving dispatches on the type of the value through a protocol, so a
   record, or a Java class we do not own, can say how it is written.
   Loading dispatches on the tag at the front of each header line
   through a multimethod, because when we are loading, the tag is all we
   have. The built-in types are implemented through the same two
   extension points as everything else, and the aliasing table from
   stage 2 is kept."
  (:refer-clojure :exclude [load])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------
;; The two extension points

(defprotocol Persistable
  "How a value writes itself. An implementation appends lines to the
   state with `emit` and returns the new state, using `save` or
   `save-all` for any parts that are themselves values."
  (save-value [this state] "Append the lines for this value to `state`."))

(defmulti load-value
  "How a header line is read back. Dispatches on the tag before the
   colon; receives the text after the colon (or nil) and the state, and
   must return [value state]."
  (fn [tag _arg _state] tag))

;; ---------------------------------------------------------------------
;; The machinery every type shares

(defn emit
  "Append `lines` to the output in `state`."
  [state & lines]
  (update state :lines into lines))

(defn- seen
  "The position of `value` in `table`, if the very same object is there."
  [table value]
  (first (keep-indexed (fn [i x] (when (identical? x value) i)) table)))

(declare save)

(defn save-all
  "Save each of `values` in turn."
  [state values]
  (reduce save state values))

(defn save
  "Write `value` into `state`: as `ref:N` if it is a collection already
   in the table, otherwise however its type says, adding it to the table
   afterwards if it is a collection. Returns the new state."
  [state value]
  (if-let [id (and (coll? value) (seen (:table state) value))]
    (emit state (str "ref:" id))
    (let [state (save-value value state)]
      (if (coll? value)
        (update state :table conj value)
        state))))

(declare load)

(defn load-n
  "Load `n` values from the front of the lines in `state`.
   Returns [values state]."
  [state n]
  (loop [i 0, values [], state state]
    (if (= i n)
      [values state]
      (let [[value state] (load state)]
        (recur (inc i) (conj values value) state)))))

(defn load
  "Read one value from the front of the lines in `state`. A `ref:N` line
   yields the Nth collection loaded so far; any other tag is handed to
   `load-value`. Returns [value state]."
  [state]
  (let [[line & more] (:lines state)]
    (when (nil? line)
      (throw (ex-info "ran out of lines while loading" {})))
    (let [[tag arg] (str/split line #":" 2)
          state (assoc state :lines more)]
      (if (= tag "ref")
        [(nth (:table state) (parse-long arg)) state]
        (let [[value state] (load-value tag arg state)]
          [value (if (coll? value)
                   (update state :table conj value)
                   state)])))))

;; ---------------------------------------------------------------------
;; The built-in types, as extensions like any other

(extend-protocol Persistable
  nil
  (save-value [_ state] (emit state "nil"))

  Boolean
  (save-value [b state] (emit state (str "bool:" b)))

  Long
  (save-value [n state] (emit state (str "int:" n)))

  Double
  (save-value [d state] (emit state (str "float:" d)))

  clojure.lang.Keyword
  (save-value [k state] (emit state (str "kw:" (subs (str k) 1))))

  String
  (save-value [s state]
    (let [lines (str/split s #"\n" -1)]
      (apply emit state (str "str:" (count lines)) lines)))

  clojure.lang.IPersistentVector
  (save-value [v state] (save-all (emit state (str "vec:" (count v))) v))

  clojure.lang.IPersistentSet
  (save-value [s state] (save-all (emit state (str "set:" (count s))) s))

  clojure.lang.IPersistentMap
  (save-value [m state]
    (save-all (emit state (str "map:" (count m))) (apply concat m))))

(defmethod load-value "nil" [_ _ state] [nil state])
(defmethod load-value "bool" [_ arg state] [(= "true" arg) state])
(defmethod load-value "int" [_ arg state] [(parse-long arg) state])
(defmethod load-value "float" [_ arg state] [(parse-double arg) state])
(defmethod load-value "kw" [_ arg state] [(keyword arg) state])

(defmethod load-value "str" [_ arg state]
  (let [n (parse-long arg)
        text (take n (:lines state))]
    (when (< (count text) n)
      (throw (ex-info "string is shorter than its header says" {:expected n})))
    [(str/join "\n" text) (update state :lines #(drop n %))]))

(defmethod load-value "vec" [_ arg state]
  (load-n state (parse-long arg)))

(defmethod load-value "set" [_ arg state]
  (let [[values state] (load-n state (parse-long arg))]
    [(set values) state]))

(defmethod load-value "map" [_ arg state]
  (let [[values state] (load-n state (* 2 (parse-long arg)))]
    [(apply hash-map values) state]))

(defmethod load-value :default [tag _ _]
  (throw (ex-info (str "unknown tag: " tag) {:tag tag})))

;; ---------------------------------------------------------------------
;; Whole values, strings, and files

(defn to-lines
  "The lines that represent `value`."
  [value]
  (:lines (save {:lines [] :table []} value)))

(defn from-lines
  "The one value that `lines` represents. Anything after it must be blank."
  [lines]
  (let [[value state] (load {:lines lines :table []})]
    (when-not (every? str/blank? (:lines state))
      (throw (ex-info "extra lines after the value" {:extra (take 3 (:lines state))})))
    value))

(defn to-string
  "`value` as text: its lines, each ended by a newline."
  [value]
  (apply str (map #(str % "\n") (to-lines value))))

(defn from-string
  "The value that `text` (as produced by `to-string`) represents."
  [text]
  (from-lines (str/split text #"\n" -1)))

(defn save-to
  "Write `value` to the file at `path`."
  [path value]
  (spit path (to-string value)))

(defn load-from
  "Read back the value saved in the file at `path`."
  [path]
  (with-open [r (io/reader path)]
    (from-lines (line-seq r))))

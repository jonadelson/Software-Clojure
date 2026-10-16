(ns sdx.persist.aliasing
  "Stage 2: a collection that appears more than once is written once.

   The saver keeps a table of every collection it has finished writing.
   When it meets one that is already there (the very same object, not
   merely an equal one) it writes `ref:N`, where N is that collection's
   position in the table. The loader builds the same table in the same
   order, so `ref:N` means the same thing on both sides.

   Because the table has to travel through the whole recursion, the
   state of a save or a load is an explicit map, {:lines ... :table ...},
   that every function takes and returns."
  (:refer-clojure :exclude [load])
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------
;; Saving

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

(defn- save-fresh
  "Write a value the saver has not seen before."
  [state value]
  (cond
    (nil? value) (emit state "nil")
    (boolean? value) (emit state (str "bool:" value))
    (integer? value) (emit state (str "int:" value))
    (float? value) (emit state (str "float:" value))
    (keyword? value) (emit state (str "kw:" (subs (str value) 1)))
    (string? value) (let [lines (str/split value #"\n" -1)]
                      (apply emit state (str "str:" (count lines)) lines))
    (vector? value) (save-all (emit state (str "vec:" (count value))) value)
    (set? value) (save-all (emit state (str "set:" (count value))) value)
    (map? value) (save-all (emit state (str "map:" (count value))) (apply concat value))
    :else (throw (ex-info (str "don't know how to save " (type value))
                          {:value value}))))

(defn save
  "Write `value` into `state`: as `ref:N` if it is a collection already
   in the table, otherwise in full, adding it to the table afterwards if
   it is a collection. Returns the new state."
  [state value]
  (if-let [id (and (coll? value) (seen (:table state) value))]
    (emit state (str "ref:" id))
    (let [state (save-fresh state value)]
      (if (coll? value)
        (update state :table conj value)
        state))))

;; ---------------------------------------------------------------------
;; Loading

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

(defn- take-text
  "The next `n` lines in `state` joined into one string. Returns [string state]."
  [state n]
  (let [text (take n (:lines state))]
    (when (< (count text) n)
      (throw (ex-info "string is shorter than its header says" {:expected n})))
    [(str/join "\n" text) (update state :lines #(drop n %))]))

(defn- load-fresh
  "Read a value that is written out in full. Returns [value state]."
  [tag arg state]
  (case tag
    "nil" [nil state]
    "bool" [(= "true" arg) state]
    "int" [(parse-long arg) state]
    "float" [(parse-double arg) state]
    "kw" [(keyword arg) state]
    "str" (take-text state (parse-long arg))
    "vec" (load-n state (parse-long arg))
    "set" (let [[values state] (load-n state (parse-long arg))]
            [(set values) state])
    "map" (let [[values state] (load-n state (* 2 (parse-long arg)))]
            [(apply hash-map values) state])
    (throw (ex-info (str "unknown tag: " tag) {:tag tag}))))

(defn load
  "Read one value from the front of the lines in `state`. A `ref:N` line
   yields the Nth collection loaded so far. Returns [value state]."
  [state]
  (let [[line & more] (:lines state)]
    (when (nil? line)
      (throw (ex-info "ran out of lines while loading" {})))
    (let [[tag arg] (str/split line #":" 2)
          state (assoc state :lines more)]
      (if (= tag "ref")
        [(nth (:table state) (parse-long arg)) state]
        (let [[value state] (load-fresh tag arg state)]
          [value (if (coll? value)
                   (update state :table conj value)
                   state)])))))

;; ---------------------------------------------------------------------
;; Whole values

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

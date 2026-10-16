(ns sdx.persist.basic
  "Stage 1: save Clojure values as lines of text and load them back.

   Every value becomes a header line of the form `tag:argument`, possibly
   followed by more lines: the text of a string, or the elements of a
   collection. `save` turns a value into a vector of lines and `load`
   turns the front of a sequence of lines back into a value. Neither
   touches a file; two small functions at the end do that."
  (:refer-clojure :exclude [load])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------
;; Saving

(declare save)

(defn- save-all
  "The lines for each of `values`, one after another."
  [values]
  (into [] (mapcat save) values))

(defn save
  "The lines that represent `value`."
  [value]
  (cond
    (nil? value) ["nil"]
    (boolean? value) [(str "bool:" value)]
    (integer? value) [(str "int:" value)]
    (float? value) [(str "float:" value)]
    (keyword? value) [(str "kw:" (subs (str value) 1))]
    (string? value) (let [lines (str/split value #"\n" -1)]
                      (into [(str "str:" (count lines))] lines))
    (vector? value) (into [(str "vec:" (count value))] (save-all value))
    (set? value) (into [(str "set:" (count value))] (save-all value))
    (map? value) (into [(str "map:" (count value))] (save-all (apply concat value)))
    :else (throw (ex-info (str "don't know how to save " (type value))
                          {:value value}))))

;; ---------------------------------------------------------------------
;; Loading

(declare load)

(defn- load-n
  "Load `n` values from the front of `lines`.
   Returns [values remaining-lines]."
  [n lines]
  (loop [i 0, values [], lines lines]
    (if (= i n)
      [values lines]
      (let [[value lines] (load lines)]
        (recur (inc i) (conj values value) lines)))))

(defn- take-text
  "The first `n` of `lines` joined back into one string, and the rest."
  [n lines]
  (let [text (take n lines)]
    (when (< (count text) n)
      (throw (ex-info "string is shorter than its header says" {:expected n})))
    [(str/join "\n" text) (drop n lines)]))

(defn load
  "Read one value from the front of `lines`. Returns [value remaining-lines]."
  [lines]
  (when (empty? lines)
    (throw (ex-info "ran out of lines while loading" {})))
  (let [[line & more] lines
        [tag arg] (str/split line #":" 2)]
    (case tag
      "nil" [nil more]
      "bool" [(= "true" arg) more]
      "int" [(parse-long arg) more]
      "float" [(parse-double arg) more]
      "kw" [(keyword arg) more]
      "str" (take-text (parse-long arg) more)
      "vec" (load-n (parse-long arg) more)
      "set" (let [[values more] (load-n (parse-long arg) more)]
              [(set values) more])
      "map" (let [[values more] (load-n (* 2 (parse-long arg)) more)]
              [(apply hash-map values) more])
      (throw (ex-info (str "unknown tag in line: " line) {:line line})))))

;; ---------------------------------------------------------------------
;; Whole values, strings, and files

(defn from-lines
  "The one value that `lines` represents. Anything after it must be blank."
  [lines]
  (let [[value rest] (load lines)]
    (when-not (every? str/blank? rest)
      (throw (ex-info "extra lines after the value" {:extra (take 3 rest)})))
    value))

(defn to-string
  "`value` as text: its lines, each ended by a newline."
  [value]
  (apply str (map #(str % "\n") (save value))))

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

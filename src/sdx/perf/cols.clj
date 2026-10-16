(ns sdx.perf.cols
  "A column-major dataframe: a map from column name to a vector of values.

   This is the layout statisticians and database engines prefer, because
   most of what they do touches a few columns and every row. Selecting
   columns is a map lookup; it is filtering rows that has to work."
  (:require [sdx.perf.frame :as f]))

(defn row-at
  "Row `i` of a column map, as a map from column name to value."
  [columns i]
  (reduce-kv (fn [row name values] (assoc row name (nth values i)))
             {}
             columns))

(defn- height
  "The number of rows in a column map."
  [columns]
  (if (empty? columns) 0 (count (val (first columns)))))

(defrecord ColFrame [columns]
  f/DataFrame
  (ncol [_] (count columns))
  (nrow [_] (height columns))
  (cols [_] (set (keys columns)))
  (column [_ name] (get columns name))
  (select [_ wanted]
    (->ColFrame (select-keys columns wanted)))
  (filter-rows [_ pred]
    (let [keep (filterv #(pred (row-at columns %)) (range (height columns)))]
      (->ColFrame (reduce-kv (fn [m name values] (assoc m name (mapv values keep)))
                             {}
                             columns)))))

(defn col-frame
  "Build a column-major frame from a map of column name to a sequence of
   values. Every column must have the same length."
  [columns]
  (let [columns (into {} (map (fn [[name values]] [name (vec values)])) columns)
        lengths (set (map count (vals columns)))]
    (when (> (count lengths) 1)
      (throw (ex-info "columns have different lengths"
                      {:lengths (into {} (map (fn [[k v]] [k (count v)])) columns)})))
    (->ColFrame columns)))

(defn from-rows
  "Build a column-major frame from the same input `row-frame` takes:
   a sequence of maps with identical keys."
  ([rows]
   (from-rows (keys (first rows)) rows))
  ([names rows]
   (col-frame (into {} (map (fn [name] [name (mapv #(get % name) rows)])) names))))

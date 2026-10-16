(ns sdx.perf.rows
  "A row-major dataframe: a vector of maps, one map per row.

   This is the layout you get by default when you read a CSV file or a
   query result, and it makes anything that looks at one row at a time
   easy. We also carry the column names separately, because a frame
   with no rows has nowhere else to keep them."
  (:require [sdx.perf.frame :as f]))

(defrecord RowFrame [names rows]
  f/DataFrame
  (ncol [_] (count names))
  (nrow [_] (count rows))
  (cols [_] names)
  (column [_ name] (mapv #(get % name) rows))
  (select [_ wanted]
    (let [wanted (set wanted)]
      (->RowFrame wanted (mapv #(select-keys % wanted) rows))))
  (filter-rows [_ pred]
    (->RowFrame names (filterv pred rows))))

(defn row-frame
  "Build a row-major frame from a sequence of maps, which must all have
   the same keys. Pass `names` as well when there may be no rows."
  ([rows]
   (row-frame (keys (first rows)) rows))
  ([names rows]
   (let [names (set names)
         rows (vec rows)]
     (doseq [r rows]
       (when (not= (set (keys r)) names)
         (throw (ex-info "row does not have the frame's columns"
                         {:expected names :row r}))))
     (->RowFrame names rows))))

(ns sdx.perf.compare
  "Measure selecting columns and filtering rows on both layouts as the
   number of rows grows, and print the medians as a table.

   Run it with:  clojure -M -m sdx.perf.compare 1000 10000 100000"
  (:require [clojure.string :as str]
            [sdx.perf.bench :as b]
            [sdx.perf.cols :as cols]
            [sdx.perf.frame :as f]
            [sdx.perf.rows :as rows]))

(defn make-rows
  "`n` rows with `width` columns named :c0, :c1, ... The value in row i,
   column j is i + j, so every column is different and nothing is random."
  [n width]
  (mapv (fn [i]
          (into {} (map (fn [j] [(keyword (str "c" j)) (+ i j)])) (range width)))
        (range n)))

(def ^:private wanted [:c0 :c1 :c2])

(defn- even-first?
  "The row predicate used by every filter benchmark: keeps half the rows."
  [row]
  (even? (:c0 row)))

(defn measure
  "Median times, in milliseconds, for select and filter on a row-major
   and a column-major frame with `n` rows and ten columns. `opts` are
   passed to `bench`."
  ([n] (measure n {:warmup 5 :warmup-ms 2000 :repeats 11}))
  ([n opts]
   (let [data (make-rows n 10)
         by-row (rows/row-frame data)
         by-col (cols/from-rows data)]
     {:rows n
      :select-rows (:median-ms (b/bench opts (f/select by-row wanted)))
      :select-cols (:median-ms (b/bench opts (f/select by-col wanted)))
      :filter-rows (:median-ms (b/bench opts (f/filter-rows by-row even-first?)))
      :filter-cols (:median-ms (b/bench opts (f/filter-rows by-col even-first?)))})))

(defn table
  "Format a sequence of `measure` results as a Markdown table."
  [results]
  (let [line (fn [cells] (str "| " (str/join " | " cells) " |"))
        cell (fn [ms] (format "%.3f" (double ms)))]
    (str/join
     "\n"
     (into [(line ["rows" "select (rows)" "select (cols)" "filter (rows)" "filter (cols)"])
            (line (repeat 5 "---:"))]
           (map (fn [{:keys [rows select-rows select-cols filter-rows filter-cols]}]
                  (line [rows (cell select-rows) (cell select-cols)
                         (cell filter-rows) (cell filter-cols)])))
           results))))

(defn -main
  "Print the comparison table for the sizes given on the command line,
   or for 1000, 10000 and 100000 rows if none are given."
  [& args]
  (let [sizes (if (seq args) (map parse-long args) [1000 10000 100000])]
    (println (table (map measure sizes)))))

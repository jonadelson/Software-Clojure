(ns sdx.perf.frame
  "The operations a dataframe supports, as a protocol, plus the functions
   that can be written once in terms of that protocol.

   A dataframe is a rectangular table with named columns. The protocol
   says what a frame can do; it says nothing about how the frame is
   stored, which is the whole point: this chapter builds two storage
   layouts and measures them against each other.")

(defprotocol DataFrame
  "A rectangular table: named columns, every column the same length."
  (ncol [df] "Number of columns.")
  (nrow [df] "Number of rows.")
  (cols [df] "The column names, as a set.")
  (column [df name]
    "Every value in the named column, as a vector, top to bottom.")
  (select [df names]
    "A new frame holding only the named columns, in the same row order.")
  (filter-rows [df pred]
    "A new frame holding only the rows for which (pred row) is truthy.
     `row` is a map from column name to value."))

(defn eq?
  "True if `a` and `b` have the same columns holding the same values in
   the same order, regardless of how either frame is stored."
  [a b]
  (and (= (cols a) (cols b))
       (= (nrow a) (nrow b))
       (every? (fn [name] (= (column a name) (column b name))) (cols a))))

(defn row
  "Row `i` of `df` as a map from column name to value. Works for any
   layout, but a layout that stores rows can do better."
  [df i]
  (into {} (map (fn [name] [name (nth (column df name) i)])) (cols df)))

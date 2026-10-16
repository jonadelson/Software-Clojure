(ns sdx.interp.calc
  "Stage 1: a calculator. A program is a number, or a vector whose first
   element names an operation and whose remaining elements are programs.
   Evaluating a vector means evaluating its arguments and applying the
   operation to the results.")

(def ops
  "The operations the calculator knows, each an ordinary Clojure function
   of the evaluated arguments."
  {:add + :sub - :mul * :div quot :max max :min min})

(defn evaluate
  "Evaluate `expr` using the operations in `table`, which defaults to
   `ops`. Numbers evaluate to themselves. A vector is an operation
   applied to the values of its arguments."
  ([expr] (evaluate ops expr))
  ([table expr]
   (if (vector? expr)
     (let [[op & args] expr
           f (get table op)]
       (when-not f
         (throw (ex-info (str "unknown operation " op) {:op op :expr expr})))
       (apply f (map #(evaluate table %) args)))
     expr)))

(ns sdx.interp.ops
  "Arithmetic, comparison, and logic for the interpreter. None of these
   needs to see the environment or to control evaluation: each one
   evaluates all of its arguments and applies an ordinary Clojure
   function to the values, so they are defined from a table."
  (:require [sdx.interp.core :as core]))

(def fn-ops
  "Operations that are plain functions of their evaluated arguments."
  {:add + :sub - :mul * :div quot :mod mod
   :lt < :gt > :eq = :not not})

(doseq [[op f] fn-ops]
  (defmethod core/do-op op [env [_ & args]]
    (apply f (core/evaluate-all env args))))

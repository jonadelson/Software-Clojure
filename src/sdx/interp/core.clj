(ns sdx.interp.core
  "Stage 2: the core of the interpreter. Adds an environment that holds
   variables, and dispatches each operation through a multimethod so
   that new operations can be added from any namespace.

   Every operation receives the environment and the whole, unevaluated
   expression, and decides for itself which arguments to evaluate."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------
;; The environment

(defprotocol Env
  "What the interpreter needs from an environment: look a name up, and
   bind a name to a value."
  (lookup [env name] "The value bound to `name`; throws if it is unbound.")
  (assign! [env name value] "Bind `name` to `value` and return the value."))

(defrecord MapEnv [vars]
  Env
  (lookup [_ name]
    (let [m @vars]
      (if (contains? m name)
        (get m name)
        (throw (ex-info (str "unknown variable " name) {:name name})))))
  (assign! [_ name value]
    (swap! vars assoc name value)
    value))

(defn make-env
  "A fresh environment with no variables in it."
  []
  (->MapEnv (atom {})))

;; ---------------------------------------------------------------------
;; Evaluation

(defmulti do-op
  "Evaluate one operation: a vector whose first element is a keyword
   naming it. Dispatches on that keyword. Methods receive the environment
   and the whole unevaluated vector, and return the operation's value."
  (fn [_env expr] (first expr)))

(defmethod do-op :default [_ expr]
  (throw (ex-info (str "unknown operation " (first expr)) {:expr expr})))

(defn evaluate
  "Evaluate `expr` in `env` and return its value. Vectors are operations;
   anything else is a literal that evaluates to itself."
  [env expr]
  (if (vector? expr)
    (do-op env expr)
    expr))

(defn evaluate-all
  "Evaluate each of `exprs` in order and return their values as a vector."
  [env exprs]
  (mapv #(evaluate env %) exprs))

;; ---------------------------------------------------------------------
;; Operations that control evaluation or touch the environment

(defmethod do-op :seq [env [_ & exprs]]
  (last (evaluate-all env exprs)))

(defmethod do-op :set [env [_ name expr]]
  (assign! env name (evaluate env expr)))

(defmethod do-op :get [env [_ name]]
  (lookup env name))

(defmethod do-op :if [env [_ test then else]]
  (if (evaluate env test)
    (evaluate env then)
    (evaluate env else)))

(defmethod do-op :while [env [_ test body]]
  (while (evaluate env test)
    (evaluate env body)))

(defmethod do-op :print [env [_ & exprs]]
  (println (str/join " " (evaluate-all env exprs))))

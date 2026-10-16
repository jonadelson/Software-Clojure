(ns sdx.closures
  "Functions and closures for the interpreter of the previous chapter.

   Adds two operations:

       [:def name [params ...] body]   define a function
       [:call name args ...]           call one

   and replaces the flat environment with a chain of frames, so that
   every call gets its own variables. A function value remembers the
   frame it was defined in, which is what makes it a closure."
  (:require [sdx.interp.array]
            [sdx.interp.core :as core :refer [do-op evaluate evaluate-all lookup assign!]]
            [sdx.interp.main :as main]
            [sdx.interp.ops]))

;; ---------------------------------------------------------------------
;; Frames

(defn- owner
  "The frame in the chain starting at `frame` that binds `name`, or nil
   if none of them does."
  [frame name]
  (loop [f frame]
    (cond
      (nil? f)                     nil
      (contains? @(:vars f) name)  f
      :else                        (recur (:parent f)))))

(defrecord Frame [vars parent]
  core/Env
  (lookup [this name]
    (if-let [f (owner this name)]
      (get @(:vars f) name)
      (throw (ex-info (str "unknown variable " name) {:name name}))))
  (assign! [this name value]
    (let [f (or (owner this name) this)]
      (swap! (:vars f) assoc name value)
      value)))

(defn make-env
  "A fresh global frame with no variables and no parent."
  []
  (->Frame (atom {}) nil))

(defn push-frame
  "A new frame holding `bindings`, a map from names to values, whose
   parent is `parent`."
  [parent bindings]
  (->Frame (atom bindings) parent))

(defn snapshot
  "The variables visible from `frame` as a vector of maps, innermost
   frame first. Useful for looking at the stack."
  [frame]
  (loop [f frame, acc []]
    (if f
      (recur (:parent f) (conj acc @(:vars f)))
      acc)))

;; ---------------------------------------------------------------------
;; Function values

(defrecord Closure [params body env])

(defmethod print-method Closure [f ^java.io.Writer w]
  (.write w (str "#closure" (pr-str (:params f)))))

(defmethod do-op :def [env [_ name params body]]
  (assign! env name (->Closure params body env)))

;; ---------------------------------------------------------------------
;; Calling

(defn invoke
  "Call the closure `f` with already-evaluated `args`, evaluating its
   body in a new frame whose parent is `parent`. Lexical scope passes the
   closure's own :env as the parent; dynamic scope would pass the
   caller's environment instead."
  [f args parent]
  (when-not (instance? Closure f)
    (throw (ex-info (str "not a function: " (pr-str f)) {:value f})))
  (let [{:keys [params body]} f]
    (when (not= (count params) (count args))
      (throw (ex-info (str "expected " (count params) " arguments, got " (count args))
                      {:params params :args args})))
    (evaluate (push-frame parent (zipmap params args)) body)))

(defmethod do-op :call [env [_ name & args]]
  (let [f (lookup env name)]
    (invoke f (evaluate-all env args) (:env f))))

;; ---------------------------------------------------------------------
;; Running a file

(defn -main
  "Run the EDN program in the named file with functions available:

       clojure -M -m sdx.closures resources/closures/fact.edn"
  [path]
  (main/run-file path (make-env))
  (flush))

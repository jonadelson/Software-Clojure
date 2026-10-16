(ns sdx.interp.array
  "Arrays for the interpreter: fixed-size, indexed from zero, and held
   as Clojure vectors. Because a vector cannot be changed in place,
   setting an element makes a new vector and rebinds the variable that
   held the old one."
  (:require [sdx.interp.core :as core :refer [do-op evaluate lookup assign!]]))

(defn- check-index
  "Throw a helpful error unless `i` is a valid index into `arr`."
  [arr i]
  (when-not (and (integer? i) (< -1 i (count arr)))
    (throw (ex-info (str "index " i " out of range for array of " (count arr))
                    {:index i :size (count arr)}))))

(defmethod do-op :array [env [_ size]]
  (vec (repeat (evaluate env size) nil)))

(defmethod do-op :array-len [env [_ arr]]
  (count (evaluate env arr)))

(defmethod do-op :array-get [env [_ arr index]]
  (let [a (evaluate env arr)
        i (evaluate env index)]
    (check-index a i)
    (nth a i)))

(defmethod do-op :array-set [env [_ name index value]]
  (let [i (evaluate env index)
        v (evaluate env value)
        a (lookup env name)]
    (check-index a i)
    (assign! env name (assoc a i v))
    v))

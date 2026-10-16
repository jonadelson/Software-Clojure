(ns messy)

(defn area
  "Area of a rectangle."
  [width height]
  (* width height))

(defn report [title width height depth colour weight]
  (let [a (area width height)
        unused (* 2 depth)]
    (str title ": " a " " colour)))

(defn area
  "A second definition, which silently replaces the first."
  [r]
  (* Math/PI r r))

(defn deep-thought
  "Nested well past the point of readability."
  [xs]
  (map (fn [x]
         (when (pos? x)
           (let [y (* x 2)]
             (if (even? y)
               (do (println (str y))
                   y)
               y))))
       xs))

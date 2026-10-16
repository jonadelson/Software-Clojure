(ns sdx.objects.simple
  "Objects as plain maps that carry their own behavior.

   Each 'object' is a map whose keys hold either data (:name, :side)
   or functions (:area, :perimeter). Calling a method means looking up
   the function in the map and passing the map to it.")

(defn call
  "Look up `method` in `thing` and call it with `thing` and any extra args."
  [thing method & args]
  (if-let [f (get thing method)]
    (apply f thing args)
    (throw (ex-info (str "no method " method) {:method method :thing thing}))))

;; ---------------------------------------------------------------------
;; Squares

(defn- square-area [thing]
  (* (:side thing) (:side thing)))

(defn- square-perimeter [thing]
  (* 4 (:side thing)))

(defn square
  "Make a square with the given name and side length."
  [name side]
  {:name name
   :side side
   :area square-area
   :perimeter square-perimeter})

;; ---------------------------------------------------------------------
;; Circles

(defn- circle-area [thing]
  (* Math/PI (:radius thing) (:radius thing)))

(defn- circle-perimeter [thing]
  (* 2 Math/PI (:radius thing)))

(defn circle
  "Make a circle with the given name and radius."
  [name radius]
  {:name name
   :radius radius
   :area circle-area
   :perimeter circle-perimeter})

;; ---------------------------------------------------------------------
;; Using them without caring which is which

(defn describe
  "A one-line description of any shape that has :area and :perimeter."
  [thing]
  (format "%s: area %.2f, perimeter %.2f"
          (:name thing)
          (double (call thing :area))
          (double (call thing :perimeter))))

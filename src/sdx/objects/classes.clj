(ns sdx.objects.classes
  "Objects whose behavior lives in a separate 'class' map.

   An object is a map with a :class key pointing at a class. A class is a
   map with a :name, a map of :methods, and an optional :parent class.
   Calling a method walks up the parent chain until it finds one.")

(defn find-method
  "Search `cls` and its ancestors for `method`. Returns nil if not found."
  [cls method]
  (when cls
    (or (get-in cls [:methods method])
        (recur (:parent cls) method))))

(defn call
  "Call `method` on `thing`, passing `thing` as the first argument."
  [thing method & args]
  (if-let [f (find-method (:class thing) method)]
    (apply f thing args)
    (throw (ex-info (str "no method " method " for " (get-in thing [:class :name]))
                    {:method method :class (get-in thing [:class :name])}))))

(defn make
  "Construct an instance of `cls` by calling its :new method with `args`."
  [cls & args]
  (if-let [ctor (find-method cls :new)]
    (apply ctor cls args)
    (throw (ex-info (str "no constructor for " (:name cls)) {:class (:name cls)}))))

;; ---------------------------------------------------------------------
;; The root class: everything a shape can do, whether or not we know how.

(defn- shape-new [cls name]
  {:class cls :name name})

(defn- shape-describe [thing]
  (format "%s: area %.2f, perimeter %.2f"
          (:name thing)
          (double (call thing :area))
          (double (call thing :perimeter))))

(defn- shape-density [thing weight]
  (/ weight (call thing :area)))

(def shape
  "A shape has a name and knows how to describe itself, but has no
   particular geometry. :area and :perimeter must come from a child."
  {:name "Shape"
   :parent nil
   :methods {:new shape-new
             :describe shape-describe
             :density shape-density}})

;; ---------------------------------------------------------------------
;; Children that fill in the geometry.

(defn- square-new [cls name side]
  (assoc (shape-new cls name) :side side))

(def square
  {:name "Square"
   :parent shape
   :methods {:new square-new
             :area (fn [thing] (* (:side thing) (:side thing)))
             :perimeter (fn [thing] (* 4 (:side thing)))}})

(defn- circle-new [cls name radius]
  (assoc (shape-new cls name) :radius radius))

(def circle
  {:name "Circle"
   :parent shape
   :methods {:new circle-new
             :area (fn [thing] (* Math/PI (:radius thing) (:radius thing)))
             :perimeter (fn [thing] (* 2 Math/PI (:radius thing)))}})

;; ---------------------------------------------------------------------
;; Asking an object about its lineage.

(defn instance-of?
  "True if `thing` belongs to `cls` or one of its descendants."
  [thing cls]
  (loop [c (:class thing)]
    (cond
      (nil? c) false
      (= (:name c) (:name cls)) true
      :else (recur (:parent c)))))

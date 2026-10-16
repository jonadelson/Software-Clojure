(ns sdx.objects.builtin
  "The same shapes using the tools Clojure provides: multimethods for
   dispatch on a value, and protocols and records for dispatch on type.")

;; ---------------------------------------------------------------------
;; Multimethods: choose the implementation by looking at the data.

(defmulti area
  "Area of a shape, chosen by the shape's :kind."
  :kind)

(defmulti perimeter
  "Perimeter of a shape, chosen by the shape's :kind."
  :kind)

(defmethod area :square [{:keys [side]}] (* side side))
(defmethod perimeter :square [{:keys [side]}] (* 4 side))

(defmethod area :circle [{:keys [radius]}] (* Math/PI radius radius))
(defmethod perimeter :circle [{:keys [radius]}] (* 2 Math/PI radius))

(defn describe
  "Works for any map whose :kind has area and perimeter methods."
  [shape]
  (format "%s: area %.2f, perimeter %.2f"
          (:name shape) (double (area shape)) (double (perimeter shape))))

;; ---------------------------------------------------------------------
;; Protocols and records: choose the implementation by the type of the value.

(defprotocol Shape
  "Operations every shape supports."
  (shape-area [this] "Area of the shape.")
  (shape-perimeter [this] "Perimeter of the shape."))

(defrecord Square [name side]
  Shape
  (shape-area [_] (* side side))
  (shape-perimeter [_] (* 4 side)))

(defrecord Circle [name radius]
  Shape
  (shape-area [_] (* Math/PI radius radius))
  (shape-perimeter [_] (* 2 Math/PI radius)))

(defn describe-record
  "Describe anything that satisfies the Shape protocol."
  [shape]
  (format "%s: area %.2f, perimeter %.2f"
          (:name shape) (double (shape-area shape)) (double (shape-perimeter shape))))

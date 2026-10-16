(ns sdx.template
  "A template expander.

   A template is HTML in which some elements carry directives, which are
   attributes whose names start with z-:

       z-if=\"name\"         keep the element only if the variable is truthy
       z-loop=\"item:items\" repeat the element once per value in items,
                           with item bound to each value in turn
       z-var=\"name\"        replace the element's content with the variable
       z-num=\"name\"        the same, formatted as a number for people

   Variables live in an environment: a stack of maps from keyword to
   value, searched from the innermost frame outward. Expansion walks the
   tree and returns HTML text."
  (:require [clojure.string :as str]
            [sdx.html.parser :as p]))

;; ---------------------------------------------------------------------
;; Environments: a stack of maps.

(defn lookup
  "Find `name` in `env`, a vector of maps, searching from the last map
   (the innermost scope) back to the first. Throws if it is not bound
   anywhere; a variable bound to nil or false is still bound."
  [env name]
  (if-let [entry (some #(find % name) (rseq env))]
    (val entry)
    (throw (ex-info (str "unknown variable " name) {:name name}))))

;; ---------------------------------------------------------------------
;; Formatting values as text.

(defn escape
  "Make a value safe to put inside HTML text."
  [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- group-digits
  "Insert a comma every three digits from the right: \"1234567\" -> \"1,234,567\"."
  [digits]
  (->> (reverse digits)
       (partition-all 3)
       (map #(apply str (reverse %)))
       reverse
       (str/join ",")))

(defn format-number
  "Render a number for people: thousands separators, and two decimal
   places for anything that is not a whole number."
  [n]
  (let [text (if (integer? n)
               (str n)
               (str (.setScale (bigdec n) 2 java.math.RoundingMode/HALF_UP)))
        [sign text] (if (str/starts-with? text "-") ["-" (subs text 1)] ["" text])
        [whole fraction] (str/split text #"\." 2)]
    (str sign (group-digits whole) (when fraction (str "." fraction)))))

;; ---------------------------------------------------------------------
;; Directives: each one is a method of this multimethod.

(defmulti directive
  "Expand `node`, which carries the directive attribute `attr` with the
   given `value`, in environment `env`. Returns HTML text. Dispatches on
   the attribute name, so a new directive is one new defmethod."
  (fn [attr env node value] attr))

(defmethod directive :default
  [attr _ node _]
  (throw (ex-info (str "unknown directive " attr " on <" (:tag node) ">")
                  {:attr attr :tag (:tag node)})))

(def ^:private structural
  "Directives that decide whether and how often an element appears.
   They run before any others on the same element, in this order."
  ["z-if" "z-loop"])

(defn- next-directive
  "The name of the directive to apply to `node` first, or nil if it has none."
  [node]
  (let [names (filter #(str/starts-with? % "z-") (keys (:attrs node)))]
    (when (seq names)
      (or (some (set names) structural)
          (first (sort names))))))

(defn- without
  "The element without one of its attributes."
  [node attr]
  (update node :attrs dissoc attr))

;; ---------------------------------------------------------------------
;; Expansion: tree -> text.

(declare expand-node)

(defn- expand-children
  "Expand every child of `node` and join the results."
  [env node]
  (apply str (map #(expand-node env %) (:children node))))

(defn expand-node
  "Expand one node of the tree in `env` to HTML text."
  [env node]
  (cond
    (string? node)
    node

    (= :document (:tag node))
    (expand-children env node)

    :else
    (if-let [attr (next-directive node)]
      (directive attr env node (get-in node [:attrs attr]))
      (str "<" (:tag node) (p/attrs->html (:attrs node)) ">"
           (expand-children env node)
           "</" (:tag node) ">"))))

(defn expand
  "Expand `template` (HTML text, or an already parsed tree) using `vars`,
   a map from keyword to value. Returns HTML text."
  [template vars]
  (expand-node [vars] (if (string? template) (p/parse template) template)))

;; ---------------------------------------------------------------------
;; The built-in directives. Each removes its own attribute and expands
;; what is left, so that several directives on one element compose.

(defmethod directive "z-if"
  [_ env node value]
  (if (lookup env (keyword value))
    (expand-node env (without node "z-if"))
    ""))

(defmethod directive "z-loop"
  [_ env node value]
  (let [[item-name coll-name] (str/split value #":" 2)]
    (when-not coll-name
      (throw (ex-info (str "z-loop needs item:items, got " value) {:value value})))
    (let [body (without node "z-loop")]
      (apply str
             (for [item (lookup env (keyword coll-name))]
               (expand-node (conj env {(keyword item-name) item}) body))))))

(defmethod directive "z-var"
  [_ env node value]
  (let [text (escape (lookup env (keyword value)))]
    (expand-node env (-> node (without "z-var") (assoc :children [text])))))

(defmethod directive "z-num"
  [_ env node value]
  (let [text (format-number (lookup env (keyword value)))]
    (expand-node env (-> node (without "z-num") (assoc :children [text])))))

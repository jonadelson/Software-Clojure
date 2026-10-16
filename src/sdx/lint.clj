(ns sdx.lint
  "A linter for Clojure source.

   The source text is read into forms with the same reader the compiler
   uses, so every list knows which line it started on. A check is a
   function from the vector of top-level forms to a seq of findings,
   each {:line n :message s}. The linter is just a vector of checks."
  (:require [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io StringReader]))

;; ---------------------------------------------------------------------
;; Reading

(defn read-forms
  "Read every top-level form in `text` into a vector. Lists carry
   :line and :column metadata saying where they began."
  [text]
  (let [reader (LineNumberingPushbackReader. (StringReader. text))
        eof (Object.)]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof eof :read-cond :allow} reader)]
          (if (identical? form eof)
            forms
            (recur (conj forms form))))))))

(defn line-of
  "The line on which `form` starts, or nil if the reader did not record one."
  [form]
  (:line (meta form)))

;; ---------------------------------------------------------------------
;; Walking

(defn subforms
  "`form` together with every form nested anywhere inside it, outermost first."
  [form]
  (tree-seq coll? seq form))

(defn calls?
  "True if `form` is a list whose first element is one of the symbols in `heads`."
  [heads form]
  (and (seq? form) (contains? heads (first form))))

(defn find-calls
  "Every list anywhere inside `forms` whose head is in `heads`."
  [heads forms]
  (filter #(calls? heads %) (mapcat subforms forms)))

(defn- children
  "The forms directly inside a collection. A map's children are its
   keys and values, not its entries."
  [form]
  (if (map? form)
    (mapcat identity form)
    (seq form)))

;; ---------------------------------------------------------------------
;; Check: functions with too many parameters

(def defn-heads '#{defn defn-})

(defn arities
  "The parameter vectors of a defn form, one per arity. Skips the name,
   the docstring and the attribute map if they are present."
  [form]
  (let [body (drop-while #(or (symbol? %) (string? %) (map? %)) (rest form))
        head (first body)]
    (cond
      (vector? head) [head]
      (seq? head)    (map first (filter seq? body))
      :else          [])))

(defn param-count
  "How many parameters a parameter vector binds, not counting the & marker."
  [params]
  (count (remove #{'&} params)))

(defn too-many-params
  "Findings for every top-level defn with more than `limit` parameters
   in any of its arities."
  [limit forms]
  (for [form (filter #(calls? defn-heads %) forms)
        params (arities form)
        :let [n (param-count params)]
        :when (> n limit)]
    {:line (line-of form)
     :message (format "%s takes %d parameters (limit %d)" (second form) n limit)}))

;; ---------------------------------------------------------------------
;; Check: code nested too deeply

(defn- too-deep-in
  "Findings for collections inside `form` nested more than `limit`
   levels deep. `level` is the nesting level of `form` itself and
   `line` is the nearest known line number above it."
  [limit level line form]
  (when (coll? form)
    (let [line (or (line-of form) line)]
      (if (> level limit)
        [{:line line
          :message (format "nesting is %d levels deep (limit %d)" level limit)}]
        (mapcat #(too-deep-in limit (inc level) line %) (children form))))))

(defn too-deep
  "One finding per top-level form that nests more than `limit` levels
   deep, placed at the first sub-form that crosses the limit. A
   top-level form is level 1."
  [limit forms]
  (for [form forms
        :let [found (first (too-deep-in limit 1 nil form))]
        :when found]
    found))

;; ---------------------------------------------------------------------
;; Check: definitions without a docstring

(def documented-heads '#{defn defn- defmacro})

(defn missing-docstring
  "Findings for every top-level defn or defmacro whose third element
   is not a string."
  [forms]
  (for [form (filter #(calls? documented-heads %) forms)
        :when (not (string? (nth form 2 nil)))]
    {:line (line-of form)
     :message (format "%s has no docstring" (second form))}))

;; ---------------------------------------------------------------------
;; Check: let bindings that are never used

(defn binding-names
  "Every symbol a binding target introduces, including those inside
   destructuring vectors and maps."
  [target]
  (->> (subforms target)
       (filter symbol?)
       (remove #{'&})))

(defn- without-quoted
  "`form` with every (quote ...) replaced by nil, so that a quoted
   symbol is not mistaken for a use of a local."
  [form]
  (walk/prewalk (fn [x] (if (calls? #{'quote} x) nil x)) form))

(defn used-symbols
  "The set of symbols that appear anywhere in `forms`, ignoring quoted data."
  [forms]
  (->> forms
       (map without-quoted)
       (mapcat subforms)
       (filter symbol?)
       set))

(defn- placeholder?
  "True for names like _ or _unused that signal an intentionally ignored value."
  [sym]
  (str/starts-with? (name sym) "_"))

(defn unused-bindings
  "Findings for every let binding, anywhere in `forms`, whose name is
   not mentioned in a later binding or in the body."
  [forms]
  (for [form (find-calls #{'let} forms)
        :let [[_ bindings & body] form]
        :when (vector? bindings)
        :let [pairs (partition 2 bindings)]
        [i [target _]] (map-indexed vector pairs)
        :let [later (used-symbols (concat (map second (drop (inc i) pairs)) body))]
        name (binding-names target)
        :when (and (not (placeholder? name))
                   (not (contains? later name)))]
    {:line (line-of form)
     :message (format "%s is bound but never used" name)}))

;; ---------------------------------------------------------------------
;; Check: the same name defined twice

(defn- defined-name
  "The name a top-level form defines, if it is a def of some kind.
   defmethod is excluded because it adds to a name rather than defining one."
  [form]
  (when (and (seq? form)
             (symbol? (first form))
             (str/starts-with? (name (first form)) "def")
             (not= 'defmethod (first form))
             (symbol? (second form)))
    (second form)))

(defn duplicate-names
  "Findings for every top-level name that is defined more than once,
   reported at each definition after the first."
  [forms]
  (let [defs (keep (fn [form]
                     (when-let [n (defined-name form)]
                       {:name n :line (line-of form)}))
                   forms)]
    (for [[name occurrences] (group-by :name defs)
          :when (> (count occurrences) 1)
          later (rest occurrences)]
      {:line (:line later)
       :message (format "%s is already defined on line %s" name (:line (first occurrences)))})))

;; ---------------------------------------------------------------------
;; The linter

(def default-checks
  "The checks run when the caller does not supply any."
  [(partial too-many-params 4)
   (partial too-deep 6)
   missing-docstring
   unused-bindings
   duplicate-names])

(defn lint
  "Read `text` and run every check over it. Returns findings sorted by line."
  ([text]
   (lint text default-checks))
  ([text checks]
   (let [forms (read-forms text)]
     (->> checks
          (mapcat #(% forms))
          (sort-by (juxt :line :message))))))

(defn lint-file
  "Lint the file at `path`."
  [path]
  (lint (slurp path)))

(defn format-finding
  "Render a finding the way compilers do: path:line: message."
  [path {:keys [line message]}]
  (str path ":" line ": " message))

(defn -main
  "Lint each file named on the command line and print the findings.
   Exits with status 1 if there were any."
  [& paths]
  (let [lines (for [path paths
                    finding (lint-file path)]
                (format-finding path finding))]
    (doseq [line lines]
      (println line))
    (println (count lines) "finding(s)")
    (System/exit (if (seq lines) 1 0))))

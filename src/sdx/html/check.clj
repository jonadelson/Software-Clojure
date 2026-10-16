(ns sdx.html.check
  "Checks that run over a parsed HTML tree and collect violations.

   A violation is a map {:rule keyword :path [tags...] :message string}.
   An element check is a function (fn [node path]) that returns a
   sequence of violations for that one element. A document check is a
   function (fn [tree]) that looks at the whole tree at once."
  (:require [clojure.string :as str]
            [sdx.html.visitor :as v]))

;; ---------------------------------------------------------------------
;; The rules, as data.

(def global-attributes
  "Attributes that any known element may carry."
  #{"class" "id" "style" "title"})

(def allowed-attributes
  "For each tag we know about, the attributes it may carry beyond the
   global ones. A tag that is not a key here is not a tag we accept."
  {"html"  #{"lang"}
   "head"  #{}
   "title" #{}
   "body"  #{}
   "h1"    #{}
   "h2"    #{}
   "p"     #{}
   "div"   #{}
   "span"  #{}
   "em"    #{}
   "strong" #{}
   "a"     #{"href" "target"}
   "img"   #{"src" "alt" "width" "height"}
   "ul"    #{}
   "ol"    #{"start"}
   "li"    #{"value"}
   "table" #{}
   "tr"    #{}
   "th"    #{"colspan"}
   "td"    #{"colspan"}})

(def nesting-rules
  "Tags that may only appear directly inside certain parents."
  {"li"    #{"ul" "ol"}
   "tr"    #{"table"}
   "td"    #{"tr"}
   "th"    #{"tr"}
   "title" #{"head"}})

;; ---------------------------------------------------------------------
;; Element checks: (fn [node path]) -> violations

(defn- violation
  "Build a violation map."
  [rule path message]
  {:rule rule :path path :message message})

(defn check-known-tag
  "A violation if `node` uses a tag we have no rules for."
  [node path]
  (when-not (contains? allowed-attributes (:tag node))
    [(violation :unknown-tag path (str "unknown tag <" (:tag node) ">"))]))

(defn check-attributes
  "A violation for each attribute that `node` is not allowed to carry.
   Unknown tags are skipped, since check-known-tag already reports them."
  [node path]
  (let [tag (:tag node)]
    (when-let [allowed (get allowed-attributes tag)]
      (for [attr (sort (keys (:attrs node)))
            :when (not (or (contains? global-attributes attr)
                           (contains? allowed attr)))]
        (violation :attribute path
                   (str "<" tag "> may not have attribute " attr))))))

(defn check-nesting
  "A violation if `node` has a nesting rule and its parent breaks it."
  [node path]
  (let [tag (:tag node)
        parent (peek (pop path))]
    (when-let [parents (get nesting-rules tag)]
      (when-not (contains? parents parent)
        [(violation :nesting path
                    (str "<" tag "> must be inside "
                         (str/join " or " (map #(str "<" % ">") (sort parents)))
                         ", not "
                         (if parent (str "<" parent ">") "the top level")))]))))

(def element-checks
  "The element checks run by default."
  [check-known-tag check-attributes check-nesting])

;; ---------------------------------------------------------------------
;; Document checks: (fn [tree]) -> violations

(defn check-unique-ids
  "A violation for each id that is used more than once in `tree`.
   This check needs to remember what it has seen, so it is written as a
   walk with its own accumulator instead of as an element check."
  [tree]
  (:violations
   (v/walk tree
           {:enter (fn [{:keys [seen] :as acc} node path]
                     (let [id (get-in node [:attrs "id"])]
                       (cond
                         (nil? id) acc
                         (seen id) (update acc :violations conj
                                           (violation :duplicate-id path
                                                      (str "id \"" id "\" is used more than once")))
                         :else (update acc :seen conj id))))}
           {:seen #{} :violations []})))

(def document-checks
  "The document checks run by default."
  [check-unique-ids])

;; ---------------------------------------------------------------------
;; Running them all.

(defn validate
  "Run the element checks on every element and the document checks on
   the whole tree. Returns a vector of violations in document order,
   element violations first."
  ([tree]
   (validate tree element-checks document-checks))
  ([tree element-checks document-checks]
   (-> (v/walk tree
               {:enter (fn [found node path]
                         (into found (mapcat #(% node path)) element-checks))}
               [])
       (into (mapcat #(% tree)) document-checks))))

(defn path->string
  "Render a path for people: [\"html\" \"body\" \"p\"] -> \"html/body/p\"."
  [path]
  (str/join "/" path))

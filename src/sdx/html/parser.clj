(ns sdx.html.parser
  "A small HTML tokenizer and parser.

   The tokenizer turns text into a vector of tokens: an opening tag with
   its attributes, a closing tag, or a run of text. The parser turns the
   tokens into a tree. Every element is a map

       {:tag \"p\" :attrs {\"class\" \"note\"} :children [...]}

   text is a plain string, and the whole document hangs off a root node
   whose :tag is the keyword :document."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------
;; Tokenizing: text -> tokens

(def ^:private attr-pattern
  "One attribute: a name, optionally followed by =\"value\"."
  #"([^\s=\"/>]+)(?:=\"([^\"]*)\")?")

(defn- parse-attrs
  "Turn the text after a tag name into a map of attribute name -> value.
   An attribute written without a value gets the empty string."
  [text]
  (into {}
        (map (fn [[_ name value]] [name (or value "")]))
        (re-seq attr-pattern text)))

(defn- parse-tag
  "Turn the inside of <...> into an :open or :close token."
  [body pos]
  (if (str/starts-with? body "/")
    {:type :close :tag (str/trim (subs body 1)) :pos pos}
    (let [self-closing? (str/ends-with? body "/")
          body (if self-closing? (subs body 0 (dec (count body))) body)
          [_ tag attr-text] (re-matches #"(?s)\s*([^\s/>]+)(.*)" body)]
      (when-not tag
        (throw (ex-info "tag has no name" {:pos pos})))
      (cond-> {:type :open :tag tag :attrs (parse-attrs attr-text) :pos pos}
        self-closing? (assoc :self-closing true)))))

(defn tokenize
  "Split HTML text into a vector of tokens. Each token is a map with a
   :type of :open, :close, or :text, and the :pos where it started."
  [text]
  (loop [pos 0
         tokens []]
    (cond
      (>= pos (count text))
      tokens

      (= \< (nth text pos))
      (let [end (str/index-of text ">" pos)]
        (when-not end
          (throw (ex-info "tag is never closed" {:pos pos})))
        (recur (inc end)
               (conj tokens (parse-tag (subs text (inc pos) end) pos))))

      :else
      (let [end (or (str/index-of text "<" pos) (count text))]
        (recur end
               (conj tokens {:type :text :text (subs text pos end) :pos pos}))))))

;; ---------------------------------------------------------------------
;; Parsing: tokens -> tree

(defn element
  "Make an element node with the given tag, attribute map, and children."
  [tag attrs & children]
  {:tag tag :attrs attrs :children (vec children)})

(defn element?
  "True if `node` is an element rather than text."
  [node]
  (map? node))

(def ^:private root
  {:tag :document :attrs {} :children []})

(defn- add-child
  "Append `child` to the children of the node on top of `stack`."
  [stack child]
  (update stack (dec (count stack)) update :children conj child))

(defn- push-token
  "Feed one token to the stack of open elements."
  [stack {:keys [type tag attrs self-closing pos text]}]
  (case type
    :text
    (add-child stack text)

    :open
    (let [node (element tag attrs)]
      (if self-closing
        (add-child stack node)
        (conj stack (assoc node :pos pos))))

    :close
    (let [open (peek stack)]
      (when (= :document (:tag open))
        (throw (ex-info (str "closing </" tag "> but nothing is open")
                        {:pos pos :found tag})))
      (when (not= tag (:tag open))
        (throw (ex-info (str "expected </" (:tag open) "> but found </" tag ">")
                        {:pos pos :expected (:tag open) :found tag})))
      (add-child (pop stack) (dissoc open :pos)))))

(defn parse
  "Parse HTML text into a tree whose root has :tag :document.
   Throws ex-info with a :pos when tags are mismatched or left open."
  [text]
  (let [stack (reduce push-token [root] (tokenize text))]
    (when (> (count stack) 1)
      (let [open (peek stack)]
        (throw (ex-info (str "<" (:tag open) "> is never closed")
                        {:pos (:pos open) :tag (:tag open)}))))
    (peek stack)))

;; ---------------------------------------------------------------------
;; Unparsing: tree -> text

(defn attrs->html
  "Render an attribute map as text, sorted by name so output is predictable."
  [attrs]
  (apply str (map (fn [[name value]] (str " " name "=\"" value "\""))
                  (sort attrs))))

(defn ->html
  "Turn a tree back into HTML text."
  [node]
  (cond
    (string? node) node
    (= :document (:tag node)) (apply str (map ->html (:children node)))
    :else (str "<" (:tag node) (attrs->html (:attrs node)) ">"
               (apply str (map ->html (:children node)))
               "</" (:tag node) ">")))

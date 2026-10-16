(ns sdx.parser
  "Turning glob text such as \"2023-*.{pdf,txt}\" into the matcher data
   used by sdx.glob.data, in two steps: a tokenizer that groups characters
   into tokens, and a recursive-descent parser that groups tokens into
   nodes.

   Usage from the command line, printing the names that match:

     clojure -M -m sdx.parser PATTERN NAME..."
  (:require [sdx.glob.data :as glob]))

;; ---------------------------------------------------------------------
;; Tokens

(def ^:private special
  "Characters that mean something other than themselves, and the kind
   of token each one produces."
  {\* :any, \? :one, \{ :open, \, :comma, \} :close})

(defn- add-literal
  "Add a :lit token for the characters of `s` from `start` to `end` to
   `tokens`, unless that range is empty."
  [tokens s start end]
  (if (< start end)
    (conj tokens {:type :lit :text (subs s start end) :pos start})
    tokens))

(defn tokenize
  "Split pattern text into a vector of tokens. Each token is a map with
   a :type, the :pos of its first character, and for literals the :text."
  [s]
  (let [n (count s)]
    (loop [i 0, start 0, tokens []]
      (if (= i n)
        (add-literal tokens s start n)
        (if-let [kind (special (nth s i))]
          (recur (inc i) (inc i)
                 (conj (add-literal tokens s start i) {:type kind :pos i}))
          (recur (inc i) start tokens))))))

;; ---------------------------------------------------------------------
;; Grammar
;;
;;   pattern := item*
;;   item    := LIT | ANY | ONE | either
;;   either  := OPEN pattern (COMMA pattern)* CLOSE
;;
;; Each parse function takes the token vector and the index of the token
;; it should start at, and returns [result index-of-the-next-token].

(defn- fail
  "Throw a parse error that says what went wrong and where."
  [message pos]
  (throw (ex-info (str message " at position " pos) {:pos pos})))

(defn- describe
  "The characters that a token stood for, for use in error messages."
  [token]
  (case (:type token)
    :lit (:text token)
    :any "*"
    :one "?"
    :open "{"
    :comma ","
    :close "}"))

(declare parse-pattern)

(defn- parse-either
  "Parse a {...} group whose :open token is at index `i`. Returns the
   :either node and the index just past the :close token."
  [tokens i]
  (let [open (nth tokens i)]
    (loop [i (inc i), alternatives []]
      (let [[nodes i] (parse-pattern tokens i)
            alternatives (conj alternatives nodes)]
        (case (:type (get tokens i))
          :comma (recur (inc i) alternatives)
          :close [(into [:either] alternatives) (inc i)]
          (fail "unclosed '{' opened" (:pos open)))))))

(defn- parse-item
  "Parse one item starting at index `i`. Returns the node and the next index."
  [tokens i]
  (let [token (nth tokens i)]
    (case (:type token)
      :lit [[:lit (:text token)] (inc i)]
      :any [[:any] (inc i)]
      :one [[:one] (inc i)]
      :open (parse-either tokens i))))

(def ^:private stops
  "Token types that end a pattern without being part of it."
  #{:comma :close})

(defn- parse-pattern
  "Parse items starting at index `i` until a comma, a closing brace, or
   the end of the tokens. Returns the vector of nodes and the next index."
  [tokens i]
  (loop [i i, nodes []]
    (let [kind (:type (get tokens i))]
      (if (or (nil? kind) (stops kind))
        [nodes i]
        (let [[node i] (parse-item tokens i)]
          (recur i (conj nodes node)))))))

(defn parse
  "Turn glob text into matcher data for sdx.glob.data/match. Throws
   ex-info with a :pos if the text is not a well-formed pattern."
  [s]
  (let [tokens (tokenize s)
        [nodes i] (parse-pattern tokens 0)]
    (if (= i (count tokens))
      nodes
      (let [token (nth tokens i)]
        (fail (str "unexpected '" (describe token) "'") (:pos token))))))

;; ---------------------------------------------------------------------
;; Everything together

(defn matches?
  "True if `text` matches the glob `pattern`."
  [pattern text]
  (glob/match (parse pattern) text))

(defn -main
  "Print each NAME that matches PATTERN, one per line."
  [& [pattern & names]]
  (if pattern
    (let [nodes (parse pattern)]
      (doseq [name names
              :when (glob/match nodes name)]
        (println name)))
    (println "usage: clojure -M -m sdx.parser PATTERN NAME...")))

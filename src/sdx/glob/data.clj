(ns sdx.glob.data
  "The same glob matcher with the pattern as plain data: a vector of
   nodes, each a vector whose first element says what kind of node it is.

     [[:lit \"2023-\"] [:any] [:lit \".\"] [:either [[:lit \"pdf\"]] [[:lit \"txt\"]]]]

   The chain of responsibility is now just the order of the vector, and
   the terminator is the empty sequence."
  (:require [clojure.string :as str]))

(defn match-at
  "True if `nodes` match `text` from `pos` to the end of the text."
  [nodes text pos]
  (if (empty? nodes)
    (= pos (count text))
    (let [[node & more] nodes
          n (count text)]
      (case (first node)
        :lit (let [[_ chars] node
                   end (+ pos (count chars))]
               (and (<= end n)
                    (= chars (subs text pos end))
                    (match-at more text end)))
        :one (and (< pos n)
                  (match-at more text (inc pos)))
        :any (boolean (some #(match-at more text %)
                            (range pos (inc n))))
        :either (let [[_ & alternatives] node]
                  (boolean (some #(match-at (concat % more) text pos)
                                 alternatives)))
        (throw (ex-info (str "unknown node type " (first node))
                        {:node node}))))))

(defn match
  "True if the whole of `text` matches the pattern `nodes`."
  [nodes text]
  (match-at nodes text 0))

(defn pattern->string
  "The glob text that a pattern stands for. A second interpretation of
   the same data, written without touching `match`."
  [nodes]
  (apply str
         (map (fn [[kind & args]]
                (case kind
                  :lit (first args)
                  :one "?"
                  :any "*"
                  :either (str "{" (str/join "," (map pattern->string args)) "}")))
              nodes)))

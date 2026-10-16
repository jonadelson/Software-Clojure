(ns sdx.glob.first
  "A glob matcher built from records. Each record matches one piece of a
   pattern and then hands the rest of the text to the matcher after it,
   so a pattern is a chain of records ending in a Null terminator.")

(defprotocol Matcher
  "One link in a chain of pattern pieces."
  (match-at [this text pos]
    "True if this piece matches `text` starting at `pos` and every piece
     after it matches the remainder of the text."))

(defrecord Null []
  Matcher
  (match-at [_ text pos]
    (= pos (count text))))

(defrecord Lit [chars rest]
  Matcher
  (match-at [_ text pos]
    (let [end (+ pos (count chars))]
      (and (<= end (count text))
           (= chars (subs text pos end))
           (match-at rest text end)))))

(defrecord One [rest]
  Matcher
  (match-at [_ text pos]
    (and (< pos (count text))
         (match-at rest text (inc pos)))))

(defrecord Any [rest]
  Matcher
  (match-at [_ text pos]
    (boolean (some #(match-at rest text %)
                   (range pos (inc (count text)))))))

(defrecord Either [left right rest]
  Matcher
  (match-at [_ text pos]
    (boolean (some (fn [end]
                     (let [piece (subs text pos end)]
                       (and (or (match-at left piece 0)
                                (match-at right piece 0))
                            (match-at rest text end))))
                   (range pos (inc (count text)))))))

(defn match
  "True if the whole of `text` matches the chain starting at `matcher`."
  [matcher text]
  (match-at matcher text 0))

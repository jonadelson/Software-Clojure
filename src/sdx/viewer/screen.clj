(ns sdx.viewer.screen
  "The boundary between the viewer and whatever it draws on.

   A Screen is anything that can tell us its size, blank itself, write
   text at a position, place a cursor, and hand us the next key. The
   viewer only ever talks to a Screen, so the real terminal can be
   replaced by a headless one that records what was drawn and answers
   `read-key` from a script. Tests use the headless one.

   Keys are data. A printable character arrives as a one-character
   string (\"j\", \"q\"); everything else arrives as a keyword
   (:up, :down, :left, :right, :page-up, :page-down, :enter,
   :backspace, :escape, :ctrl-c, ...). `read-key` returns nil when
   there is nothing left to read.")

(defprotocol Screen
  "What the viewer needs from a display and a keyboard."
  (dimensions [this] "The screen's [height width] in character cells.")
  (erase [this] "Blank every cell.")
  (write-at [this row col text]
    "Write `text` starting at `row`, `col` (both 0-based). Text past the
     right edge is clipped; rows off the screen are ignored.")
  (move-cursor [this row col] "Put the cursor at `row`, `col`.")
  (read-key [this] "Wait for the next key and return it, or nil if there is none."))

;; ---------------------------------------------------------------------
;; Helpers that every implementation needs.

(defn clip
  "At most the first `n` characters of `text`."
  [text n]
  (if (> (count text) n)
    (subs text 0 (max n 0))
    text))

(defn overlay
  "The string `row` with `text` written over it from column `col`,
   clipped to the row's width. Writes that start off the row are dropped."
  [row col text]
  (let [width (count row)]
    (if (or (neg? col) (>= col width))
      row
      (let [text (clip text (- width col))]
        (str (subs row 0 col) text (subs row (+ col (count text))))))))

;; ---------------------------------------------------------------------
;; A screen that draws into a vector of strings.

(defn- blank-rows
  "A vector of `height` strings, each `width` spaces long."
  [height width]
  (vec (repeat height (apply str (repeat width \space)))))

(defrecord Headless [height width cells script]
  Screen
  (dimensions [_] [height width])
  (erase [_]
    (swap! cells assoc :rows (blank-rows height width)))
  (write-at [_ row col text]
    (when (and (<= 0 row) (< row height))
      (swap! cells update-in [:rows row] overlay col text)))
  (move-cursor [_ row col]
    (swap! cells assoc :cursor [row col]))
  (read-key [_]
    (let [k (first @script)]
      (swap! script rest)
      k)))

(defn headless
  "A screen `height` rows by `width` columns that answers `read-key`
   with each of `keys` in turn and then with nil."
  [height width keys]
  (->Headless height width
              (atom {:rows (blank-rows height width) :cursor [0 0]})
              (atom (seq keys))))

(defn rows
  "What is on a headless screen right now: a vector of strings."
  [screen]
  (:rows @(:cells screen)))

(defn cursor
  "Where a headless screen's cursor is: [row col]."
  [screen]
  (:cursor @(:cells screen)))

(ns sdx.undo.edit
  "Stage 1: the viewer becomes an editor.

   A key that changes the text is first turned into an action, a small
   map such as {:op :insert :row 2 :col 5 :char \"x\"} that describes
   the change completely, and then the action is applied to the state.
   Keeping these two steps apart is what makes undo possible later."
  (:require [sdx.viewer.core :as core]))

(defn make-state
  "An editor state: the viewer's state over at least one line, so that
   there is always somewhere to type."
  [lines height]
  (core/make-state (if (seq lines) lines [""]) height))

;; ---------------------------------------------------------------------
;; Applying actions to the text.

(defmulti apply-action
  "Perform `action` on `state`. Returns the new state with the cursor
   where the change ended."
  (fn [_state action] (:op action)))

(defn- splice
  "The string `s` with the characters from `from` (inclusive) to `to`
   (exclusive) replaced by `middle`."
  [s from to middle]
  (str (subs s 0 from) middle (subs s to)))

(defn- replace-lines
  "The vector `lines` with `n` lines starting at `row` replaced by `with`."
  [lines row n with]
  (-> (subvec lines 0 row)
      (into with)
      (into (subvec lines (+ row n)))))

(defmethod apply-action :insert
  [state {:keys [row col char]}]
  (-> state
      (update-in [:lines row] splice col col char)
      (assoc :cursor [row (inc col)])))

(defmethod apply-action :delete
  [state {:keys [row col]}]
  (-> state
      (update-in [:lines row] splice col (inc col) "")
      (assoc :cursor [row col])))

(defmethod apply-action :split
  [state {:keys [row col]}]
  (let [line (get-in state [:lines row])]
    (-> state
        (update :lines replace-lines row 1 [(subs line 0 col) (subs line col)])
        (assoc :cursor [(inc row) 0]))))

(defmethod apply-action :join
  [state {:keys [row col]}]
  (let [lines (:lines state)]
    (-> state
        (update :lines replace-lines row 2 [(str (lines row) (lines (inc row)))])
        (assoc :cursor [row col]))))

;; ---------------------------------------------------------------------
;; Turning keys into actions.

(defn- printable?
  "True for a key that stands for a character the user typed."
  [key]
  (and (string? key)
       (= 1 (count key))
       (<= 32 (int (first key)))))

(defn key->action
  "The action `key` stands for in `state`, or nil if the key does not
   edit the text. Each action carries everything needed to describe
   the change on its own: the character a delete removes, the column a
   join happens at."
  [state key]
  (let [{:keys [lines cursor]} state
        [row col] cursor]
    (cond
      (printable? key)
      {:op :insert :row row :col col :char key}

      (= key :enter)
      {:op :split :row row :col col}

      (and (= key :backspace) (pos? col))
      {:op :delete :row row :col (dec col) :char (subs (lines row) (dec col) col)}

      (and (= key :backspace) (pos? row))
      {:op :join :row (dec row) :col (count (lines (dec row)))}

      :else nil)))

;; ---------------------------------------------------------------------
;; Wiring it into the viewer's loop.

(def bindings
  "The viewer's bindings for every key that is not a letter, plus
   ctrl-q to quit. Letters are text now, so h/j/k/l and q go."
  (into {:ctrl-q core/quit}
        (remove (fn [[k _]] (string? k)))
        core/bindings))

(defn step
  "The state after one key: build and perform an action if the key
   edits, otherwise let the viewer handle it. `perform` is how an
   action is applied; later stages pass one that also remembers it."
  ([state key] (step apply-action bindings state key))
  ([perform bindings state key]
   (if-let [action (key->action state key)]
     (core/scroll-to-cursor (perform state action))
     (core/step bindings state key))))

(defn run
  "Edit on `screen` until ctrl-q. Returns the final state."
  [screen state]
  (core/run step screen state))

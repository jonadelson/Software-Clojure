(ns sdx.undo.snapshot
  "Stage 3: undo and redo by remembering states.

   Instead of recording what was done, record what the text and cursor
   were before each change. Undo is then just going back to the
   previous snapshot. Because Clojure's vectors and strings are
   immutable and share structure, a snapshot costs almost nothing."
  (:require [sdx.undo.edit :as edit]
            [sdx.viewer.core :as core]))

(defn make-state
  "An editor state with no past and no future."
  [lines height]
  (assoc (edit/make-state lines height) :past [] :future []))

(defn- snapshot
  "The parts of `state` that an edit can change."
  [state]
  (select-keys state [:lines :cursor]))

(defn perform
  "Apply `action`, keeping a snapshot of what things were like before."
  [state action]
  (-> (edit/apply-action state action)
      (update :past conj (snapshot state))
      (assoc :future [])))

(defn- travel
  "Step along the history: restore the snapshot on top of `from` and
   push a snapshot of the present onto `to`."
  [state from to]
  (if-let [snap (peek (get state from))]
    (-> state
        (merge snap)
        (update from pop)
        (update to conj (snapshot state)))
    state))

(defn undo
  "Go back to the state before the most recent change."
  [state]
  (travel state :past :future))

(defn redo
  "Go forward to the state the most recent undo left."
  [state]
  (travel state :future :past))

(def bindings
  "The editor's bindings plus ctrl-z to undo and ctrl-y to redo."
  (assoc edit/bindings :ctrl-z undo :ctrl-y redo))

(defn step
  "The state after one key, with a snapshot taken before every edit."
  [state key]
  (edit/step perform bindings state key))

(defn run
  "Edit on `screen` until ctrl-q. Returns the final state."
  [screen state]
  (core/run step screen state))

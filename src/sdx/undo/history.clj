(ns sdx.undo.history
  "Stage 2: undo and redo by remembering actions.

   The state keeps a vector of the actions that have been :done and a
   vector of those that were :undone. Undoing applies the inverse of
   the most recent action; redoing applies the action again."
  (:require [sdx.undo.edit :as edit]
            [sdx.viewer.core :as core]))

(defn make-state
  "An editor state with an empty history."
  [lines height]
  (assoc (edit/make-state lines height) :done [] :undone []))

(def ^:private inverse-op
  "Each operation and the one that reverses it."
  {:insert :delete, :delete :insert, :split :join, :join :split})

(defn invert
  "The action that exactly reverses `action`."
  [action]
  (update action :op inverse-op))

(defn perform
  "Apply `action` and remember it. Anything that had been undone can no
   longer be redone, so that list is cleared."
  [state action]
  (-> (edit/apply-action state action)
      (update :done conj action)
      (assoc :undone [])))

(defn undo
  "Reverse the most recent action, if there is one."
  [state]
  (if-let [action (peek (:done state))]
    (-> (edit/apply-action state (invert action))
        (update :done pop)
        (update :undone conj action))
    state))

(defn redo
  "Apply the most recently undone action again, if there is one."
  [state]
  (if-let [action (peek (:undone state))]
    (-> (edit/apply-action state action)
        (update :undone pop)
        (update :done conj action))
    state))

(def bindings
  "The editor's bindings plus ctrl-z to undo and ctrl-y to redo."
  (assoc edit/bindings :ctrl-z undo :ctrl-y redo))

(defn step
  "The state after one key, with every edit recorded."
  [state key]
  (edit/step perform bindings state key))

(defn run
  "Edit on `screen` until ctrl-q. Returns the final state."
  [screen state]
  (core/run step screen state))

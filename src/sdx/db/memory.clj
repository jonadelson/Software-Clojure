(ns sdx.db.memory
  "Stage 1: an append-only database kept entirely in memory.

   The database is an immutable map with two parts: a vector of every
   record ever added, in the order it arrived, and an index from a
   record's :id to its position in that vector. Nothing is changed in
   place; adding a record returns a new database value.")

(defn create
  "An empty database."
  []
  {:records [] :index {}})

(defn add
  "Append `record` to `db` and point the index at it. If a record with
   the same :id already exists, the old one stays in the log and the
   index moves to the new one."
  [db record]
  (when (nil? (:id record))
    (throw (ex-info "record has no :id" {:record record})))
  (-> db
      (update :records conj record)
      (assoc-in [:index (:id record)] (count (:records db)))))

(defn lookup
  "The most recent record with `id`, or nil if there is none."
  [db id]
  (when-let [pos (get-in db [:index id])]
    (nth (:records db) pos)))

(defn history
  "Every version of the record with `id`, oldest first."
  [db id]
  (filterv #(= id (:id %)) (:records db)))

(defn size
  "How many records are in the log, including superseded ones."
  [db]
  (count (:records db)))

(defn ids
  "The ids of every record that has a current version."
  [db]
  (set (keys (:index db))))

(defn rebuild-index
  "The index that a log of `records` implies: the position of the last
   record with each id. (add) maintains this incrementally; this
   function computes it from scratch, which is how a database that was
   written to disk gets its index back."
  [records]
  (reduce (fn [index [pos record]]
            (assoc index (:id record) pos))
          {}
          (map-indexed vector records)))

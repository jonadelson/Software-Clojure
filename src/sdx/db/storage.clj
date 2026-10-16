(ns sdx.db.storage
  "Stage 2: the database talks to its storage through a protocol, so the
   same database code can keep its records in memory or in a file.

   Storage is a sequence of fixed-size blocks that can only grow. The
   database on top of it is still a map: the storage and an index from
   id to block position. Adding a record has a side effect on the
   storage but still returns a new database value with the updated
   index, so the index can be threaded through code like any other
   immutable value."
  (:require [sdx.db.record :as rec])
  (:import [java.io RandomAccessFile]))

(defprotocol Storage
  "An append-only sequence of fixed-size byte blocks."
  (block-count [this] "How many blocks are stored.")
  (append-block! [this block] "Store `block` after the others and return its position.")
  (read-block [this pos] "The bytes of the block at position `pos`.")
  (close! [this] "Release whatever the storage holds open."))

(defn memory-storage
  "Storage that keeps its blocks in a vector inside an atom. The atom is
   needed because the protocol's methods must share state between
   calls: a caller who appends a block expects a later read to see it."
  []
  (let [blocks (atom [])]
    (reify Storage
      (block-count [_] (count @blocks))
      (append-block! [_ block] (dec (count (swap! blocks conj block))))
      (read-block [_ pos] (nth @blocks pos))
      (close! [_] nil))))

(defn file-storage
  "Storage that appends blocks of `block-size` bytes to the file at
   `path`, creating it if necessary, and reads them back by seeking."
  [path block-size]
  (let [file (RandomAccessFile. (str path) "rw")]
    (reify Storage
      (block-count [_] (quot (.length file) block-size))
      (append-block! [_ block]
        (let [pos (quot (.length file) block-size)]
          (.seek file (.length file))
          (.write file ^bytes block)
          pos))
      (read-block [_ pos]
        (let [block (byte-array block-size)]
          (.seek file (* pos block-size))
          (.readFully file block)
          block))
      (close! [_] (.close file)))))

(defn record-at
  "The record stored at block position `pos`."
  [storage pos]
  (rec/unpack (read-block storage pos)))

(defn open
  "A database over `storage`. The index is rebuilt by reading every
   block, so a database can be reopened from a file written earlier."
  [storage]
  {:storage storage
   :index (reduce (fn [index pos]
                    (assoc index (:id (record-at storage pos)) pos))
                  {}
                  (range (block-count storage)))})

(defn add!
  "Append `record` to the database's storage and return the database
   with its index pointing at the new block."
  [db record]
  (let [pos (append-block! (:storage db) (rec/pack record))]
    (assoc-in db [:index (:id record)] pos)))

(defn lookup
  "The most recent record with `id`, or nil."
  [db id]
  (when-let [pos (get-in db [:index id])]
    (record-at (:storage db) pos)))

(defn history
  "Every version of the record with `id`, oldest first. This has to
   scan the whole log because the index only remembers the latest."
  [db id]
  (let [storage (:storage db)]
    (->> (range (block-count storage))
         (map #(record-at storage %))
         (filterv #(= id (:id %))))))

(defn size
  "How many records are in the log, including superseded ones."
  [db]
  (block-count (:storage db)))

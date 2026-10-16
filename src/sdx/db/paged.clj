(ns sdx.db.paged
  "Stage 3: file storage that moves data in whole pages and keeps a
   small cache of the pages it has read recently.

   A page holds a fixed number of blocks. Reading one record reads its
   whole page, so reading the neighbouring records afterwards costs
   nothing. The cache is a least-recently-used cache: when it is full,
   the page that has gone longest without being touched is dropped."
  (:require [sdx.db.storage :as s])
  (:import [java.io RandomAccessFile]
           [java.util Arrays]))

;; ---------------------------------------------------------------------
;; The cache is a pure data structure. Nothing here does I/O.

(defn empty-cache
  "A cache that can hold `capacity` pages."
  [capacity]
  {:capacity capacity :pages {} :recent [] :hits 0 :misses 0})

(defn- touch
  "Move `n` to the most-recent end of `recent`."
  [recent n]
  (conj (filterv #(not= n %) recent) n))

(defn hit
  "Record that page `n`, already present, was used again."
  [cache n]
  (-> cache (update :recent touch n) (update :hits inc)))

(defn insert
  "Add page `n` with contents `page`, dropping the least recently used
   page if the cache is over capacity."
  [cache n page]
  (let [cache (-> cache
                  (assoc-in [:pages n] page)
                  (update :recent touch n)
                  (update :misses inc))]
    (if (> (count (:pages cache)) (:capacity cache))
      (let [oldest (first (:recent cache))]
        (-> cache
            (update :pages dissoc oldest)
            (update :recent subvec 1)))
      cache)))

(defn forget
  "Drop page `n` from the cache, because the file has changed under it."
  [cache n]
  (-> cache
      (update :pages dissoc n)
      (update :recent #(filterv (fn [m] (not= m n)) %))))

(defn resident
  "The page numbers currently held, least recently used first."
  [cache]
  (:recent cache))

;; ---------------------------------------------------------------------
;; Storage that reads whole pages and keeps them in a cache.

(defprotocol Cached
  "Something that keeps a cache and can report on it."
  (stats [this] "A map with :hits, :misses, and :resident pages."))

(defn- load-page
  "Read page `n` of `page-bytes` bytes from `file`. The last page of a
   growing file may be partly filled; the rest of it is left as zeros."
  [^RandomAccessFile file page-bytes n]
  (let [page (byte-array page-bytes)
        start (* n page-bytes)
        available (max 0 (- (.length file) start))]
    (.seek file start)
    (.readFully file page 0 (int (min page-bytes available)))
    page))

(defn page-storage
  "File storage over `path` with pages of `per-page` blocks of
   `block-size` bytes each, keeping up to `capacity` pages cached."
  [path {:keys [block-size per-page capacity]}]
  (let [file (RandomAccessFile. (str path) "rw")
        page-bytes (* block-size per-page)
        cache (atom (empty-cache capacity))
        fetch (fn [n]
                (if (contains? (:pages @cache) n)
                  (do (swap! cache hit n)
                      (get-in @cache [:pages n]))
                  (let [page (load-page file page-bytes n)]
                    (swap! cache insert n page)
                    page)))]
    (reify
      s/Storage
      (block-count [_] (quot (.length file) block-size))
      (append-block! [_ block]
        (let [pos (quot (.length file) block-size)]
          (.seek file (.length file))
          (.write file ^bytes block)
          (swap! cache forget (quot pos per-page))
          pos))
      (read-block [_ pos]
        (let [page (fetch (quot pos per-page))
              start (* (rem pos per-page) block-size)]
          (Arrays/copyOfRange ^bytes page (int start) (int (+ start block-size)))))
      (close! [_] (.close file))

      Cached
      (stats [_]
        (let [c @cache]
          {:hits (:hits c) :misses (:misses c) :resident (resident c)})))))

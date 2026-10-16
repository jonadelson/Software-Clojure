(ns sdx.archive.restore
  "Stage 2 of the archiver: read a manifest back and recreate the files
   it lists from the content-addressed store."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sdx.archive.backup :as b])
  (:import [java.io File]))

(defn parse-manifest
  "Turn manifest text back into a seq of {:path :hash}. The hash is
   everything after the last comma, so paths may contain commas."
  [text]
  (->> (str/split-lines text)
       (remove str/blank?)
       (map (fn [line]
              (let [i (str/last-index-of line ",")]
                {:path (subs line 0 i)
                 :hash (subs line (inc i))})))))

(defn manifests
  "Every manifest in `backup-dir`, oldest first."
  [backup-dir]
  (->> (.listFiles (io/file backup-dir))
       (filter #(str/ends-with? (.getName ^File %) ".csv"))
       (sort-by #(.getName ^File %))))

(defn latest-manifest
  "The most recent manifest in `backup-dir`, or nil if there is none."
  [backup-dir]
  (last (manifests backup-dir)))

(defn check-stored
  "The stored file for `hash`, after making sure it exists and that its
   contents still match its name. Throws ex-info otherwise."
  [backup-dir hash]
  (let [stored (b/stored-file backup-dir hash)]
    (cond
      (not (.exists ^File stored))
      (throw (ex-info "missing from backup" {:hash hash}))

      (not= hash (b/hash-file stored))
      (throw (ex-info "stored file is corrupt" {:hash hash :actual (b/hash-file stored)}))

      :else stored)))

(defn restore
  "Recreate every file listed in `manifest` under `target-dir`, taking
   the contents from `backup-dir`. Returns {:restored n}."
  [backup-dir manifest target-dir]
  (let [entries (parse-manifest (slurp manifest))]
    (doseq [{:keys [path hash]} entries]
      (let [stored (check-stored backup-dir hash)
            target (io/file target-dir path)]
        (io/make-parents target)
        (io/copy stored target)))
    {:restored (count entries)}))

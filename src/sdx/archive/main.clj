(ns sdx.archive.main
  "Command-line entry point for the archiver.

   clojure -M -m sdx.archive.main backup SOURCE BACKUP-DIR
   clojure -M -m sdx.archive.main restore BACKUP-DIR TARGET [MANIFEST]"
  (:require [sdx.archive.backup :as b]
            [sdx.archive.restore :as r]))

(defn- usage []
  (println "usage: backup SOURCE BACKUP-DIR")
  (println "       restore BACKUP-DIR TARGET [MANIFEST]"))

(defn -main
  "Dispatch on the first argument and print a one-line summary."
  [& [command & args]]
  (case command
    "backup"
    (let [[source backup-dir] args
          {:keys [manifest entries copied]} (b/backup source backup-dir)]
      (println (format "%d files, %d new, manifest %s"
                       (count entries) copied (.getName manifest))))

    "restore"
    (let [[backup-dir target manifest] args
          manifest (if manifest
                     (java.io.File. manifest)
                     (r/latest-manifest backup-dir))
          {:keys [restored]} (r/restore backup-dir manifest target)]
      (println (format "restored %d files from %s" restored (.getName manifest))))

    (usage)))

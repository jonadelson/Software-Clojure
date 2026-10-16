(ns sdx.ftp.main-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [sdx.ftp.concurrent :as ftp]
            [sdx.ftp.helpers :as h]
            [sdx.ftp.main :as main]))

(deftest sending-from-the-command-line
  (h/with-temp-dir
    (fn [dir]
      (let [dst (io/file dir "dst")
            a (h/write-file! (io/file dir "src" "a.txt") (.getBytes "abc"))
            b (h/write-file! (io/file dir "src" "b.txt") (.getBytes "defgh"))
            {:keys [port stop]} (ftp/start-server dst)]
        (try
          (let [printed (with-out-str
                          (main/-main "send" "127.0.0.1" (str port) (str a) (str b)))]
            (is (re-find #"a\.txt -> 3 bytes" printed))
            (is (re-find #"b\.txt -> 5 bytes" printed)))
          (is (= "abc" (slurp (io/file dst "a.txt"))))
          (is (= "defgh" (slurp (io/file dst "b.txt"))))
          (finally
            (stop)))))))

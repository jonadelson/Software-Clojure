(ns sdx.ftp.concurrent-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [sdx.ftp.chunked :as chunked]
            [sdx.ftp.concurrent :as ftp]
            [sdx.ftp.helpers :as h]
            [sdx.ftp.simple :as simple])
  (:import [java.io IOException]
           [java.net Socket SocketTimeoutException]))

(def quiet
  "An error reporter for tests that expect errors."
  (fn [_]))

(deftest many-clients-at-once
  (h/with-temp-dir
    (fn [dir]
      (let [dst (io/file dir "dst")
            files (vec (for [i (range 8)]
                         (h/write-file! (io/file dir "src" (str "f" i ".bin"))
                                        (h/pseudo-random-bytes (* (inc i) 3000)))))
            {:keys [port stop]} (ftp/start-server dst {:on-error quiet})]
        (try
          (let [replies (mapv #(future (chunked/send-file "127.0.0.1" port %)) files)]
            (is (= (map #(.length ^java.io.File %) files)
                   (map #(deref % 10000 ::timeout) replies))))
          (doseq [^java.io.File f files]
            (is (= (vec (h/read-file f))
                   (vec (h/read-file (io/file dst (.getName f)))))))
          (finally
            (stop)))))))

(deftest a-stalled-client-is-cut-off
  (h/with-temp-dir
    (fn [dir]
      (let [failure (promise)
            {:keys [port stop]} (ftp/start-server (io/file dir "dst")
                                                  {:timeout-ms 200
                                                   :on-error #(deliver failure %)})]
        (try
          (with-open [sock (Socket. "127.0.0.1" (int port))]
            (let [out (simple/data-out sock)]
              (simple/write-str out "stalled.txt")
              (.flush out)
              (testing "the server gives up on us after the timeout"
                (is (instance? SocketTimeoutException (deref failure 5000 ::no-error))))))
          (testing "and is still there for the next client"
            (let [f (h/write-file! (io/file dir "src" "ok.txt") (.getBytes "hello"))]
              (is (= 5 (chunked/send-file "127.0.0.1" port f)))))
          (finally
            (stop)))))))

(deftest stopping-closes-open-connections
  (h/with-temp-dir
    (fn [dir]
      (let [{:keys [port stop connections]} (ftp/start-server dir {:on-error quiet})]
        (with-open [sock (Socket. "127.0.0.1" (int port))]
          (.setSoTimeout sock 5000)
          (is (h/wait-until #(seq @connections) 5000) "the server has accepted us")
          (stop)
          (testing "the idle connection is closed from the server's side"
            (is (try (= -1 (.read (.getInputStream sock)))
                     (catch IOException _ true))))
          (is (empty? @connections)))))))

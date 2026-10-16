(ns sdx.ftp.chunked-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [sdx.ftp.chunked :as ftp]
            [sdx.ftp.helpers :as h])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(deftest copying-an-exact-number-of-bytes
  (let [data (h/pseudo-random-bytes 10000)
        in (ByteArrayInputStream. data)
        out (ByteArrayOutputStream.)]
    (testing "the buffer is smaller than the data and does not divide it"
      (is (= 10000 (ftp/copy-n in out 10000)))
      (is (= (vec data) (vec (.toByteArray out)))))
    (testing "nothing is read past the requested count"
      (is (= 10000 (ftp/copy-n (ByteArrayInputStream. data) (ByteArrayOutputStream.) 10000)))
      (let [in (ByteArrayInputStream. data)]
        (ftp/copy-n in (ByteArrayOutputStream.) 100)
        (is (= 9900 (.available in)))))
    (testing "a short input is an error, not a short file"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ended early"
                            (ftp/copy-n (ByteArrayInputStream. data)
                                        (ByteArrayOutputStream.) 20000))))))

(deftest sending-files-larger-than-the-buffer
  (h/with-temp-dir
    (fn [dir]
      (let [dst (io/file dir "dst")
            {:keys [port stop]} (ftp/start-server dst)]
        (try
          (doseq [n [0 1 4096 4097 100000]]
            (let [data (h/pseudo-random-bytes n)
                  f (h/write-file! (io/file dir "src" (str n ".bin")) data)]
              (is (= n (ftp/send-file "127.0.0.1" port f)))
              (is (= (vec data) (vec (h/read-file (io/file dst (str n ".bin"))))))))
          (finally
            (stop)))))))

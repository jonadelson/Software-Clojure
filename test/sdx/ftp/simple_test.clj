(ns sdx.ftp.simple-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [sdx.ftp.helpers :as h]
            [sdx.ftp.simple :as ftp])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            DataInputStream DataOutputStream EOFException]
           [java.net Socket]))

(defn- frame-bytes
  "The bytes `f` writes to a DataOutputStream."
  ^bytes [f]
  (let [bytes (ByteArrayOutputStream.)]
    (f (DataOutputStream. bytes))
    (.toByteArray bytes)))

(defn- data-input [^bytes bs]
  (DataInputStream. (ByteArrayInputStream. bs)))

(deftest frames-round-trip
  (let [payload (.getBytes "hello" "UTF-8")
        wire (frame-bytes #(ftp/write-frame % payload))]
    (testing "a frame is a 4-byte length followed by the bytes"
      (is (= [0 0 0 5 104 101 108 108 111] (vec wire))))
    (is (= (vec payload) (vec (ftp/read-frame (data-input wire)))))))

(deftest strings-are-frames-of-utf-8
  (let [wire (frame-bytes #(ftp/write-str % "héllo"))]
    (is (= 6 (nth wire 3)) "é takes two bytes in UTF-8")
    (is (= "héllo" (ftp/read-str (data-input wire))))))

(deftest bad-frame-lengths-are-refused
  (testing "a negative length"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bad frame length"
                          (ftp/read-frame (data-input (byte-array [-1 -1 -1 -1]))))))
  (testing "a length with no bytes behind it"
    (is (thrown? EOFException
                 (ftp/read-frame (data-input (byte-array [0 0 0 9 1 2])))))))

(deftest file-names-must-be-plain
  (is (= "notes.txt" (ftp/safe-name "notes.txt")))
  (doseq [bad ["" "." ".." "../notes.txt" "a/b" "a\\b"]]
    (is (thrown? clojure.lang.ExceptionInfo (ftp/safe-name bad)) bad)))

(deftest sending-a-file
  (h/with-temp-dir
    (fn [dir]
      (let [src (h/write-file! (io/file dir "src" "hello.txt")
                               (.getBytes "hello, world" "UTF-8"))
            dst (io/file dir "dst")
            {:keys [port stop]} (ftp/start-server dst)]
        (try
          (is (= 12 (ftp/send-file "127.0.0.1" port src)))
          (is (= "hello, world" (slurp (io/file dst "hello.txt"))))
          (testing "an empty file is still a file"
            (let [empty (h/write-file! (io/file dir "src" "empty") (byte-array 0))]
              (is (= 0 (ftp/send-file "127.0.0.1" port empty)))
              (is (.exists (io/file dst "empty")))))
          (testing "every byte value survives the trip"
            (let [data (byte-array (map unchecked-byte (range 256)))
                  f (h/write-file! (io/file dir "src" "all.bin") data)]
              (is (= 256 (ftp/send-file "127.0.0.1" port f)))
              (is (= (vec data) (vec (h/read-file (io/file dst "all.bin")))))))
          (finally
            (stop)))))))

(deftest a-bad-client-does-not-stop-the-server
  (h/with-temp-dir
    (fn [dir]
      (let [dst (io/file dir "dst")
            {:keys [port stop]} (ftp/start-server dst)]
        (try
          (testing "a path instead of a name gets the client hung up on"
            (with-open [sock (Socket. "127.0.0.1" (int port))]
              (.setSoTimeout sock 5000)
              (let [out (ftp/data-out sock)]
                (ftp/write-str out "../escaped.txt")
                (ftp/write-frame out (.getBytes "evil" "UTF-8"))
                (.flush out)
                (is (thrown? EOFException (.readLong (ftp/data-in sock))))))
            (is (not (.exists (io/file dir "escaped.txt")))))
          (testing "the next client is served as usual"
            (let [f (h/write-file! (io/file dir "src" "fine.txt") (.getBytes "fine"))]
              (is (= 4 (ftp/send-file "127.0.0.1" port f)))))
          (finally
            (stop)))))))

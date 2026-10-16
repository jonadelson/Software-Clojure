(ns sdx.viewer.ansi-test
  "The ANSI screen cannot be driven by a test, but the escape sequences
   it builds, the keys it decodes, and the bytes it writes can all be
   checked with in-memory streams."
  (:require [clojure.test :refer [deftest is testing]]
            [sdx.viewer.ansi :as ansi]
            [sdx.viewer.main]
            [sdx.viewer.screen :as scr]))

(deftest escape-sequences
  (is (= "\u001b[1;1H" (ansi/goto 0 0)))
  (is (= "\u001b[3;11H" (ansi/goto 2 10)))
  (is (= "\u001b[2J" ansi/clear-screen)))

(defn- byte-source
  "A next-byte function that hands out `bytes` in turn and then -1."
  [bytes]
  (let [pending (atom (seq bytes))]
    (fn []
      (let [b (first @pending)]
        (swap! pending rest)
        (or b -1)))))

(defn- decode
  "Decode the key whose bytes are the chars of `s`."
  [s]
  (let [[b & more] (map int s)]
    (ansi/decode-key b (byte-source more))))

(deftest decoding-keys
  (is (= "j" (decode "j")))
  (is (= "Q" (decode "Q")))
  (is (= :up (decode "\u001b[A")))
  (is (= :down (decode "\u001b[B")))
  (is (= :right (decode "\u001b[C")))
  (is (= :left (decode "\u001b[D")))
  (is (= :up (decode "\u001bOA")))
  (is (= :page-up (decode "\u001b[5~")))
  (is (= :page-down (decode "\u001b[6~")))
  (is (= :home (decode "\u001b[H")))
  (is (= :end (decode "\u001b[4~")))
  (is (= :enter (decode "\r")))
  (is (= :enter (decode "\n")))
  (is (= :tab (decode "\t")))
  (is (= :backspace (decode "\u007f")))
  (is (= :ctrl-c (decode "\u0003")))
  (is (= :ctrl-z (decode "\u001a")))
  (is (= :ctrl-q (decode "\u0011")))
  (testing "a bare escape with nothing after it"
    (is (= :escape (decode "\u001b"))))
  (testing "sequences we do not know are reported, not mistaken for text"
    (is (= :unknown (decode "\u001b[99~")))
    (is (= :unknown (decode "\u001b["))))
  (testing "end of input"
    (is (nil? (ansi/decode-key -1 (byte-source []))))))

(deftest terminal-writes-escape-codes
  (let [out (java.io.StringWriter.)
        in (java.io.ByteArrayInputStream. (.getBytes "j\u001b[Bq" "UTF-8"))
        term (ansi/->Terminal out in 3 10)]
    (is (= [3 10] (scr/dimensions term)))
    (scr/erase term)
    (scr/write-at term 1 2 "hello world")
    (scr/write-at term 5 0 "off screen")
    (scr/move-cursor term 1 4)
    (is (= (str "\u001b[2J" "\u001b[2;3Hhello wo" "\u001b[2;5H") (str out)))
    (testing "keys come from the input stream"
      (is (= "j" (scr/read-key term)))
      (is (= :down (scr/read-key term)))
      (is (= "q" (scr/read-key term)))
      (is (nil? (scr/read-key term))))))

(deftest main-without-a-file-prints-usage
  (let [err (java.io.StringWriter.)]
    (binding [*err* err]
      (sdx.viewer.main/-main))
    (is (re-find #"usage" (str err)))))

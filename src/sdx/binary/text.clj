(ns sdx.binary.text
  "Text as bytes: character encodings, and a checksum for noticing when
   bytes have been damaged on their way from one program to another."
  (:require [sdx.binary.bits :as bits]))

;; ---------------------------------------------------------------------
;; Encodings

(defn encode
  "The bytes of `s` in the named encoding, such as \"UTF-8\" or \"UTF-16\"."
  [s encoding]
  (.getBytes ^String s ^String encoding))

(defn decode
  "The string whose bytes `bs` are, read in the named encoding."
  [bs encoding]
  (String. ^bytes bs ^String encoding))

;; ---------------------------------------------------------------------
;; Checksums

(defn checksum
  "The simplest checksum there is: the sum of all the bytes, kept to one
   byte. Reordering the bytes does not change it."
  [bs]
  (reduce (fn [sum b] (bit-and (+ sum (bits/unsigned b)) 0xFF)) 0 bs))

(defn fletcher16
  "Fletcher's checksum: two running sums, the second of which depends on
   the order of the bytes, packed into one 16-bit number."
  [bs]
  (let [[a b] (reduce (fn [[a b] x]
                        (let [a (mod (+ a (bits/unsigned x)) 255)]
                          [a (mod (+ b a) 255)]))
                      [0 0]
                      bs)]
    (bit-or (bit-shift-left b 8) a)))

(defn with-checksum
  "The `payload` followed by its Fletcher checksum as two big-endian bytes."
  [^bytes payload]
  (byte-array (concat payload (bits/int->bytes (fletcher16 payload) 2 :big))))

(defn verify
  "The payload inside a message built by `with-checksum`, provided its
   checksum still matches; otherwise an exception."
  [^bytes message]
  (when (< (alength message) 2)
    (throw (ex-info "message too short to carry a checksum" {:length (alength message)})))
  (let [n (- (alength message) 2)
        payload (java.util.Arrays/copyOfRange message 0 n)
        trailer (java.util.Arrays/copyOfRange message n (+ n 2))
        stored (bit-and 0xFFFF (bits/bytes->int trailer :big))
        actual (fletcher16 payload)]
    (if (= stored actual)
      payload
      (throw (ex-info "checksum mismatch" {:stored stored :actual actual})))))

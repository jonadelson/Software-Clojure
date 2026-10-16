(ns sdx.binary.bits
  "Bits, bytes and two's complement: the arithmetic underneath every
   binary format, done by hand with Clojure's bit functions so that
   nothing is hidden.")

(defn bits
  "The low `width` bits of `n` as a string of 0s and 1s, most
   significant first."
  [n width]
  (apply str (map (fn [i] (if (bit-test n i) \1 \0))
                  (range (dec width) -1 -1))))

(defn unbits
  "The non-negative number whose binary digits are the string `s`."
  [s]
  (reduce (fn [n c] (+ (* 2 n) (case c \0 0 \1 1))) 0 s))

(defn unsigned
  "The number 0..255 that byte `b` holds when its bits are read as an
   unsigned quantity."
  [b]
  (bit-and b 0xFF))

(defn to-byte
  "The byte (-128..127) whose eight bits are the low eight bits of `n`:
   the two's complement reading of those bits."
  [n]
  (unchecked-byte n))

(defn hex
  "The bytes of `bs` as two-digit hexadecimal numbers separated by spaces."
  [bs]
  (clojure.string/join " " (map #(format "%02x" (unsigned %)) bs)))

(defn int->bytes
  "The low `width` bytes of `n` as a byte array: most significant byte
   first for `:big`, least significant first for `:little`."
  [n width endian]
  (let [shifts (range 0 (* 8 width) 8)]
    (byte-array (map (fn [shift] (unchecked-byte (bit-shift-right n shift)))
                     (if (= endian :big) (reverse shifts) shifts)))))

(defn bytes->int
  "The two's complement integer stored in `bs` (one to eight bytes) in
   `endian` order."
  [bs endian]
  (let [ordered (if (= endian :big) (seq bs) (reverse bs))
        width (count bs)
        magnitude (reduce (fn [n b] (bit-or (bit-shift-left n 8) (unsigned b)))
                          0
                          ordered)]
    (if (and (< width 8) (bit-test magnitude (dec (* 8 width))))
      (- magnitude (bit-shift-left 1 (* 8 width)))
      magnitude)))

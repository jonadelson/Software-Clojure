---
title: "Binary Data"
nav_order: 17
lede: "Bits, bytes and two's complement, a tiny language for packing values into byte arrays, and a checksum to notice damage."
---

Most of this book has dealt in text, because text is what people read.
Machines do not read text. A file on disk, a packet on the wire, a
block in a database, and the memory of a running program are all
sequences of bytes, and a byte is eight bits that mean nothing until
someone decides what they mean. The number 200, the letter `È`, the
boolean flags of a file permission, and a quarter of a floating point
number can all be the same eight bits. Learning to work with binary
data is learning to keep the bits and their meaning apart.

This chapter builds up from the bottom. We start with the bit
operations Clojure provides and use them to see how negative numbers
are stored. We assemble bytes into integers by hand, in both byte
orders, and then let `java.nio.ByteBuffer` do it for us. We design a
tiny language for describing the layout of a record, so that
`(pack "<2i s f" [1 2 "hi" 0.5])` produces eighteen bytes and `unpack`
gets the values back. Then we look at text itself as bytes, where the
same string has three different lengths in three encodings, and finish
with a checksum that lets a reader notice when bytes were damaged on
the way. The tests compare byte arrays by turning them into vectors,
which exposes a trap of its own.

## Bits

Clojure's integers are 64-bit, and a handful of functions treat them
as rows of bits rather than as numbers. `bit-and`, `bit-or` and
`bit-xor` combine two rows bit by bit; `bit-shift-left` and
`bit-shift-right` slide a row sideways; `bit-test` asks whether one
bit is set.

```clojure
(bit-and 12 10)         ;; => 8     1100 and 1010 is 1000
(bit-or 12 10)          ;; => 14    1100 or  1010 is 1110
(bit-xor 12 10)         ;; => 6     1100 xor 1010 is 0110
(bit-shift-left 1 4)    ;; => 16    one, moved four places left
(bit-shift-right 200 4) ;; => 12    200 is 11001000; drop four bits
(bit-test 5 0)          ;; => true  101 has its lowest bit set
(bit-test 5 1)          ;; => false
```

It is hard to see what these do in decimal, so the first thing we
build is a way to look at the bits:

```clojure
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
```

`bits` walks the bit positions from the highest down to zero and asks
`bit-test` about each. `unbits` goes the other way the way you would
read a decimal number aloud: for each digit, multiply what you have by
the base and add the digit.

```clojure
(bits 12 4)          ;; => "1100"
(bits 5 8)           ;; => "00000101"
(bits 200 8)         ;; => "11001000"
(unbits "11001000")  ;; => 200
```

## Two's complement

What about negative numbers?

```clojure
(bits -1 8)    ;; => "11111111"
(bits -56 8)   ;; => "11001000"
(bits 200 8)   ;; => "11001000"
```

The bits of -56 and the bits of 200 are the same. This is **two's
complement**, the representation every modern processor uses for
signed integers: a number with its top bit set is read as a negative
number, specifically as the unsigned value minus 2 to the width. In
eight bits, 200 - 256 = -56. The arrangement has the useful property
that addition works the same way for signed and unsigned numbers, so
the hardware does not need to know which it is dealing with; only the
program does.

Java, and so Clojure, decided that a `byte` is signed. There is no
unsigned byte type. That decision shows up constantly when working
with binary data, so we give the two readings names:

```clojure
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
```

`(bit-and b 0xFF)` keeps the low eight bits and clears the rest; since
a negative byte widened to a long has all its high bits set, this is
exactly what turns -56 back into 200. `unchecked-byte` goes the other
way and is one of the few places where "unchecked" is what we want:
the ordinary `byte` function refuses a value outside -128..127, and we
are deliberately asking for the bits of 200 reinterpreted.

```clojure
(to-byte 200)              ;; => -56
(unsigned -56)             ;; => 200
(byte 200)                 ;; throws: Value out of range for byte: 200
(vec (byte-array [1 2 200]))  ;; => [1 2 -56]
```

That last line is the trap the tests have to live with. `byte-array`
builds a Java array of bytes from a sequence of numbers, and `vec`
turns one back into a vector so that it can be compared with `=` (two
arrays are never `=`, even with the same contents). But the vector
holds signed bytes, so a test that expects the byte 200 must write
`-56`. Every expected value in this chapter's tests is written that
way, and getting used to it is part of learning the material.

A helper for looking at arrays in hexadecimal, two digits per byte,
rounds out the toolkit:

```clojure
(defn hex
  "The bytes of `bs` as two-digit hexadecimal numbers separated by spaces."
  [bs]
  (clojure.string/join " " (map #(format "%02x" (unsigned %)) bs)))
```

The tests in `test/sdx/binary/bits_test.clj` pin all of this down,
including the fact that 200 and -56 print as the same bits:

```clojure
(deftest negative-numbers-are-twos-complement
  (is (= "11111111" (b/bits -1 8)))
  (is (= "11111110" (b/bits -2 8)))
  (is (= "10000000" (b/bits -128 8)))
  (testing "the same eight bits read two ways"
    (is (= -56 (b/to-byte 200)))
    (is (= 200 (b/unsigned (b/to-byte 200))))
    (is (= (b/bits 200 8) (b/bits (b/to-byte 200) 8))))
  (testing "to-byte keeps only the low eight bits"
    (is (= 127 (b/to-byte 127)))
    (is (= -128 (b/to-byte 128)))
    (is (= 1 (b/to-byte 257)))))
```

## Several bytes make a number

A byte holds 256 values. Anything bigger is spread across several
bytes, and immediately there is a choice: does the most significant
byte come first (**big-endian**, the order we write numbers in) or
last (**little-endian**, the order most processors use internally)?
Both are in daily use, so any binary format has to say which it means,
and any code that reads one has to honor it.

```clojure
(defn int->bytes
  "The low `width` bytes of `n` as a byte array: most significant byte
   first for `:big`, least significant first for `:little`."
  [n width endian]
  (let [shifts (range 0 (* 8 width) 8)]
    (byte-array (map (fn [shift] (unchecked-byte (bit-shift-right n shift)))
                     (if (= endian :big) (reverse shifts) shifts)))))
```

Shifting `n` right by 0, 8, 16 and 24 bits and taking the low byte
each time peels the bytes off least significant first, which is the
little-endian order; reversing the shifts gives big-endian. The number
456 is `00000001 11001000` in sixteen bits, and it comes out as:

```clojure
(hex (int->bytes 456 4 :big))      ;; => "00 00 01 c8"
(hex (int->bytes 456 4 :little))   ;; => "c8 01 00 00"
(vec (int->bytes 456 4 :big))      ;; => [0 0 1 -56]
```

Reading is the reverse: fold the bytes in, shifting what we have left
by eight bits before each one. One wrinkle is that the result must be
sign-extended. If the top bit of the top byte is set, the bytes
represented a negative number, and we subtract 2 to the width to get
it, exactly as in the eight-bit case.

```clojure
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
```

```clojure
(bytes->int (byte-array [-1 -2]) :big)      ;; => -2
(bytes->int (byte-array [-1 -2]) :little)   ;; => -257
(bytes->int (byte-array [0 -56]) :big)      ;; => 200
(bytes->int (byte-array [-56]) :big)        ;; => -56
```

The same two bytes are -2 or -257 depending on the order you read them
in. Nothing about the bytes tells you which is right; that is why
formats write it down.

Having done this by hand, we can check our work against the JDK.
`java.nio.ByteBuffer` is a byte array with a position, a byte order
and typed reads and writes, and it agrees with us:

```clojure
(deftest agrees-with-the-jdk
  (let [n 123456789
        jdk (.array (.putInt (ByteBuffer/allocate 4) n))]
    (is (= (vec jdk) (vec (b/int->bytes n 4 :big))))
    (is (= n (.getInt (ByteBuffer/wrap (b/int->bytes n 4 :big)))))))
```

The remaining tests round-trip values at every width and both byte
orders, including the extremes (`Long/MIN_VALUE` in eight bytes,
-128 in one), because the edges are where sign extension goes wrong.

## A language for layouts

A record in a binary file is several values laid end to end: an id,
a length, a name, a reading. Writing a function for each record type
would be tedious and error-prone, so instead we describe a layout with
a string and let one pair of functions, `pack` and `unpack`, interpret
it. A **format string** is a tiny language: an optional byte-order
character (`<` for little-endian, `>` for big-endian, which is the
default) followed by items, each of which is a code with an optional
repeat count:

| code | meaning |
|------|---------|
| `b` | one signed byte |
| `h` | 16-bit signed integer |
| `i` | 32-bit signed integer |
| `f` | 32-bit floating point number |
| `s` | string: 32-bit byte count, then its UTF-8 bytes |

So `"<2i s f"` is two little-endian ints, a string, and a float. Like
the glob patterns of [Chapter 4](../04-glob/), the language is tiny
enough to parse with a regular expression and a loop:

```clojure
(def ^:private item-pattern #"^(\d*)([bhifs])")

(defn parse-format
  "The byte :order and the :items of `fmt`, each item a map with a
   :code character and a :count. Spaces in the format are ignored."
  [fmt]
  (let [[order body] (case (first fmt)
                       \< [:little (subs fmt 1)]
                       \> [:big (subs fmt 1)]
                       [:big fmt])]
    (loop [remaining (str/replace body #"\s" "")
           items []]
      (if (empty? remaining)
        {:order order :items items}
        (if-let [[whole digits code] (re-find item-pattern remaining)]
          (recur (subs remaining (count whole))
                 (conj items {:code (first code)
                              :count (if (empty? digits) 1 (parse-long digits))}))
          (throw (ex-info (str "bad format: " fmt) {:format fmt :at remaining})))))))
```

The `^` in the pattern anchors it to the start of the remaining text,
so `re-find` either matches the next item or returns `nil`, and `nil`
means the format is malformed at exactly that point, which the error
reports. The groups give the digits (possibly empty) and the code.

```clojure
(parse-format "<2i s f")
;; => {:order :little,
;;     :items [{:code \i, :count 2} {:code \s, :count 1} {:code \f, :count 1}]}
```

Two small helpers turn items into something the packer can walk, one
code per value, and map our keyword to the JDK's byte-order constant:

```clojure
(defn- codes
  "The format's items expanded into one code per value, so that
   \"2i s\" becomes (\\i \\i \\s)."
  [items]
  (mapcat (fn [{:keys [code count]}] (repeat count code)) items))

(defn- byte-order
  [order]
  (if (= order :little) ByteOrder/LITTLE_ENDIAN ByteOrder/BIG_ENDIAN))
```

### Packing

`ByteBuffer` has to be allocated with a size, and a format that
contains strings has no fixed size, so packing is two passes. The
first works out how many bytes each value will take; the second writes
them.

```clojure
(defn- size-of
  "How many bytes `value` occupies when written with `code`."
  [code value]
  (case code
    \b 1
    \h 2
    \i 4
    \f 4
    \s (+ 4 (alength ^bytes value))))

(defn- put!
  "Write `value` into `buf` according to `code`."
  [^ByteBuffer buf code value]
  (case code
    \b (.put buf (byte value))
    \h (.putShort buf (short value))
    \i (.putInt buf (int value))
    \f (.putFloat buf (float value))
    \s (do (.putInt buf (alength ^bytes value))
           (.put buf ^bytes value))))
```

The `^ByteBuffer` and `^bytes` are **type hints**. Clojure is
dynamically typed, so a call like `(.putInt buf n)` would ordinarily be
resolved by reflection at run time, looking up the method by name each
call. A hint tells the compiler the type so that it can compile a
direct call. Hints never change what a program means, only how fast it
runs, and `alength` on an untyped array would be reflective and slow
without one.

Note that `put!` uses the *checked* conversions `byte`, `short` and
`int`. A value that does not fit the field it is being written to is a
bug, and `(byte 200)` throwing "Value out of range" at pack time is
far better than silently storing -56 and finding out later. The
`unchecked-byte` of the previous section was for reinterpreting bits
we already had; this is for values that are supposed to fit.

```clojure
(defn pack
  "Lay `values` out as a byte array according to `fmt`."
  [fmt values]
  (let [{:keys [order items]} (parse-format fmt)
        cs (codes items)]
    (when (not= (count cs) (count values))
      (throw (ex-info (str "format " (pr-str fmt) " takes " (count cs)
                           " values but " (count values) " were given")
                      {:format fmt :values values})))
    (let [values (map (fn [code v] (if (= code \s) (.getBytes ^String v "UTF-8") v))
                      cs values)
          size (reduce + (map size-of cs values))
          buf (doto (ByteBuffer/allocate size) (.order (byte-order order)))]
      (doseq [[code v] (map vector cs values)]
        (put! buf code v))
      (.array buf))))
```

Strings are converted to their UTF-8 bytes first, so that `size-of`
can count bytes rather than characters (which, as the next section
shows, are not the same thing). `doto` is a convenience for Java
objects that are configured by calling methods on them: it evaluates
its first argument, calls each of the following forms on it, and
returns the object. The buffer's `.order` is set once and every
multi-byte write after that respects it, which is how one line
implements endianness for the whole packer.

```clojure
(vec (pack "i" [1]))          ;; => [0 0 0 1]
(vec (pack "<i" [1]))         ;; => [1 0 0 0]
(vec (pack "h" [-2]))         ;; => [-1 -2]
(vec (pack "s" ["hi"]))       ;; => [0 0 0 2 104 105]
(vec (pack "2h i" [1 2 3]))   ;; => [0 1 0 2 0 0 0 3]
(hex (pack "<2i s f" [1 2 "hi" 0.5]))
;; => "01 00 00 00 02 00 00 00 02 00 00 00 68 69 00 00 00 3f"
```

### Unpacking

Unpacking wraps the array in a buffer, sets the same byte order, and
reads one value per code:

```clojure
(defn- get!
  "Read one value from `buf` according to `code`."
  [^ByteBuffer buf code]
  (case code
    \b (long (.get buf))
    \h (long (.getShort buf))
    \i (long (.getInt buf))
    \f (double (.getFloat buf))
    \s (let [n (.getInt buf)
             arr (byte-array n)]
         (.get buf arr)
         (String. arr "UTF-8"))))

(defn unpack
  "The values that `pack` wrote into `bs` with the same `fmt`, as a vector."
  [fmt ^bytes bs]
  (let [{:keys [order items]} (parse-format fmt)
        buf (doto (ByteBuffer/wrap bs) (.order (byte-order order)))
        values (mapv (fn [code] (get! buf code)) (codes items))]
    (when (.hasRemaining buf)
      (throw (ex-info (str (.remaining buf) " bytes left over after unpacking")
                      {:format fmt :remaining (.remaining buf)})))
    values))
```

Every integer is widened to a `long` and every float to a `double` on
the way out, so that the results are ordinary Clojure numbers and
`(= values (unpack fmt (pack fmt values)))` holds without any fuss
about boxed types. The check at the end catches the most common
mistake with binary data, a format that does not match the bytes: if
the format is shorter than the data, bytes are left over, and if it is
longer, `ByteBuffer` throws `BufferUnderflowException` when it runs
out.

Floats deserve a closer look. A 32-bit float is stored as a sign bit,
eight bits of exponent, and twenty-three bits of fraction, and
`Float/floatToIntBits` lets us see that layout directly:

```clojure
(vec (pack "f" [1.0]))                       ;; => [63 -128 0 0]
(bits (Float/floatToIntBits 1.0) 32)         ;; => "00111111100000000000000000000000"
(unpack "f" (pack "f" [0.1]))                ;; => [0.10000000149011612]
```

The number 1.0 has a zero fraction and an exponent of 127. The number
0.1 cannot be represented exactly in binary at all, and in 32 bits the
nearest value is wrong in the eighth decimal place. The test
`floats-are-only-32-bits` checks that the round trip is close but not
equal, which is the honest thing to assert.

### Testing the language

The tests in `test/sdx/binary/pack_test.clj` are a table of formats
and values that must round-trip, plus the ways a caller can get it
wrong:

```clojure
(deftest round-trips
  (doseq [[fmt values] [["i" [42]]
                        ["<i" [-42]]
                        ["b h i" [-1 -300 -70000]]
                        ["3i" [1 2 3]]
                        ["s" ["héllo, wörld"]]
                        ["s s" ["" "two\nlines"]]
                        ["f" [1.5]]
                        ["<2i s f b h" [1 2 "mixed" 0.25 7 -7]]]]
    (testing fmt
      (is (= values (p/unpack fmt (p/pack fmt values)))))))

(deftest byte-order-changes-the-meaning
  (is (= [16777216] (p/unpack "<i" (p/pack ">i" [1]))))
  (is (= [256] (p/unpack "<h" (p/pack ">h" [1])))))
```

The second test is the whole endianness lesson in two lines: the
bytes `00 00 00 01` are 1 read one way and 2 to the 24th read the
other.

## Text is bytes too

The `s` code converted strings with `(.getBytes s "UTF-8")`, and the
word "UTF-8" is doing a lot of work. A string is a sequence of
characters; to store or send it, each character has to become bytes,
and a **character encoding** is the rule for doing that. There are
many, and the same text has different bytes, and a different number of
bytes, in each.

```clojure
(defn encode
  "The bytes of `s` in the named encoding, such as \"UTF-8\" or \"UTF-16\"."
  [s encoding]
  (.getBytes ^String s ^String encoding))

(defn decode
  "The string whose bytes `bs` are, read in the named encoding."
  [bs encoding]
  (String. ^bytes bs ^String encoding))
```

```clojure
(vec (encode "hi" "UTF-8"))         ;; => [104 105]
(vec (encode "hi" "UTF-16BE"))      ;; => [0 104 0 105]
(vec (encode "hi" "UTF-16"))        ;; => [-2 -1 0 104 0 105]
(vec (encode "é" "UTF-8"))          ;; => [-61 -87]
(vec (encode "é" "UTF-16BE"))       ;; => [0 -23]
(vec (encode "é" "ISO-8859-1"))     ;; => [-23]
```

UTF-8 uses one byte for the characters of ASCII and two, three or four
for everything else, so "hi" is two bytes and "é" is two. UTF-16 uses
two bytes for almost everything, so "hi" doubles in size and "é" stays
the same. And plain "UTF-16", as opposed to "UTF-16BE", begins with the
bytes `FE FF`: a **byte order mark**, which is the encoding's way of
writing down the endianness question from earlier so that a reader can
tell which order the two-byte units are in. The three bytes for "é"
are not even different readings of the same bits; they are three
different conventions, and bytes written under one and read under
another produce wrong text rather than an error:

```clojure
(deftest decoding-must-use-the-same-encoding
  (is (= "héllo" (t/decode (t/encode "héllo" "UTF-8") "UTF-8")))
  (is (= "héllo" (t/decode (t/encode "héllo" "UTF-16") "UTF-16")))
  (testing "the wrong encoding gives the wrong text, not an error"
    (is (= "hÃ©llo" (t/decode (t/encode "héllo" "UTF-8") "ISO-8859-1")))))
```

This is why a binary format must say what encoding its strings use,
and why `pack` writes a *byte* count before a string rather than a
character count: the reader needs to know how many bytes to take
before it can decode anything.

## Noticing damage

Bytes get damaged. Disks fail, cables are noisy, programs have bugs,
and a format that cannot tell a damaged record from a good one will
cheerfully decode garbage into plausible-looking values. The cure is
redundancy: send a little extra information computed from the data, so
that a reader can recompute it and compare. The simplest version adds
the bytes up:

```clojure
(defn checksum
  "The simplest checksum there is: the sum of all the bytes, kept to one
   byte. Reordering the bytes does not change it."
  [bs]
  (reduce (fn [sum b] (bit-and (+ sum (bits/unsigned b)) 0xFF)) 0 bs))
```

The docstring gives away the weakness. Addition is commutative, so two
bytes that swap places, a common kind of corruption, leave the sum
unchanged:

```clojure
(checksum (byte-array [1 2]))   ;; => 3
(checksum (byte-array [2 1]))   ;; => 3
```

Fletcher's checksum fixes this with a second running sum that
accumulates the first one. Because the second sum adds the running
total after every byte, a byte's contribution depends on where it is,
and reordering changes the result:

```clojure
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
```

```clojure
(fletcher16 (byte-array [1 2]))   ;; => 1027
(fletcher16 (byte-array [2 1]))   ;; => 1283
(format "%04x" (fletcher16 (encode "abcde" "UTF-8")))   ;; => "c8f0"
```

The `reduce` carries a pair `[a b]` as its accumulator, which is the
idiomatic way to run two sums in one pass without any mutable state.
The two eight-bit sums are packed into one sixteen-bit number with the
shift and or from the start of the chapter.

With a checksum function in hand, a **message** is a payload followed
by its checksum, and reading one means recomputing the checksum and
refusing the payload if it does not match:

```clojure
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
```

`with-checksum` reuses `int->bytes` from the start of the chapter to
write the sixteen-bit checksum, and `verify` reuses `bytes->int` to
read it, masking with `0xFFFF` because the checksum is an unsigned
quantity and `bytes->int` would otherwise sign-extend a value with its
top bit set.

```clojure
(hex (with-checksum (encode "hello" "UTF-8")))   ;; => "68 65 6c 6c 6f 2d 16"
```

The tests damage a message in the two ways a checksum should catch,
and check that a message built from `pack` survives the trip through
`with-checksum` and `verify` intact:

```clojure
(deftest messages-carry-their-checksum
  (let [payload (t/encode "hello" "UTF-8")
        message (t/with-checksum payload)]
    (is (= (+ 2 (alength payload)) (alength message)))
    (is (= (vec payload) (vec (t/verify message))))
    (testing "a damaged byte is noticed"
      (let [damaged (aclone message)]
        (aset-byte damaged 1 (byte 0))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"checksum mismatch"
                              (t/verify damaged)))))
    (testing "two swapped bytes are noticed"
      (let [swapped (aclone message)]
        (aset-byte swapped 0 (aget message 1))
        (aset-byte swapped 1 (aget message 0))
        (is (thrown? clojure.lang.ExceptionInfo (t/verify swapped)))))
    (testing "a message too short to carry a checksum"
      (is (thrown? clojure.lang.ExceptionInfo (t/verify (byte-array [1])))))))

(deftest a-packed-record-can-be-checksummed
  (let [fmt "i s h"
        values [7 "temperature" -3]
        message (t/with-checksum (p/pack fmt values))]
    (is (= values (p/unpack fmt (t/verify message))))))
```

A sixteen-bit checksum lets one damaged message in 65,536 slip
through, which is fine for noticing a flipped bit and not fine for
anything adversarial. Stronger checks (cyclic redundancy checks, which
the JDK provides as `java.util.zip.CRC32`, and cryptographic hashes
like the SHA-256 of [Chapter 3](../03-dupes/)) follow the same
pattern: compute something from the bytes, send it along, recompute
and compare. The design is the same; only the arithmetic gets harder.

## Summary

- A byte is eight bits with no meaning of its own. Two's complement
  gives the same bits a signed and an unsigned reading, and Clojure's
  bytes are always signed, so `(vec bytes)` shows 200 as -56.
- `bit-and`, `bit-or`, `bit-shift-left`, `bit-shift-right` and
  `bit-test` are enough to take numbers apart into bytes and put them
  back together, in either byte order.
- Big-endian and little-endian are both in use. The bytes do not say
  which they are; the format must.
- A format string is a small language for describing a layout, and
  interpreting it with one `pack` and one `unpack` replaces a pair of
  functions per record type.
- Packing anything variable-length takes two passes: one to find the
  size, one to write. `ByteBuffer` handles byte order and typed
  reads and writes once it is told the order.
- Use checked conversions (`byte`, `short`, `int`) for values that are
  supposed to fit and `unchecked-byte` only to reinterpret bits you
  already have.
- Text has no bytes until an encoding is chosen; UTF-8, UTF-16 and
  ISO-8859-1 give the same string different lengths and different
  bytes, and decoding with the wrong one yields wrong text, not an
  error.
- A checksum is redundancy: extra bytes computed from the data so that
  damage can be detected. A plain sum misses reordering; Fletcher's
  second sum catches it.

## Exercises

1. **Unsigned fields.** Add codes `B`, `H` and `I` for unsigned
   8-, 16- and 32-bit integers. Packing must reject values that do not
   fit, and unpacking must not sign-extend. Which of the functions in
   `sdx.binary.bits` do you reuse?

2. **Doubles and longs.** Add `d` for 64-bit floating point and `q`
   for 64-bit integers. Write a test showing that `0.1` survives a
   `d` round trip but not an `f` one.

3. **Fixed-width strings.** Add a code `4c` meaning "a string padded
   or truncated to exactly four bytes, no length prefix", as many file
   formats use for tags. What should happen to a string that is too
   long, and what should the unpacked value be for one that was
   padded?

4. **Size without packing.** Write `(size fmt values)` that returns
   the number of bytes `pack` would produce without producing them.
   Can it be done without converting the strings?

5. **Bit fields.** Write `(pack-bits [[3 5] [4 2]])` that packs several
   small numbers into one byte, where each pair is a width and a
   value, and the matching unpacker. What happens if the widths add up
   to more than eight?

6. **Hexdump.** Write a `hexdump` function that prints a byte array
   sixteen bytes per line, with the offset in hexadecimal at the left
   and the printable ASCII characters at the right, like the Unix tool.

7. **Fletcher versus sum.** Generate ten thousand random pairs of
   byte arrays that differ by one swapped pair of adjacent bytes.
   For how many does `checksum` fail to notice? For how many does
   `fletcher16`?

8. **Framing.** Combine the chapters: write `(frame fmt values)` that
   packs the values, prefixes the result with its length as a 32-bit
   integer, and appends a checksum, and `(unframe fmt bytes)` that
   reads one such message from the front of a byte array and returns
   the values and the remaining bytes, in the style of
   [Chapter 16](../16-persist/)'s `load`.

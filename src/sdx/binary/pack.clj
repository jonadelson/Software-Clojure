(ns sdx.binary.pack
  "pack and unpack: a tiny format-string language for laying values out
   in a byte array and reading them back.

   A format is an optional byte-order character, `<` for little-endian
   or `>` for big-endian (the default), followed by items. Each item is
   a code with an optional repeat count in front of it:

     b  one signed byte          h  16-bit signed integer
     i  32-bit signed integer    f  32-bit floating point
     s  string: 32-bit byte count, then its UTF-8 bytes

   So \"<2i s f\" is two little-endian ints, a string and a float."
  (:require [clojure.string :as str])
  (:import [java.nio ByteBuffer ByteOrder]))

;; ---------------------------------------------------------------------
;; The format language

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

(defn- codes
  "The format's items expanded into one code per value, so that
   \"2i s\" becomes (\\i \\i \\s)."
  [items]
  (mapcat (fn [{:keys [code count]}] (repeat count code)) items))

(defn- byte-order
  [order]
  (if (= order :little) ByteOrder/LITTLE_ENDIAN ByteOrder/BIG_ENDIAN))

;; ---------------------------------------------------------------------
;; Packing

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

;; ---------------------------------------------------------------------
;; Unpacking

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

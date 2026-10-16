(ns sdx.db.record
  "Packing a record with fixed fields into a fixed-size block of bytes
   and unpacking it again.

   The schema is data: a vector of [field type] pairs. Because every
   field has a fixed width, every record packs to the same number of
   bytes, so the position of a record in a file is a simple
   multiplication."
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

(def schema
  "The fields of a reading, in the order they are stored, and their
   types. A string type carries its width in bytes."
  [[:id :int]
   [:sensor [:str 16]]
   [:reading :double]
   [:when :long]])

(defn field-size
  "How many bytes a field of this `type` occupies."
  [type]
  (cond
    (= type :int) 4
    (= type :long) 8
    (= type :double) 8
    (and (vector? type) (= :str (first type))) (second type)
    :else (throw (ex-info (str "unknown field type " type) {:type type}))))

(def block-size
  "The size in bytes of one packed record."
  (reduce + (map (comp field-size second) schema)))

(defn- put-field!
  "Write `value` into `buf` as a field of `type`."
  [^ByteBuffer buf type value]
  (cond
    (= type :int) (.putInt buf (int value))
    (= type :long) (.putLong buf (long value))
    (= type :double) (.putDouble buf (double value))
    :else
    (let [width (second type)
          encoded (.getBytes ^String value StandardCharsets/UTF_8)
          padded (byte-array width)]
      (when (> (alength encoded) width)
        (throw (ex-info (str "string does not fit in " width " bytes: " value)
                        {:value value :width width})))
      (System/arraycopy encoded 0 padded 0 (alength encoded))
      (.put buf padded))))

(defn- get-field
  "Read the next field of `type` from `buf`."
  [^ByteBuffer buf type]
  (cond
    (= type :int) (.getInt buf)
    (= type :long) (.getLong buf)
    (= type :double) (.getDouble buf)
    :else
    (let [padded (byte-array (second type))]
      (.get buf padded)
      (let [used (count (take-while #(not (zero? %)) padded))]
        (String. padded 0 (int used) StandardCharsets/UTF_8)))))

(defn pack
  "Turn a record map into a byte array of exactly `block-size` bytes.
   Throws if a field of the schema is missing."
  ^bytes [record]
  (let [buf (ByteBuffer/allocate block-size)]
    (doseq [[field type] schema]
      (when-not (contains? record field)
        (throw (ex-info (str "record is missing " field) {:record record :field field})))
      (put-field! buf type (get record field)))
    (.array buf)))

(defn unpack
  "Turn a block of bytes produced by `pack` back into a record map."
  [^bytes block]
  (let [buf (ByteBuffer/wrap block)]
    (reduce (fn [record [field type]]
              (assoc record field (get-field buf type)))
            {}
            schema)))

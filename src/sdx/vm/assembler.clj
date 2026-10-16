(ns sdx.vm.assembler
  "Turns assembly language text into a vector of words the machine can
   run, and turns words back into text.

   The language has one item per line:

       label:            the address of the next item gets this name
       op args...        an instruction; registers are R0..R3, numbers
                         are decimal, and @name is a label's address
       .word n           one literal word
       .data name size   reserve `size` zeroed words after the code and
                         give their first address a name
       # comment         ignored to the end of the line"
  (:require [clojure.string :as str]
            [sdx.vm.isa :as isa]))

(defn- fail
  "Throw an error that names the line it came from."
  [line msg]
  (throw (ex-info (str "line " line ": " msg) {:line line})))

;; ---------------------------------------------------------------------
;; Pass one: text to items.

(defn- strip-comment [line]
  (str/trim (first (str/split line #"#" 2))))

(defn- parse-directive [n text]
  (let [[directive & args] (str/split text #"\s+")]
    (case directive
      ".word" (if-let [value (and (= 1 (count args)) (parse-long (first args)))]
                {:kind :word :value value}
                (fail n (str "expected .word <number>, got " text)))
      ".data" (let [[name size] args
                    size (and size (parse-long size))]
                (if (and name size (pos? size))
                  {:kind :data :name name :size size}
                  (fail n (str "expected .data <name> <size>, got " text))))
      (fail n (str "unknown directive " directive)))))

(defn parse-line
  "Parse line number `n` into an item map, or nil for blank lines and
   comments. Every item carries its line number for error messages."
  [n line]
  (let [text (strip-comment line)]
    (when-not (str/blank? text)
      (assoc (cond
               (str/ends-with? text ":")
               {:kind :label :name (subs text 0 (dec (count text)))}

               (str/starts-with? text ".")
               (parse-directive n text)

               :else
               (let [[op & args] (str/split text #"\s+")]
                 {:kind :instr :op (keyword op) :args (vec args)}))
             :line n))))

(defn parse
  "Parse a whole program into a vector of items."
  [text]
  (->> (str/split-lines text)
       (map-indexed (fn [i line] (parse-line (inc i) line)))
       (remove nil?)
       vec))

;; ---------------------------------------------------------------------
;; Pass two: where everything will live.

(defn- takes-a-word? [item]
  (contains? #{:instr :word} (:kind item)))

(defn label-addresses
  "A map from every label and data block name to its address.
   Instructions and .word items take one word each, in order, starting
   at 0; data blocks follow the code in the order they were declared."
  [items]
  (let [code-length (count (filter takes-a-word? items))
        define (fn [labels name addr line]
                 (if (contains? labels name)
                   (fail line (str "duplicate label " name))
                   (assoc labels name addr)))]
    (:labels
     (reduce (fn [{:keys [addr data-addr labels] :as acc}
                  {:keys [kind name size line]}]
               (case kind
                 :label (assoc acc :labels (define labels name addr line))
                 :data (assoc acc
                              :labels (define labels name data-addr line)
                              :data-addr (+ data-addr size))
                 (assoc acc :addr (inc addr))))
             {:addr 0 :data-addr code-length :labels {}}
             items))))

;; ---------------------------------------------------------------------
;; Pass three: items to words.

(defn- parse-register [line text]
  (let [[_ digits] (re-matches #"R(\d+)" text)
        r (and digits (parse-long digits))]
    (cond
      (nil? r) (fail line (str "expected a register, got " text))
      (>= r isa/num-registers) (fail line (str "no such register " text))
      :else r)))

(defn- parse-value [line labels text]
  (cond
    (str/starts-with? text "@")
    (or (get labels (subs text 1))
        (fail line (str "undefined label " text)))

    (re-matches #"\d+" text)
    (let [v (parse-long text)]
      (if (<= v isa/max-arg)
        v
        (fail line (str "value too large: " text " (limit " isa/max-arg ")"))))

    :else (fail line (str "expected a number or @label, got " text))))

(defn encode-instruction
  "Turn an instruction item into a word, looking up labels in `labels`."
  [labels {:keys [op args line]}]
  (let [spec (get isa/instructions op)]
    (when-not spec
      (fail line (str "unknown operation " (name op))))
    (when-not (= (count args) (count (:args spec)))
      (fail line (format "%s takes %d argument(s), got %d"
                         (name op) (count (:args spec)) (count args))))
    (let [[a b] (map (fn [kind text]
                       (if (= kind :reg)
                         (parse-register line text)
                         (parse-value line labels text)))
                     (:args spec)
                     args)]
      (isa/encode op (or a 0) (or b 0)))))

(defn assemble-items
  "Turn parsed items into the vector of words that makes up the program."
  [items]
  (let [labels (label-addresses items)
        code (into []
                   (keep (fn [item]
                           (case (:kind item)
                             :instr (encode-instruction labels item)
                             :word (:value item)
                             nil)))
                   items)
        data-size (transduce (comp (filter #(= :data (:kind %))) (map :size))
                             + 0 items)
        words (into code (repeat data-size 0))]
    (when (> (count words) isa/ram-size)
      (throw (ex-info (str "program needs " (count words)
                           " words but memory holds " isa/ram-size)
                      {:length (count words) :limit isa/ram-size})))
    words))

(defn assemble
  "Assemble program text into a vector of words."
  [text]
  (assemble-items (parse text)))

;; ---------------------------------------------------------------------
;; And back again.

(defn disassemble-word
  "Render one word as a line of assembly: an instruction if it decodes
   as one, otherwise a .word directive."
  [word]
  (if-let [{:keys [op a b]} (isa/decode word)]
    (str/join " " (cons (name op)
                        (map (fn [kind value]
                               (if (= kind :reg) (str "R" value) (str value)))
                             (get-in isa/instructions [op :args])
                             [a b])))
    (str ".word " word)))

(defn disassemble
  "Render every word of a program as a line of assembly text."
  [words]
  (mapv disassemble-word words))

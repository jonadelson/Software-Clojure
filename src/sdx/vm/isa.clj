(ns sdx.vm.isa
  "The instruction set: which operations exist, what arguments each one
   takes, and how an instruction is packed into a single integer word.

   A word holds the operation code in its low byte and up to two
   one-byte arguments above it:

       bits 0-7    operation code
       bits 8-15   first argument
       bits 16-23  second argument

   The machine, the assembler, the disassembler, and the debugger all
   work from the table in this namespace, so adding an instruction means
   adding one row here and then teaching the machine what it does.")

(def instructions
  "Every operation the machine understands, keyed by mnemonic.
   :code is the number stored in the low byte of the word and :args
   says what kind of value goes in each argument slot: a register
   number (:reg), a constant (:val), or a memory address (:addr)."
  {:hlt {:code 1  :args []           :doc "stop the machine"}
   :ldc {:code 2  :args [:reg :val]  :doc "Ra = constant"}
   :ldr {:code 3  :args [:reg :reg]  :doc "Ra = memory[Rb]"}
   :str {:code 4  :args [:reg :reg]  :doc "memory[Rb] = Ra"}
   :add {:code 5  :args [:reg :reg]  :doc "Ra = Ra + Rb"}
   :sub {:code 6  :args [:reg :reg]  :doc "Ra = Ra - Rb"}
   :beq {:code 7  :args [:reg :addr] :doc "branch to addr if Ra is zero"}
   :bne {:code 8  :args [:reg :addr] :doc "branch to addr if Ra is not zero"}
   :prr {:code 9  :args [:reg]       :doc "print Ra"}
   :prm {:code 10 :args [:reg]       :doc "print memory[Ra]"}
   :cpy {:code 11 :args [:reg :reg]  :doc "Ra = Rb"}})

(def num-registers
  "How many general-purpose registers the machine has."
  4)

(def ram-size
  "How many words of memory the machine has."
  256)

(def max-arg
  "The largest value an argument can hold, since each one is a byte."
  255)

(def code->op
  "The reverse of `instructions`: operation code -> mnemonic."
  (into {} (map (fn [[op {:keys [code]}]] [code op])) instructions))

(defn encode
  "Pack an operation and up to two byte-sized arguments into one word.
   Missing arguments are zero."
  ([op] (encode op 0 0))
  ([op a] (encode op a 0))
  ([op a b]
   (let [code (get-in instructions [op :code])]
     (when-not code
       (throw (ex-info (str "unknown operation " op) {:op op})))
     (doseq [arg [a b]]
       (when-not (<= 0 arg max-arg)
         (throw (ex-info (str "argument out of range: " arg)
                         {:op op :arg arg :max max-arg}))))
     (bit-or code
             (bit-shift-left a 8)
             (bit-shift-left b 16)))))

(defn decode
  "Unpack a word into {:op :a :b}, or nil if its low byte is not an
   operation code."
  [word]
  (when-let [op (code->op (bit-and word 0xFF))]
    {:op op
     :a (bit-and (bit-shift-right word 8) 0xFF)
     :b (bit-and (bit-shift-right word 16) 0xFF)}))

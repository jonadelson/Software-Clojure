(ns sdx.vm.machine
  "A virtual machine with four registers and 256 words of memory.

   The whole state of the machine is one immutable map:

       {:pc      0            the address of the next instruction
        :regs    [0 0 0 0]    the registers
        :ram     [...]        256 words of memory
        :output  []           every line the program has printed
        :halted? false}

   Executing an instruction is a pure function from one state to the
   next, and running a program is a loop that calls it until the
   machine halts."
  (:require [sdx.vm.isa :as isa]))

(defn new-machine
  "A machine with `program` (a vector of words) loaded at address 0, the
   rest of memory zeroed, every register zero, and the program counter
   at 0."
  [program]
  (when (> (count program) isa/ram-size)
    (throw (ex-info (str "program too long: " (count program) " words")
                    {:length (count program) :limit isa/ram-size})))
  {:pc 0
   :regs (vec (repeat isa/num-registers 0))
   :ram (vec (concat program (repeat (- isa/ram-size (count program)) 0)))
   :output []
   :halted? false})

;; ---------------------------------------------------------------------
;; Reading and writing the parts of the state.

(defn reg
  "The value in register `r`."
  [state r]
  (nth (:regs state) r))

(defn- check-addr [addr]
  (when-not (< -1 addr isa/ram-size)
    (throw (ex-info (str "address out of range: " addr)
                    {:address addr :limit isa/ram-size})))
  addr)

(defn load-word
  "The word at memory address `addr`."
  [state addr]
  (nth (:ram state) (check-addr addr)))

(defn store-word
  "A new state with `value` at memory address `addr`."
  [state addr value]
  (assoc-in state [:ram (check-addr addr)] value))

;; ---------------------------------------------------------------------
;; What each instruction does.

(defmulti execute
  "Carry out one decoded instruction on `state` and return the new state.
   By the time this is called the program counter already points past
   the instruction, so a branch simply overwrites it."
  (fn [_state instr] (:op instr)))

(defmethod execute :hlt [state _]
  (assoc state :halted? true))

(defmethod execute :ldc [state {:keys [a b]}]
  (assoc-in state [:regs a] b))

(defmethod execute :ldr [state {:keys [a b]}]
  (assoc-in state [:regs a] (load-word state (reg state b))))

(defmethod execute :str [state {:keys [a b]}]
  (store-word state (reg state b) (reg state a)))

(defmethod execute :add [state {:keys [a b]}]
  (update-in state [:regs a] + (reg state b)))

(defmethod execute :sub [state {:keys [a b]}]
  (update-in state [:regs a] - (reg state b)))

(defmethod execute :beq [state {:keys [a b]}]
  (if (zero? (reg state a)) (assoc state :pc b) state))

(defmethod execute :bne [state {:keys [a b]}]
  (if (zero? (reg state a)) state (assoc state :pc b)))

(defmethod execute :prr [state {:keys [a]}]
  (update state :output conj (str (reg state a))))

(defmethod execute :prm [state {:keys [a]}]
  (update state :output conj (str (load-word state (reg state a)))))

(defmethod execute :cpy [state {:keys [a b]}]
  (assoc-in state [:regs a] (reg state b)))

;; ---------------------------------------------------------------------
;; The fetch-decode-execute cycle.

(defn fetch
  "The instruction at the program counter, decoded. Throws if the
   program counter has run off the end of memory or the word there is
   not an instruction."
  [state]
  (let [pc (:pc state)]
    (when-not (< -1 pc isa/ram-size)
      (throw (ex-info (str "program counter off the end of memory: " pc)
                      {:pc pc})))
    (let [word (nth (:ram state) pc)]
      (or (isa/decode word)
          (throw (ex-info (str "not an instruction: " word " at address " pc)
                          {:pc pc :word word}))))))

(defn step
  "Execute the instruction at the program counter and return the new
   state. A halted machine is returned unchanged."
  [state]
  (if (:halted? state)
    state
    (let [instr (fetch state)]
      (execute (update state :pc inc) instr))))

(defn run
  "Step the machine until it halts and return the final state."
  [state]
  (loop [state state]
    (if (:halted? state)
      state
      (recur (step state)))))

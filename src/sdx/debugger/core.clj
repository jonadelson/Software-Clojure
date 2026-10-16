(ns sdx.debugger.core
  "An interactive debugger for the virtual machine of sdx.vm.

   The debugger's state is a map that holds the machine and a little
   bookkeeping. Every command is a pure function from that state (plus
   whatever was typed after the command name) to a new state. Commands
   never print: they queue lines in the state, and the only place that
   reads or writes anything is the loop in `debug-loop`, which is given
   its input and output functions by whoever calls it."
  (:require [clojure.string :as str]
            [sdx.vm.assembler :as asm]
            [sdx.vm.isa :as isa]
            [sdx.vm.machine :as vm]))

;; ---------------------------------------------------------------------
;; State

(declare commands)

(defn new-debugger
  "Wrap a machine state for debugging. `commands` maps each command
   name to {:doc ... :fn ...}; it is kept in the state so that `help`
   can describe it and so that later stages can extend it."
  ([machine] (new-debugger machine commands))
  ([machine commands]
   {:vm machine
    :commands commands
    :shown 0        ; how many lines of program output we have displayed
    :messages []    ; lines waiting to be written
    :done? false}))

(defn say
  "Queue one or more lines to be displayed after the current command."
  [dbg & lines]
  (update dbg :messages into lines))

;; ---------------------------------------------------------------------
;; Turning parts of the machine into text.

(defn format-registers
  "All four registers on one line."
  [vm]
  (str/join "  " (map-indexed (fn [i v] (str "R" i " = " v)) (:regs vm))))

(defn format-location
  "Where the machine is: the address and text of the next instruction,
   or a note that it has halted."
  [vm]
  (let [pc (:pc vm)]
    (cond
      (:halted? vm) "halted"
      (>= pc isa/ram-size) (str "pc off the end of memory: " pc)
      :else (format "%3d: %s" pc (asm/disassemble-word (nth (:ram vm) pc))))))

(defn format-listing
  "Disassembly of addresses `from` to `to` inclusive. `mark` is called
   with each address and returns the margin to print before it."
  [vm from to mark]
  (for [addr (range from (inc to))]
    (format "%s%3d: %s" (mark addr) addr (asm/disassemble-word (nth (:ram vm) addr)))))

(defn show-output
  "Queue any lines the program has printed since the last time we looked."
  [dbg]
  (let [out (get-in dbg [:vm :output])
        fresh (subvec out (:shown dbg))]
    (-> (apply say dbg fresh)
        (assoc :shown (count out)))))

(defn after-running
  "What every command that moves the machine does afterwards: show new
   program output, then say where the machine is now."
  [dbg]
  (let [dbg (show-output dbg)]
    (say dbg (format-location (:vm dbg)))))

;; ---------------------------------------------------------------------
;; Parsing the arguments commands are given.

(defn parse-address
  "A memory address typed by the user, or nil if it is not one."
  [text]
  (when-let [n (and text (re-matches #"\d+" text) (parse-long text))]
    (when (< n isa/ram-size) n)))

(defn address-range
  "Turn the arguments of a command like `m 10 20` into [from to], using
   `default` when no arguments were given. Returns nil for bad input."
  [[from to] default]
  (cond
    (nil? from) default
    (nil? to) (when-let [a (parse-address from)] [a a])
    :else (let [a (parse-address from) b (parse-address to)]
            (when (and a b (<= a b)) [a b]))))

;; ---------------------------------------------------------------------
;; The commands.

(defn step-cmd
  "Execute one instruction."
  [dbg _args]
  (if (get-in dbg [:vm :halted?])
    (say dbg "machine is halted")
    (after-running (update dbg :vm vm/step))))

(defn continue-cmd
  "Run until the machine halts."
  [dbg _args]
  (if (get-in dbg [:vm :halted?])
    (say dbg "machine is halted")
    (after-running (update dbg :vm vm/run))))

(defn registers-cmd
  "Show the registers."
  [dbg _args]
  (say dbg (format-registers (:vm dbg))))

(defn memory-cmd
  "Show one word of memory, or a range of them."
  [dbg args]
  (if-let [[from to] (address-range args nil)]
    (apply say dbg (for [addr (range from (inc to))]
                     (format "%3d: %d" addr (nth (get-in dbg [:vm :ram]) addr))))
    (say dbg "usage: m <address> [<address>]")))

(defn pc-mark
  "The margin for a listing: an arrow at the program counter."
  [vm]
  (fn [addr] (if (= addr (:pc vm)) ">" " ")))

(defn listing-range
  "The addresses a listing command should show: the ones the user asked
   for, or a window starting at the program counter."
  [dbg args]
  (let [pc (get-in dbg [:vm :pc])]
    (address-range args [pc (min (+ pc 7) (dec isa/ram-size))])))

(defn list-cmd
  "Show the instructions around the program counter, or a range."
  [dbg args]
  (if-let [[from to] (listing-range dbg args)]
    (apply say dbg (format-listing (:vm dbg) from to (pc-mark (:vm dbg))))
    (say dbg "usage: d [<address> [<address>]]")))

(defn help-cmd
  "List the commands."
  [dbg _args]
  (apply say dbg (for [[name {:keys [doc]}] (sort (:commands dbg))]
                   (format "%-4s %s" name doc))))

(defn quit-cmd
  "Leave the debugger."
  [dbg _args]
  (assoc dbg :done? true))

(def commands
  "The command table: what each thing the user can type does."
  {"s" {:doc "step one instruction" :fn step-cmd}
   "c" {:doc "continue until the machine halts" :fn continue-cmd}
   "r" {:doc "show the registers" :fn registers-cmd}
   "m" {:doc "show memory: m <addr> or m <from> <to>" :fn memory-cmd}
   "d" {:doc "disassemble: d, d <addr>, or d <from> <to>" :fn list-cmd}
   "h" {:doc "this help" :fn help-cmd}
   "q" {:doc "quit" :fn quit-cmd}})

;; ---------------------------------------------------------------------
;; The loop.

(defn dispatch
  "Apply one line of user input to the debugger state."
  [dbg line]
  (let [[name & args] (str/split (str/trim line) #"\s+")]
    (cond
      (str/blank? line) dbg
      (contains? (:commands dbg) name) ((get-in dbg [:commands name :fn]) dbg args)
      :else (say dbg (str "unknown command " name " (h for help)")))))

(defn- flush-messages
  "Write every queued line with `write` and clear the queue."
  [dbg write]
  (doseq [line (:messages dbg)]
    (write line))
  (assoc dbg :messages []))

(defn debug-loop
  "Run a session from `dbg`, reading lines with (:read io), which returns
   nil at end of input, and writing lines with (:write io). Returns the
   final debugger state."
  [dbg {:keys [read write]}]
  (loop [dbg (flush-messages (say dbg (format-location (:vm dbg))) write)]
    (if (:done? dbg)
      dbg
      (if-let [line (read)]
        (recur (flush-messages (dispatch dbg line) write))
        dbg))))

(defn debug
  "Debug `machine` interactively using the io map's :read and :write."
  [machine io]
  (debug-loop (new-debugger machine) io))

;; ---------------------------------------------------------------------
;; Scripted sessions, for tests and for trying things out.

(defn scripted-io
  "An io map whose :read hands out `lines` one at a time and whose
   :write collects what is written. Deref (:written io) to see it."
  [lines]
  (let [remaining (atom (seq lines))
        written (atom [])]
    {:read (fn []
             (let [line (first @remaining)]
               (swap! remaining next)
               line))
     :write (fn [line] (swap! written conj line))
     :written written}))

(defn run-script
  "Debug `machine` with a vector of command lines and return everything
   the debugger wrote."
  [machine lines]
  (let [io (scripted-io lines)]
    (debug machine io)
    @(:written io)))

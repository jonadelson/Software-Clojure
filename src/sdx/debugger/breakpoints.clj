(ns sdx.debugger.breakpoints
  "The debugger from sdx.debugger.core with breakpoints added.

   A breakpoint is an address. Continuing runs the machine until it
   halts or reaches a breakpoint. Addresses can be typed as numbers or
   as the names of labels from the assembled program, so this stage
   also keeps the assembler's label table in the debugger state."
  (:require [clojure.string :as str]
            [sdx.debugger.core :as core]
            [sdx.vm.machine :as vm]))

(defn new-debugger
  "A debugger for `machine` that knows the program's `labels` and
   starts with no breakpoints."
  [machine labels]
  (-> (core/new-debugger machine)
      (assoc :breakpoints #{}
             :labels labels)))

(defn run-until
  "Step the machine until it halts or `stop?` is true of its state.
   Always takes at least one step, so that continuing from a breakpoint
   moves past it rather than stopping on it again."
  [machine stop?]
  (loop [m (vm/step machine)]
    (if (or (:halted? m) (stop? m))
      m
      (recur (vm/step m)))))

(defn resolve-address
  "A number or a label name typed by the user, as an address, or nil."
  [dbg text]
  (or (core/parse-address text)
      (get (:labels dbg) text)))

(defn break-cmd
  "Set or clear a breakpoint; with no argument, list them."
  [dbg [arg]]
  (let [bps (:breakpoints dbg)]
    (cond
      (nil? arg)
      (if (empty? bps)
        (core/say dbg "no breakpoints")
        (core/say dbg (str "breakpoints: " (str/join " " (sort bps)))))

      (resolve-address dbg arg)
      (let [addr (resolve-address dbg arg)]
        (if (contains? bps addr)
          (-> dbg (update :breakpoints disj addr)
              (core/say (str "cleared breakpoint at " addr)))
          (-> dbg (update :breakpoints conj addr)
              (core/say (str "breakpoint at " addr)))))

      :else
      (core/say dbg (str "no such address or label: " arg)))))

(defn continue-cmd
  "Run until the machine halts or reaches a breakpoint."
  [dbg _args]
  (if (get-in dbg [:vm :halted?])
    (core/say dbg "machine is halted")
    (let [bps (:breakpoints dbg)
          at-breakpoint? (fn [m] (contains? bps (:pc m)))]
      (core/after-running (update dbg :vm run-until at-breakpoint?)))))

(defn breakpoint-mark
  "The margin for a listing: a star for a breakpoint, an arrow for the
   program counter."
  [dbg]
  (let [pc (get-in dbg [:vm :pc])
        bps (:breakpoints dbg)]
    (fn [addr]
      (str (if (contains? bps addr) "*" " ")
           (if (= addr pc) ">" " ")))))

(defn list-cmd
  "Show the instructions around the program counter, marking breakpoints."
  [dbg args]
  (if-let [[from to] (core/listing-range dbg args)]
    (apply core/say dbg (core/format-listing (:vm dbg) from to (breakpoint-mark dbg)))
    (core/say dbg "usage: d [<address> [<address>]]")))

(def commands
  "The core command table with breakpoints added, and continue and
   disassemble replaced by versions that know about them."
  (assoc core/commands
         "b" {:doc "breakpoint: b <addr or label> toggles, b lists" :fn break-cmd}
         "c" {:doc "continue until a breakpoint or the machine halts" :fn continue-cmd}
         "d" {:doc "disassemble: d, d <addr>, or d <from> <to>" :fn list-cmd}))

(defn debug
  "Debug `machine` interactively, with breakpoints, using the io map's
   :read and :write. `labels` maps label names to addresses."
  [machine labels io]
  (core/debug-loop (assoc (new-debugger machine labels) :commands commands) io))

(defn run-script
  "Debug `machine` with a vector of command lines and return everything
   the debugger wrote."
  [machine labels lines]
  (let [io (core/scripted-io lines)]
    (debug machine labels io)
    @(:written io)))

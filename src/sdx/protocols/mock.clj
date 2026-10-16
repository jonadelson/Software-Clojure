(ns sdx.protocols.mock
  "Replacing a function while a test runs, and what that tells us
   about vars.

   The code under test talks to a clock and to a (pretend) network
   service. Neither is something a test should depend on, so the tests
   swap in replacements by changing what the vars point at.")

;; ---------------------------------------------------------------------
;; The code we want to test. It depends on two things we do not control.

(defn current-time-ms
  "Milliseconds since the Unix epoch, read from the system clock."
  []
  (System/currentTimeMillis))

(defn exchange-rate
  "The exchange rate from currency `from` to currency `to`.

   In a real program this would call a web service. Here it refuses to
   run at all, which makes the point that tests must never reach it."
  [from to]
  (throw (ex-info "no network access in this chapter" {:from from :to to})))

(defn convert
  "Convert `amount` of currency `from` into currency `to` at today's rate."
  [amount from to]
  (* amount (exchange-rate from to)))

(defn receipt
  "A one-line receipt showing the converted amount and when it was computed."
  [amount from to]
  (format "%.2f %s at %d" (double (convert amount from to)) to (current-time-ms)))

;; ---------------------------------------------------------------------
;; A home-made with-redefs, built from the operations vars provide.

(defn with-replacement
  "Point var `v` at `value` while calling `thunk` (a function of no
   arguments), then restore the var's old value even if `thunk` throws.
   Returns whatever `thunk` returned."
  [v value thunk]
  (let [old (deref v)]
    (alter-var-root v (constantly value))
    (try
      (thunk)
      (finally
        (alter-var-root v (constantly old))))))

;; ---------------------------------------------------------------------
;; A replacement that remembers how it was called.

(defn recording
  "Wrap `f` so that every call is remembered. Returns a map with the
   wrapper under :fn and an atom of argument vectors under :calls."
  [f]
  (let [calls (atom [])]
    {:calls calls
     :fn (fn [& args]
           (swap! calls conj (vec args))
           (apply f args))}))

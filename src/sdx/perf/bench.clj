(ns sdx.perf.bench
  "A small benchmarking harness: run a piece of code several times and
   report the median wall-clock time, with no library beyond the JDK.")

(defn median
  "The median of a non-empty collection of numbers."
  [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)
        mid (quot n 2)]
    (if (odd? n)
      (nth sorted mid)
      (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2.0))))

(def ^:private sink
  "Somewhere to put each result so that the JIT compiler cannot decide
   the work was unnecessary and skip it."
  (atom nil))

(defn- time-once
  "Call `thunk` and return how long it took, in nanoseconds."
  [thunk]
  (let [start (System/nanoTime)
        result (thunk)
        stop (System/nanoTime)]
    (reset! sink result)
    (- stop start)))

(defn- warm-up
  "Call `thunk` until both `warmup` calls and `warmup-ms` milliseconds
   have gone by. Returns how many calls that took."
  [thunk warmup warmup-ms]
  (let [deadline (+ (System/nanoTime) (* warmup-ms 1e6))]
    (loop [calls 0]
      (if (or (< calls warmup) (< (System/nanoTime) deadline))
        (do (time-once thunk)
            (recur (inc calls)))
        calls))))

(defn bench*
  "Call `thunk` without timing it until it has run `warmup` times and
   `warmup-ms` milliseconds have passed, then `repeats` times with
   timing. Returns a map with the :median-ms, :min-ms and :max-ms of the
   timed runs, the individual :times-ms, and the number of :warmup-runs."
  ([thunk] (bench* thunk {}))
  ([thunk {:keys [warmup warmup-ms repeats]
           :or {warmup 5 warmup-ms 500 repeats 11}}]
   (let [warmup-runs (warm-up thunk warmup warmup-ms)
         times (mapv (fn [_] (/ (time-once thunk) 1e6)) (range repeats))]
     {:median-ms (median times)
      :min-ms (apply min times)
      :max-ms (apply max times)
      :times-ms times
      :warmup-runs warmup-runs})))

(defmacro bench
  "Time `body` the way `bench*` times a thunk. `opts` is a map that may
   hold :warmup, :warmup-ms and :repeats; pass {} for the defaults.

     (bench {:repeats 5} (expensive-thing))"
  [opts & body]
  `(bench* (fn [] ~@body) ~opts))

(ns sdx.build.pattern
  "Stage 3: pattern rules and variables.

   A configuration now has three parts:

     {:vars     {\"CC\" \"cc\"}
      :patterns {\"%.o\" {:depends [\"%.c\"] :rule \"${CC} -c $< -o $@\"}}
      :targets  {\"app\" {:depends [\"main.o\"] :rule \"${CC} $^ -o $@\"}}}

   `expand` turns this into the plain configuration that stage 2
   understands, by instantiating a pattern for every name that has no
   explicit target and substituting variables into every rule."
  (:require [clojure.string :as str]))

(defn match-pattern
  "If `name` matches `pattern`, which must contain exactly one %, return
   the text the % stands for (the stem); otherwise return nil."
  [pattern name]
  (let [parts (str/split pattern #"%" -1)]
    (when-not (= 2 (count parts))
      (throw (ex-info (str "pattern must contain exactly one %: " pattern)
                      {:pattern pattern})))
    (let [[prefix suffix] parts]
      (when (and (str/starts-with? name prefix)
                 (str/ends-with? name suffix)
                 (> (count name) (+ (count prefix) (count suffix))))
        (subs name (count prefix) (- (count name) (count suffix)))))))

(defn instantiate
  "A concrete target made from a pattern rule by replacing % in each
   dependency with `stem`."
  [rule stem]
  (update rule :depends (fn [depends] (mapv #(str/replace % "%" stem) depends))))

(defn substitute
  "Replace ${NAME} with the variable's value and $@, $<, and $^ with the
   target, its first dependency, and all of its dependencies."
  [text target depends vars]
  (-> text
      (str/replace #"\$\{([^}]*)\}"
                   (fn [[_ name]]
                     (or (get vars name)
                         (throw (ex-info (str "unknown variable " name)
                                         {:variable name :target target})))))
      (str/replace #"\$[@<^]"
                   (fn [token]
                     (case token
                       "$@" target
                       "$<" (or (first depends) "")
                       "$^" (str/join " " depends))))))

(defn concrete-target
  "The explicit target called `name`, or the first matching pattern
   instantiated for it, or nil if `name` is a source file."
  [config name]
  (or (get-in config [:targets name])
      (some (fn [[pattern rule]]
              (when-let [stem (match-pattern pattern name)]
                (instantiate rule stem)))
            (sort-by key (:patterns config)))))

(defn expand
  "The plain configuration for building `roots`: every target reachable
   from them, with patterns instantiated and variables substituted.
   Names that match nothing become targets with no rule, which stage 2
   treats as source files."
  [config roots]
  (loop [todo (vec roots)
         done {}]
    (cond
      (empty? todo) done

      (contains? done (peek todo)) (recur (pop todo) done)

      :else
      (let [name (peek todo)
            {:keys [depends rule]} (concrete-target config name)
            target (cond-> {}
                     depends (assoc :depends depends)
                     rule (assoc :rule (substitute rule name depends (:vars config))))]
        (recur (into (pop todo) depends) (assoc done name target))))))

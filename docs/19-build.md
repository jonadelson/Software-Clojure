---
title: "A Build Manager"
nav_order: 19
lede: "A make-like tool that orders targets by their dependencies, rebuilds only what is stale, and derives rules from patterns."
---

Any program bigger than a script is assembled from pieces: source files
become object files, object files become a library, the library and a
main program become an executable, and the documentation is generated
from all of it. Each step depends on the ones before it, and after a
change, only some of the steps need to be repeated. A **build manager**
is the tool that keeps track of this. The oldest and best known is
`make`, and this chapter builds a small one in its image.

The interesting problems are all about structure rather than I/O. The
dependencies between targets form a graph, and the order in which to
build them is a property of that graph. Deciding whether a target is out
of date is a question about timestamps that can be answered without
looking at a disk. And the patterns that let one rule describe how to
compile every `.c` file are a small language that expands into the plain
rules of the first stage. We build those three things in three stages,
and keep the filesystem and the shell out of all of them until the final
section, so that nearly everything in the chapter can be tested with a
map.

## Dependencies form a graph

A build configuration is a map from a target's name to what it depends
on and the rule that makes it. We write it as EDN, Clojure's data
notation, so that reading a configuration file is a single call to
`clojure.edn/read-string` and so that the tests can write
configurations inline:

```clojure
{"app"    {:depends ["main.o" "util.o"] :rule "link app"}
 "main.o" {:depends ["main.c" "defs.h"] :rule "compile main"}
 "util.o" {:depends ["util.c" "defs.h"] :rule "compile util"}
 "main.c" {}
 "util.c" {}
 "defs.h" {}}
```

Targets with no rule, like `main.c`, are **sources**: they exist already
and nothing makes them. Everything else is a target that some rule
produces. Rules are strings for now; what to do with them comes later.

Seen as a graph, each target is a node and each entry in `:depends` is
an edge. For a build to be possible the graph must be a **directed
acyclic graph**, or DAG: the edges point from a target to what it needs,
and following them must never bring you back to where you started. If
`a` needs `b` and `b` needs `a`, there is no first thing to build. Before
anything else, we check that every edge points at a node that exists:

```clojure
(ns sdx.build.topo
  "Stage 1: a build configuration is a graph, and building is visiting
   that graph in an order where every target comes after the things it
   depends on.

   A configuration is a map from target name to a map with :depends (a
   vector of other target names) and, usually, :rule (a command to run
   to make the target)."
  (:require [clojure.string :as str]))

(defn check-config
  "Return `config` unchanged if every dependency names a target in it;
   otherwise throw."
  [config]
  (doseq [[target {:keys [depends]}] config
          dep depends]
    (when-not (contains? config dep)
      (throw (ex-info (str target " depends on unknown target " dep)
                      {:target target :missing dep}))))
  config)
```

The order in which to build the targets is a **topological sort**: an
ordering of the nodes in which every edge points backwards, so that by
the time we reach a target, everything it depends on has been visited.
We use the algorithm usually credited to Kahn. The idea is simple: a
target whose dependencies have all been built is *ready*; take a ready
target, mark it built, and see whether that makes any of the targets
depending on it ready; repeat until nothing is ready. To know which
targets depend on a given one, we first turn the edges around:

```clojure
(defn dependents
  "A map from each target to the set of targets that depend on it
   directly: the edges of the graph, pointing the other way."
  [config]
  (reduce (fn [m [target {:keys [depends]}]]
            (reduce (fn [m dep] (update m dep conj target)) m depends))
          (zipmap (keys config) (repeat #{}))
          config))
```

```clojure
(dependents config)
;; => {"app" #{}, "main.o" #{"app"}, "util.o" #{"app"},
;;     "main.c" #{"main.o"}, "util.c" #{"util.o"}, "defs.h" #{"util.o" "main.o"}}
```

The `zipmap` seeds every target with an empty set so that targets nobody
depends on (like `app`) still appear. The sort itself is a `loop` with
three pieces of state: how many unbuilt dependencies each target still
has, which targets are ready, and the order so far.

```clojure
(defn topo-sort
  "Every target in `config`, ordered so that each comes after all of its
   dependencies. When several targets are ready at once, they are taken
   in sorted order so the result is deterministic. Throws if the graph
   has a cycle."
  [config]
  (let [config (check-config config)
        downstream (dependents config)]
    (loop [waiting (into {} (for [[t {:keys [depends]}] config]
                              [t (count (set depends))]))
           ready (into (sorted-set) (for [[t n] waiting :when (zero? n)] t))
           order []]
      (cond
        (seq ready)
        (let [t (first ready)
              waiting (reduce #(update %1 %2 dec) (dissoc waiting t) (downstream t))
              freed (filter #(zero? (waiting %)) (downstream t))]
          (recur waiting (into (disj ready t) freed) (conj order t)))

        (seq waiting)
        (throw (ex-info (str "cycle among " (str/join ", " (sort (keys waiting))))
                        {:cycle (set (keys waiting))}))

        :else order))))
```

```clojure
(topo-sort config)
;; => ["defs.h" "main.c" "main.o" "util.c" "util.o" "app"]
```

Each pass takes the first ready target, removes it from `waiting`,
decrements the count of everything downstream of it, and moves whatever
reached zero into `ready`. Two details are worth dwelling on.

The first is that `ready` is a sorted set. A graph usually has many
valid topological orders (here `util.c` could go first just as well as
`defs.h`), and if we let the order depend on how a hash map happens to
iterate, the build would produce different output on different days and
a test could not say what to expect. Taking ready targets in sorted
order costs nothing and makes the function deterministic. Notice that
`main.o` comes before `util.c`: as soon as `defs.h` and `main.c` are
done, `main.o` is ready and sorts ahead of `util.c`. Any order in which
dependencies come first is correct, and this is the one we get.

The second is cycle detection, which falls out of the algorithm for
free. If the loop runs out of ready targets while some are still
waiting, every one of those is waiting for something that is itself
waiting, which can only happen if there is a cycle. The error names all
of them, which includes targets that merely depend on the cycle, since
they are just as stuck. The tests check a two-node cycle, a target that
depends on itself, and a target downstream of a cycle:

```clojure
(deftest cycles-are-detected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle among a, b"
                        (topo/topo-sort {"a" {:depends ["b"]} "b" {:depends ["a"]}})))
  (testing "a target that depends on itself is a cycle of one"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle among a"
                          (topo/topo-sort {"a" {:depends ["a"]}}))))
  (testing "targets downstream of a cycle are reported with it"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle among a, b, c"
                          (topo/topo-sort {"a" {:depends ["b"]}
                                           "b" {:depends ["a"]}
                                           "c" {:depends ["b"]}
                                           "d" {}})))))
```

A build is rarely for every target. Asking for `main.o` should not
compile `util.o`, so we need the set of targets reachable from the ones
requested, and the plan is the topological order restricted to that set:

```clojure
(defn needed
  "The set of targets that building `targets` involves: the targets
   themselves and everything they depend on, directly or indirectly."
  [config targets]
  (loop [todo (vec targets)
         seen #{}]
    (cond
      (empty? todo) seen

      (seen (peek todo)) (recur (pop todo) seen)

      (not (contains? config (peek todo)))
      (throw (ex-info (str "unknown target " (peek todo)) {:target (peek todo)}))

      :else (recur (into (pop todo) (get-in config [(peek todo) :depends]))
                   (conj seen (peek todo))))))

(defn plan
  "The targets to visit, in order, to build `targets`. With no targets,
   every target in the config is built."
  ([config] (topo-sort config))
  ([config targets]
   (if (seq targets)
     (filterv (needed config targets) (topo-sort config))
     (topo-sort config))))
```

```clojure
(needed config ["main.o"])   ;; => #{"main.c" "main.o" "defs.h"}
(plan config ["main.o"])     ;; => ["defs.h" "main.c" "main.o"]
```

`needed` is a depth-first walk using a vector as a stack: `peek` looks at
the top, `pop` removes it, and `into` pushes a target's dependencies.
Sorting the whole graph and then filtering is slightly wasteful, but it
keeps one topological sort in the program instead of two, and a sort of
a few hundred targets is not where a build spends its time.

Finally, building in stage 1 means running every rule in plan order. The
function that runs a rule is passed in as an argument, which keeps this
namespace free of any opinion about what a rule *is*:

```clojure
(defn build
  "Build `targets` by calling `(execute target rule)` for each target in
   the plan that has a rule. Returns the names visited, in order."
  [config targets execute]
  (let [order (plan config targets)]
    (doseq [target order
            :let [rule (get-in config [target :rule])]
            :when rule]
      (execute target rule))
    order))
```

In the tests, `execute` is a function that appends to an atom, so the
test can check what would have run and in what order without running
anything:

```clojure
(deftest build-runs-rules-in-order
  (let [ran (atom [])
        order (topo/build config ["app"] (fn [target rule] (swap! ran conj [target rule])))]
    (is (= 6 (count order)))
    (testing "sources have no rule and so nothing is run for them"
      (is (= [["main.o" "compile main"] ["util.o" "compile util"] ["app" "link app"]]
             @ran)))))
```

## Only rebuilding what changed

Stage 1 rebuilds everything every time, which defeats the purpose. The
rule `make` uses is this: a target is **stale** if it does not exist or
if any of its dependencies is newer than it, and a stale target is
rebuilt by running its rule, which makes it newer than all of them.

The obvious way to implement that is to ask the filesystem for
modification times. We are going to do something that looks like a
detour and turns out to be the main road: the build function takes the
timestamps as a map from name to time and returns the updated map. The
core of the build manager never touches a file. The thin layer that
reads real timestamps from a disk comes at the end of the chapter, and
it is about ten lines.

```clojure
(ns sdx.build.stamps
  "Stage 2: rebuild only what is out of date.

   The build takes a map of timestamps alongside the configuration and
   returns the updated map, so the core never looks at a filesystem. A
   name that is absent from the map does not exist yet."
  (:require [sdx.build.topo :as topo]))

(defn stale?
  "True if `target` must be rebuilt: it does not exist, one of its
   dependencies was rebuilt during this run, or one of its dependencies
   has a newer timestamp than it does."
  [config stamps rebuilt target]
  (let [mine (get stamps target)
        depends (get-in config [target :depends])]
    (boolean
     (or (nil? mine)
         (some rebuilt depends)
         (some #(> (get stamps %) mine) depends)))))
```

There are three ways to be stale rather than two, and the middle one is
the subtle part of this stage. The build stamps every target it rebuilds
with the same time, `now`. If `main.o` is rebuilt at time 20 and then we
ask whether `app` (stamped 10) is stale, the timestamp comparison says
yes. But if `app` had been missing and was rebuilt in the same run, both
would be stamped 20, and 20 is not newer than 20. More to the point, we
should not be relying on arithmetic to tell us something we already
know: if a dependency was rebuilt in this run, the target must be too.
So `build` passes along the set of targets rebuilt so far, and `stale?`
checks it directly. Sets are functions of their members in Clojure, so
`(some rebuilt depends)` is "is any dependency in the rebuilt set".

```clojure
(defn build
  "Bring `targets` (default: all of them) up to date as of time `now`.
   Returns {:stamps updated-stamps, :rebuilt [names in build order]}.
   Throws if a target that does not exist has no rule to make it."
  ([config stamps now] (build config stamps now nil))
  ([config stamps now targets]
   (reduce (fn [{:keys [stamps rebuilt] :as state} target]
             (cond
               (not (stale? config stamps (set rebuilt) target))
               state

               (get-in config [target :rule])
               (-> state
                   (assoc-in [:stamps target] now)
                   (update :rebuilt conj target))

               :else
               (throw (ex-info (str "no rule to make " target) {:target target}))))
           {:stamps stamps :rebuilt []}
           (topo/plan config targets))))

(defn commands
  "The rules to run for a `build` result, in order."
  [config {:keys [rebuilt]}]
  (mapv #(get-in config [% :rule]) rebuilt))
```

`build` is a `reduce` over the plan from stage 1. The accumulator holds
the timestamps as they are *after* each step, so that when the reduce
reaches `app`, it sees the new stamp on `main.o`. A target that is up to
date passes through untouched. A stale target with a rule gets stamped
`now` and recorded. A stale target with no rule is a source that does
not exist, and there is nothing we can do but say so, which is the
error `make` reports as "no rule to make target".

```clojure
(def sources {"main.c" 1 "util.c" 1 "defs.h" 1})

(def first-build (build config sources 10 ["app"]))
first-build
;; => {:stamps {"main.c" 1, "util.c" 1, "defs.h" 1, "main.o" 10, "util.o" 10, "app" 10},
;;     :rebuilt ["main.o" "util.o" "app"]}

(build config (:stamps first-build) 20 ["app"])
;; => {:stamps {... unchanged ...}, :rebuilt []}

(build config (assoc (:stamps first-build) "main.c" 15) 20 ["app"])
;; => {:stamps {"main.c" 15, ... "main.o" 20, "util.o" 10, "app" 20},
;;     :rebuilt ["main.o" "app"]}
```

The second call is the property that makes a build manager useful and
that deserves a name: the build is **idempotent**. Running it again when
nothing has changed does nothing and returns the same stamps. The third
call shows the other half: touching `main.c` rebuilds `main.o` and
`app`, but not `util.o`. Because the clock is an argument, the tests
can tell these stories exactly, with no waiting and no filesystem:

```clojure
(deftest touching-a-source-rebuilds-what-depends-on-it
  (let [built (:stamps (stamps/build config sources 10 ["app"]))]
    (testing "one changed .c file rebuilds its object and the app"
      (is (= ["main.o" "app"]
             (:rebuilt (stamps/build config (assoc built "main.c" 15) 20 ["app"])))))
    (testing "a changed header rebuilds everything"
      (is (= ["main.o" "util.o" "app"]
             (:rebuilt (stamps/build config (assoc built "defs.h" 15) 20 ["app"])))))
    (testing "building only an object ignores the app"
      (is (= ["util.o"]
             (:rebuilt (stamps/build config (assoc built "util.c" 15) 20 ["util.o"])))))))
```

> **Time is an input.**
> Taking `now` as a parameter instead of calling `System/currentTimeMillis`
> inside `build` is the same move as passing the clock to the archiver in
> [the file archiver chapter](../10-archive/). A function that reads the
> clock cannot be tested without either sleeping or stubbing; a function
> that is handed a number can be tested with any number you like. The
> same goes for the filesystem: a function that is handed a map of
> timestamps does not care where they came from.

## Patterns and variables

Writing a rule for every object file by hand gets old quickly, and worse,
each rule repeats the compiler's name and flags. `make` solves both
problems with **pattern rules**, where `%` stands for any stem, and
**variables**. Stage 3 adds both. A configuration grows two optional
sections:

```clojure
{:vars {"CC" "cc" "CFLAGS" "-O2"}
 :patterns {"%.o" {:depends ["%.c" "defs.h"] :rule "${CC} ${CFLAGS} -c $< -o $@"}}
 :targets {"app" {:depends ["main.o" "util.o"] :rule "${CC} $^ -o $@"}}}
```

A pattern rule says: anything matching `%.o` can be made from the
matching `%.c` plus `defs.h` by running this command. Inside a rule,
`${CC}` refers to a variable, and three **automatic variables** refer to
the target at hand: `$@` is the target's name, `$<` its first
dependency, and `$^` all of its dependencies.

The design decision for this stage is where the new features live. We
could teach `stale?` and `build` about patterns, but they are correct
and tested as they are. Instead, stage 3 is a function from a
configuration with patterns and variables to a plain configuration of
the kind stage 2 already understands. Expanding is a separate, pure step
that happens before building. The pieces are small. Matching a pattern
is splitting it at the `%`:

```clojure
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
```

```clojure
(match-pattern "%.o" "main.o")       ;; => "main"
(match-pattern "lib%.a" "libm.a")    ;; => "m"
(match-pattern "%.o" "main.c")       ;; => nil
(instantiate {:depends ["%.c" "defs.h"] :rule "r"} "main")
;; => {:depends ["main.c" "defs.h"], :rule "r"}
```

The `-1` passed to `str/split` keeps trailing empty strings, so a pattern
like `lib%` still splits into two parts. The length check insists that
the stem be at least one character, so that `.o` on its own does not
match `%.o`. Substituting variables is two regular-expression
replacements, each with a function that computes the replacement:

```clojure
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
```

```clojure
(substitute "${CC} -c $< -o $@" "main.o" ["main.c" "defs.h"] {"CC" "cc"})
;; => "cc -c main.c -o main.o"
```

When `str/replace` is given a function, it calls it with each match; for
a pattern with a group, the match is a vector of the whole text and the
groups, which is why the first function destructures `[_ name]`. An
unknown variable is an error rather than an empty string, because a
command with a silently missing compiler name is the kind of bug that
costs an afternoon.

Now the two functions that do the expansion. For a given name, the
concrete target is the explicit one if there is one, otherwise the first
pattern that matches, otherwise nothing, meaning the name is a source:

```clojure
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
```

```clojure
(expand config ["app"])
;; => {"app"    {:depends ["main.o" "util.o"], :rule "cc main.o util.o -o app"},
;;     "main.o" {:depends ["main.c" "defs.h"], :rule "cc -O2 -c main.c -o main.o"},
;;     "util.o" {:depends ["util.c" "defs.h"], :rule "cc -O2 -c util.c -o util.o"},
;;     "main.c" {}, "util.c" {}, "defs.h" {}}
```

`expand` is the same stack-driven walk as `needed` in stage 1, but
instead of collecting names it collects concrete targets, discovering
new names from each target's dependencies as it goes. Patterns are
tried in sorted order so that two patterns which both match a name
always pick the same one. The result is exactly the kind of map that
stage 1 and stage 2 take, so the whole pipeline is a composition of
pure functions:

```clojure
(stamps/commands plain (stamps/build plain {"main.c" 1 "util.c" 1 "defs.h" 1} 5 ["app"]))
;; => ["cc -O2 -c main.c -o main.o" "cc -O2 -c util.c -o util.o" "cc main.o util.o -o app"]
```

The tests in `test/sdx/build/pattern_test.clj` check each piece on its
own and then this end-to-end composition. One case worth calling out is
that an explicit target beats a pattern: if the configuration lists
`util.o` by name, the `%.o` rule is not consulted for it. That matches
`make` and, more importantly, it matches what someone writing the
configuration would expect.

## The edge: files and the shell

Everything so far has been pure. The last namespace is the thin layer
that connects it to the world, and it has two jobs: read timestamps from
the disk, and run commands with the shell.

```clojure
(defn disk-stamps
  "Modification times for every one of `names` that exists as a file
   under `dir`. Names that do not exist are left out."
  [dir names]
  (into {}
        (for [name names
              :let [file (io/file dir name)]
              :when (.exists file)]
          [name (.lastModified file)])))

(defn run-command!
  "Run `command` with the shell in `dir`, inheriting the terminal.
   Throws if it exits with a non-zero status."
  [dir command]
  (let [process (-> (ProcessBuilder. ["sh" "-c" command])
                    (.directory (io/file dir))
                    (.inheritIO)
                    (.start))
        status (.waitFor process)]
    (when-not (zero? status)
      (throw (ex-info (str "command failed with status " status ": " command)
                      {:command command :status status})))))
```

`disk-stamps` is the whole of the build manager's contact with the
filesystem: a map from name to the file's modification time in
milliseconds, leaving out names that do not exist, which is precisely
the shape `stale?` was written against. `run-command!` uses Java's
`ProcessBuilder` to run a command through `sh -c` in the configuration
file's directory, with the command's output going to our terminal, and
turns a non-zero exit status into an exception so that a failing step
stops the build. (This assumes a Unix-like shell, which is the only
platform-specific line in the chapter.)

The entry point ties them together. By default it prints the commands it
would run, which is the `make -n` dry run and the easiest way to see
what a configuration means; with `--run` it executes them.

```clojure
(defn commands-for
  "The commands needed to bring `targets` in the configuration at
   `config-path` up to date, judging by the files next to it."
  [config-path targets]
  (let [config (edn/read-string (slurp config-path))
        dir (.getParentFile (.getAbsoluteFile (io/file config-path)))
        roots (or (seq targets) (keys (:targets config)))
        plain (pattern/expand config roots)
        result (stamps/build plain (disk-stamps dir (keys plain))
                             (System/currentTimeMillis) roots)]
    {:dir dir :commands (stamps/commands plain result)}))

(defn -main [& args]
  (let [run? (= "--run" (first args))
        [config-path & targets] (if run? (rest args) args)
        {:keys [dir commands]} (commands-for config-path targets)]
    (if (empty? commands)
      (println "nothing to do")
      (doseq [command commands]
        (println command)
        (when run? (run-command! dir command))))))
```

The repository includes `resources/build/example.edn`, the C
configuration from the previous section, with stub source files next to
it:

```
$ clojure -M -m sdx.build.main resources/build/example.edn app
cc -O2 -c main.c -o main.o
cc -O2 -c util.c -o util.o
cc main.o util.o -o app
```

`commands-for` is a straight line through the three stages: read EDN,
expand patterns, read stamps for every name the expansion mentions,
build, collect commands. Nothing in it is clever, which is the point.
The decisions were all made in code that takes maps and returns maps.

Even this layer can be tested. `test/sdx/build/main_test.clj` writes a
small project into a temporary directory, with a configuration whose
rules are shell commands that work anywhere `sh` does, and checks the
dry run, the real run, and that a second real run has nothing to do:

```clojure
(def config
  '{:vars {"SHOUT" "tr a-z A-Z"}
    :patterns {"%.txt" {:depends ["%.md"] :rule "${SHOUT} < $< > $@"}}
    :targets {"book.txt" {:depends ["intro.txt" "body.txt"] :rule "cat $^ > $@"}}})

(deftest running-makes-the-files
  (with-temp-dir
    (fn [dir]
      (write-project! dir)
      (let [path (str (io/file dir "build.edn"))]
        (with-out-str (main/-main "--run" path "book.txt"))
        (is (= "HELLO\nWORLD\n" (slurp (io/file dir "book.txt"))))
        (testing "a second run has nothing to do"
          (is (= "nothing to do\n" (with-out-str (main/-main "--run" path "book.txt")))))
        (testing "a failing command stops the build"
          (spit (io/file dir "build.edn")
                (pr-str (assoc-in config [:targets "bad"] {:rule "exit 3"})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"status 3"
                                (with-out-str (main/-main "--run" path "bad")))))))))
```

One honest caveat: this test depends on real modification times, which
most filesystems record to the millisecond or coarser. If the build ran
fast enough for `book.txt` to get the same timestamp as `intro.txt`, the
second run would still do nothing, because equal is not newer; but if a
source were then touched within the same millisecond, the change would
be missed. Real build tools worry about exactly this, and some of them
compare file contents or hashes instead of times. Our core would accept
hashes just as readily as times, as one of the exercises explores.

## Summary

- A build configuration is a directed acyclic graph: targets are nodes,
  dependencies are edges, and a cycle means nothing can be built.
- A topological sort (Kahn's algorithm) visits every node after its
  dependencies, and detects a cycle when it runs out of ready nodes
  with some still waiting. Breaking ties in sorted order makes the
  result deterministic and therefore testable.
- A target is stale if it is missing, if a dependency is newer, or if a
  dependency was rebuilt in the same run. Threading the updated
  timestamps through a `reduce` makes the third case fall out naturally.
- A correct build is idempotent: running it again changes nothing.
- Pattern rules and variables are a small language that expands into
  plain rules. Doing the expansion as a separate pure step leaves the
  earlier stages untouched.
- Taking the clock and the timestamps as values keeps the whole core
  independent of the filesystem; the layer that reads real files and
  runs real commands is a few lines at the edge.

## Exercises

1. **Phony targets.** Add support for targets like `all` and `clean`
   that have a rule but never produce a file, so that they are always
   rebuilt. Where is the smallest change: in `stale?`, in `build`, or
   in the configuration format?

2. **Reporting the cycle.** `topo-sort` reports every target that is
   stuck, including ones that merely depend on the cycle. Change it to
   report only the targets that are actually on a cycle.

3. **Parallel builds.** Kahn's algorithm can have several targets ready
   at once. Write a version of `plan` that returns a vector of
   *batches*, where every target in a batch depends only on targets in
   earlier batches, so the batches could be built in parallel.

4. **Hashes instead of times.** Change `disk-stamps` to return a map of
   name to content hash and adjust `stale?` to compare against the
   hashes recorded in the previous build. What has to be stored between
   runs, and where?

5. **Multiple patterns.** `concrete-target` takes the first pattern
   that matches in sorted order. Change it to prefer the pattern whose
   literal prefix and suffix are longest, as `make` does, and write a
   test where the two policies disagree.

6. **Variables in dependencies.** Variables are only substituted in
   rules. Allow `${OBJS}` in a `:depends` vector, expanding to a list of
   names. What should happen if the variable is not defined?

7. **Dry-run diffs.** Make `-main` print, for each command, *why* it is
   running: which dependency was newer or missing. Which function has
   to change to make that information available?

8. **Recursive variables.** Allow a variable's value to mention other
   variables, as in `{"CC" "${TOOLCHAIN}/cc"}`. Detect cycles between
   variables using the ideas from stage 1.

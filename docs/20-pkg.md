---
title: "A Package Manager"
nav_order: 20
lede: "A dependency resolver that starts by trying every combination and learns to stop early."
---

Modern programs are mostly other people's code. A web application might
depend directly on a dozen libraries, each of which depends on a dozen
more, and every one of those comes in several versions that are not all
compatible with each other. The job of a **package manager** is to pick
one version of each package such that everybody is happy, and that turns
out to be a hard problem in the technical sense: in general, nothing
much better than trying possibilities is known.

This chapter builds the part of a package manager that does the picking.
The first stage is deliberately naive: it generates every combination of
versions and checks each one, which is correct and quickly becomes
impossible. The second stage builds the same answer one package at a
time and abandons a partial answer the moment it breaks a rule, which is
usually enormously faster and is the idea behind every practical
resolver. The third stage adds the constraint language people actually
write, such as `>=1.2,<3`, and does so without touching the search at
all. Along the way we will count exactly how much work each approach
does, because the whole subject is about the difference between a
number like 6561 and a number like 66.

## Manifests and candidates

A **manifest** describes what exists and what each thing needs. Ours
maps a package name to its versions, and each version to the packages it
depends on and the versions of each that it can work with:

```clojure
(def manifest
  {"app" {"1" {"web" #{"1" "2"} "db" #{"1" "2"}}
          "2" {"web" #{"2"} "db" #{"2" "3"}}}
   "web" {"1" {"db" #{"1"}}
          "2" {"db" #{"2" "3"}}}
   "db"  {"1" {} "2" {} "3" {}}})
```

Version 1 of `app` works with either version of `web` and the first two
versions of `db`; version 2 of `web` needs `db` 2 or 3; `db` needs
nothing. For the first two stages a constraint is simply a set of
acceptable version strings, which is the most direct representation
possible and makes checking a constraint a `contains?`. The third stage
will replace the sets with expressions.

A **candidate** is a map that picks one version for every package, and
the question is whether it is **consistent**: whether every package in it
is happy with the versions chosen for its dependencies. Before anything
else we check that the manifest is well formed, since a dependency on a
package nobody has heard of is a mistake in the input rather than a
puzzle to solve:

```clojure
(ns sdx.pkg.exhaustive
  "Stage 1: find every consistent set of package versions by trying
   every combination.

   A manifest maps each package name to a map of its versions, and each
   version to the packages it depends on and the versions of each that
   it can work with:

     {\"app\" {\"1\" {\"db\" #{\"1\" \"2\"}}}
      \"db\"  {\"1\" {} \"2\" {}}}

   A candidate is a map from every package name to one chosen version.")

(defn check-manifest
  "Return `manifest` if every dependency names a package in it; otherwise
   throw."
  [manifest]
  (doseq [[package versions] manifest
          [version depends] versions
          [dep _] depends]
    (when-not (contains? manifest dep)
      (throw (ex-info (str package " " version " depends on unknown package " dep)
                      {:package package :version version :missing dep}))))
  manifest)
```

Generating every candidate is a **cartesian product**: one version from
the first package, combined with one from the second, combined with one
from the third. The function that builds it is recursive, and it is
worth reading slowly, because it is also our first **lazy sequence**:

```clojure
(defn cartesian
  "A lazy sequence of every way to pick one element from each of
   `colls`, as a list. Picking from no collections has exactly one
   result: picking nothing."
  [colls]
  (if (empty? colls)
    '(())
    (let [tails (cartesian (rest colls))]
      (for [head (first colls)
            tail tails]
        (cons head tail)))))
```

```clojure
(cartesian [[1 2] [:a :b]])   ;; => ((1 :a) (1 :b) (2 :a) (2 :b))
(cartesian [])                ;; => (())
```

The base case is the one people get wrong: picking from no collections
has exactly one outcome, the empty choice, not zero outcomes. If it
returned an empty sequence, every product would be empty, because the
`for` would have no tails to pair heads with. With that in place, the
product of several collections is each element of the first paired with
each product of the rest.

`for` in Clojure is a sequence comprehension, not a loop, and it
produces its results lazily: nothing is computed until something asks
for it, and then only as much as was asked for. This matters here
because the product of twenty packages with three versions each has
three billion elements, and we would like to be able to look at the
first few without building the rest. The tests check that laziness by
taking three elements from a product whose first collection is infinite:

```clojure
(deftest cartesian-products
  (is (= '(()) (ex/cartesian [])))
  (is (= '((1 :a) (1 :b) (2 :a) (2 :b)) (ex/cartesian [[1 2] [:a :b]])))
  (is (= '() (ex/cartesian [[1 2] []])))
  (testing "the product is lazy: it can start from an infinite collection"
    (is (= '((0 :a) (0 :b) (1 :a)) (take 3 (ex/cartesian [(range) [:a :b]]))))))
```

(`(range)` with no arguments is the infinite sequence of integers from
zero.) Candidates are then the product of each package's versions, with
each combination turned back into a map by `zipmap`:

```clojure
(defn candidates
  "A lazy sequence of every assignment of one version to every package."
  [manifest]
  (let [names (sort (keys manifest))
        versions (map #(sort (keys (manifest %))) names)]
    (map #(zipmap names %) (cartesian versions))))

(defn candidate-count
  "How many candidates a manifest has: the product of its version counts."
  [manifest]
  (reduce * (map count (vals manifest))))
```

```clojure
(take 3 (candidates manifest))
;; => ({"app" "1", "db" "1", "web" "1"}
;;     {"app" "1", "db" "1", "web" "2"}
;;     {"app" "1", "db" "2", "web" "1"})
(candidate-count manifest)   ;; => 12
```

Checking a candidate has two layers. A single package at a single
version is compatible with a candidate if the candidate's choice for
each of its dependencies is in the acceptable set. A candidate is
consistent if that holds for every package in it:

```clojure
(defn compatible?
  "True if the versions that `candidate` picks for the dependencies of
   `package` at `version` are all acceptable to it."
  [manifest candidate package version]
  (every? (fn [[dep allowed]] (contains? allowed (candidate dep)))
          (get-in manifest [package version])))

(defn consistent?
  "True if every package in `candidate` is happy with every other."
  [manifest candidate]
  (every? (fn [[package version]] (compatible? manifest candidate package version))
          candidate))
```

```clojure
(compatible? manifest {"app" "1" "web" "2" "db" "1"} "web" "2")   ;; => false
```

(`web` 2 needs `db` 2 or 3, and this candidate chose `db` 1.) Solving is
now a `reduce` over the candidates that counts how many it looked at
and keeps the consistent ones:

```clojure
(defn solve
  "Test every candidate. Returns {:solutions [consistent candidates],
   :checked number-of-candidates-tested}."
  [manifest]
  (let [manifest (check-manifest manifest)]
    (reduce (fn [result candidate]
              (cond-> (update result :checked inc)
                (consistent? manifest candidate) (update :solutions conj candidate)))
            {:solutions [] :checked 0}
            (candidates manifest))))
```

```clojure
(solve manifest)
;; => {:solutions [{"app" "1", "db" "1", "web" "1"}
;;                 {"app" "1", "db" "2", "web" "2"}
;;                 {"app" "2", "db" "2", "web" "2"}
;;                 {"app" "2", "db" "3", "web" "2"}],
;;     :checked 12}
```

Twelve candidates, four solutions. `cond->` threads the result through
the `update` only when the condition holds, which is a tidy way to say
"always count, sometimes keep". We return a count rather than printing
one so that the tests can assert on it, and the tests do: twelve
checked, four found, and for a manifest with no solution, every
candidate checked and nothing found.

This stage is correct, and its problem is arithmetic. A manifest with
*n* packages of three versions each has 3<sup>n</sup> candidates. For
three packages that is 27; for ten it is 59,049; for twenty it is three
and a half billion; and real manifests have hundreds of packages. This
is **combinatorial explosion**, and no amount of optimizing the check
will outrun an exponent. The only way out is to not generate most of
the candidates in the first place.

## Backtracking

Look again at the manifest. `app` 2 requires `web` 2. The moment we
decide on `app` 2 and `web` 1, every candidate that starts that way is
doomed, no matter what `db` is: three candidates that stage 1 will
dutifully generate and reject. If we choose versions one package at a
time and check each choice against the choices already made, we can
throw away a bad partial choice before spending anything on completing
it. That is **backtracking**: a depth-first walk of a tree whose levels
are packages and whose branches are versions, cutting off a branch as
soon as it cannot lead anywhere.

The check for a partial choice has to look in both directions. The
newcomer may have constraints on packages already chosen, and packages
already chosen may have constraints on the newcomer. Packages that have
not been chosen yet are not consulted, since there is nothing yet to
disagree with:

```clojure
(ns sdx.pkg.backtrack
  "Stage 2: choose versions one package at a time and give up on a
   partial choice as soon as it breaks a constraint.

   The manifest format is the same as stage 1. The search is a
   depth-first walk of a tree whose levels are packages and whose
   branches are versions; a branch is cut off the moment the versions
   chosen so far cannot be extended."
  (:require [sdx.pkg.exhaustive :as ex]))

(defn fits?
  "Can `package` at `version` join the partial assignment `chosen`?
   Its own constraints must accept whatever is already chosen, and
   everything already chosen must accept it. Packages not yet chosen
   are not consulted."
  [manifest chosen package version]
  (and (every? (fn [[dep allowed]]
                 (or (not (contains? chosen dep))
                     (contains? allowed (chosen dep))))
               (get-in manifest [package version]))
       (every? (fn [[other other-version]]
                 (let [allowed (get-in manifest [other other-version package])]
                   (or (nil? allowed)
                       (contains? allowed version))))
               chosen)))
```

```clojure
(fits? manifest {"app" "2"} "web" "1")   ;; => false
(fits? manifest {"app" "2"} "db" "3")    ;; => true
```

The second direction is easy to forget, and the test
`fitting-into-a-partial-choice` has a case for each direction and one
for a package that has not been chosen. The search itself is a recursive
function over the list of packages still to decide:

```clojure
(defn search
  "Extend `chosen` with a version of each package in `order`, in turn,
   accumulating into `result` every complete assignment reached and a
   count of every version tried."
  [manifest order versions-of chosen result]
  (if (empty? order)
    (update result :solutions conj chosen)
    (let [package (first order)]
      (reduce (fn [result version]
                (let [result (update result :checked inc)]
                  (if (fits? manifest chosen package version)
                    (search manifest (rest order) versions-of
                            (assoc chosen package version) result)
                    result)))
              result
              (versions-of package)))))
```

When no packages are left, `chosen` is a complete, consistent candidate
and is recorded. Otherwise, for each version of the next package, we
count the attempt, and if it fits we recurse with it added to `chosen`;
if it does not, we move to the next version and the subtree below it is
never visited. The `result` map is threaded through the `reduce` and
through every recursive call, so the function is pure: the same manifest
and order always produce the same solutions and the same count, and
nothing is accumulated in an atom. This recursion is not a tail call,
so it uses one stack frame per package. A manifest would need thousands
of packages for that to matter.

The search order is a parameter, for reasons the next section explains,
and so is the order in which to try each package's versions:

```clojure
(defn alphabetical
  "Package names in sorted order: the simplest search order."
  [manifest]
  (sort (keys manifest)))

(defn solve
  "Find every consistent assignment by backtracking. Options:
   :order, a function from manifest to the sequence of package names to
   decide, in order (default alphabetical); :versions-of, a function
   from package name to the versions to try, in order (default sorted).
   Returns {:solutions [...], :checked number-of-versions-tried}."
  ([manifest] (solve manifest {}))
  ([manifest {:keys [order versions-of]
              :or {order alphabetical}}]
   (let [manifest (ex/check-manifest manifest)
         versions-of (or versions-of (fn [package] (sort (keys (manifest package)))))]
     (search manifest (order manifest) versions-of {} {:solutions [] :checked 0}))))
```

```clojure
(solve manifest)
;; => {:solutions [{"app" "1", "db" "1", "web" "1"}
;;                 {"app" "1", "db" "2", "web" "2"}
;;                 {"app" "2", "db" "2", "web" "2"}
;;                 {"app" "2", "db" "3", "web" "2"}],
;;     :checked 16}
```

The same four solutions, and sixteen checks against twelve. On a
manifest this small, backtracking does *more* work. The counts measure
different things (stage 1 counts complete candidates, each of which
costs a check of every package; stage 2 counts single version choices,
each of which costs one `fits?`), but the honest conclusion is that
pruning has overhead and only pays when there is something to prune. To
see it pay, we need a manifest with structure. The test file defines a
family of them:

```clojure
(defn chain
  "A manifest of `n` packages p0 ... p(n-1), each with three versions,
   where every version of each package requires the same version of
   the next package. There are exactly three solutions however long the
   chain is."
  [n]
  (into {}
        (for [i (range n)]
          [(str "p" i)
           (into {}
                 (for [v ["1" "2" "3"]]
                   [v (if (< (inc i) n) {(str "p" (inc i)) #{v}} {})]))])))

(deftest pruning-beats-the-explosion
  (let [m (chain 8)
        exhaustive (ex/solve m)
        backtracking (bt/solve m)]
    (is (= 6561 (ex/candidate-count m)))
    (is (= 6561 (:checked exhaustive)))
    (is (= 3 (count (:solutions exhaustive))))
    (is (= (set (:solutions exhaustive)) (set (:solutions backtracking))))
    (testing "three choices at the top, then three tries per level of which one fits"
      (is (= (+ 3 (* 3 3 7)) (:checked backtracking))))))
```

Eight packages in a chain, where each must match the version of the
next, have 3<sup>8</sup> = 6561 candidates and three solutions.
Exhaustive search checks all 6561. Backtracking tries three versions
of `p0`, and then at each of the seven remaining levels tries three
versions of which exactly one fits, for 3 + 3 × 3 × 7 = 66 checks.
Make the chain twice as long and the exhaustive count squares while the
backtracking count roughly doubles. That is the difference between an
algorithm that is exponential in the number of packages and one that,
on this input, is linear.

An unsatisfiable manifest is where pruning shines brightest. If `a`
needs `c` 2 and `b` needs `c` 1, there is no answer, and stage 1 finds
that out by checking every candidate. Backtracking finds it out after
four checks, because once `a` and `b` are chosen, neither version of
`c` fits and there is nothing left to try:

```clojure
(deftest unsatisfiable-manifests-are-pruned-early
  (let [impossible {"a" {"1" {"c" #{"2"}}}
                    "b" {"1" {"c" #{"1"}}}
                    "c" {"1" {} "2" {}}}
        {:keys [solutions checked]} (bt/solve impossible)]
    (is (= [] solutions))
    (is (= 4 checked))))
```

## Search order matters

Backtracking prunes a branch when a choice conflicts with an earlier
one. It follows that the earlier we make the choices most likely to
conflict, the more we prune. Which package to decide first is therefore
a real decision, and the test file's `bigger` manifest, five packages
with mostly three versions each and a tangle of constraints among them,
lets us measure it. Two common heuristics are easy to write:

```clojure
(defn fewest-versions-first
  "Package names ordered so that the ones with the fewest versions come
   first, with ties broken alphabetically."
  [manifest]
  (sort-by (fn [package] [(count (manifest package)) package]) (keys manifest)))

(defn most-constrained-first
  "Package names ordered so that the ones mentioned in the most
   constraints, as either side, come first."
  [manifest]
  (let [mentions (frequencies
                  (for [[package versions] manifest
                        [_ depends] versions
                        [dep _] depends
                        name [package dep]]
                    name))]
    (sort-by (fn [package] [(- (get mentions package 0)) package]) (keys manifest))))
```

Both use a trick worth knowing: `sort-by` with a key function that
returns a vector sorts by the first element and breaks ties by the
second, so `[(count ...) package]` means "by version count, then by
name". The negation in `most-constrained-first` turns an ascending sort
into a descending one. The test records what each order costs:

```clojure
(deftest search-order-changes-the-work
  (is (= ["app" "db" "http" "log" "web"] (bt/alphabetical bigger)))
  (is (= ["http" "app" "db" "log" "web"] (bt/fewest-versions-first bigger)))
  (is (= ["app" "log" "web" "db" "http"] (bt/most-constrained-first bigger)))
  (let [tried (fn [order] (:checked (bt/solve bigger {:order order})))]
    (is (= 77 (tried bt/alphabetical)))
    (is (= 83 (tried bt/fewest-versions-first)))
    (is (= 74 (tried bt/most-constrained-first))))
  (testing "trying versions newest first finds the same solutions"
    (let [newest (fn [package] (reverse (sort (keys (bigger package)))))]
      (is (= (set (:solutions (bt/solve bigger)))
             (set (:solutions (bt/solve bigger {:versions-of newest}))))))))
```

Against 162 candidates, alphabetical order tries 77 versions,
most-constrained-first tries 74, and fewest-versions-first tries 83,
which is *worse* than doing nothing clever. That last number is the
lesson. `http` has the fewest versions, so the heuristic decides it
first, but almost nothing constrains `http`, so deciding it early prunes
nothing and just adds a level above all the real decisions. A heuristic
is a guess about the shape of the input, and it is only as good as the
guess. Real resolvers combine several, re-evaluate them as the search
proceeds, and learn from the conflicts they hit; the shape of the
search is the one we have, and the ordering is where the engineering
goes.

The order in which to try a package's *versions* does not change how
much work it takes to find all the solutions, as the last test shows,
but it changes which solution is found first, and a package manager
that stops at the first solution cares very much about that. Users
expect the newest versions that work. The next stage uses this.

## Constraint expressions

Nobody writes `#{"1.2" "1.3" "1.4" "2.0"}` in a manifest. They write
`>=1.2,<3` and expect the tool to work out which versions that means.
Stage 3 adds a small language for that, and the design decision is
where to put it. We could teach `fits?` to evaluate expressions, but
`fits?` is called at every node of the search, and parsing a string
there would be both slow and a change to code that is already correct.
Instead, we **compile** a manifest with expressions into a manifest with
sets, by evaluating each expression once against the versions that
actually exist, and then hand the result to stage 2 unchanged.

First, versions. A version like `1.2.3` is parsed into a vector of
numbers so that it compares the way people expect, with `1.10` after
`1.9` and `1.2` the same as `1.2.0`:

```clojure
(defn parse-version
  "\"1.2.3\" becomes [1 2 3]. Throws on anything that is not digits
   separated by dots."
  [s]
  (when-not (re-matches #"\d+(\.\d+)*" s)
    (throw (ex-info (str "bad version " (pr-str s)) {:version s})))
  (mapv #(Long/parseLong %) (str/split s #"\.")))

(defn compare-versions
  "Compare two parsed versions component by component, treating missing
   trailing components as zero, so that [1 2] equals [1 2 0] and
   [1 10] is newer than [1 9]."
  [a b]
  (let [width (max (count a) (count b))
        pad (fn [v] (into v (repeat (- width (count v)) 0)))]
    (compare (pad a) (pad b))))

(defn newest-first
  "Version strings sorted from newest to oldest."
  [versions]
  (sort-by parse-version #(compare-versions %2 %1) versions))
```

```clojure
(parse-version "1.2.3")                ;; => [1 2 3]
(compare-versions [1 9] [1 10])        ;; => -1
(compare-versions [1 2] [1 2 0])       ;; => 0
(newest-first ["1.9" "2.0" "1.10"])    ;; => ("2.0" "1.10" "1.9")
```

Clojure's built-in `compare` already orders two vectors of the same
length element by element, so after padding the shorter one with zeros
we can hand the job to it. `newest-first` passes `sort-by` a comparator
with the arguments swapped, which reverses the order.

A constraint is one or more **clauses** separated by commas, each an
operator and a version. The operators are a map from their text to a
predicate on the result of `compare-versions`:

```clojure
(def ^:private operators
  {">=" #(>= % 0), "<=" #(<= % 0), "!=" #(not= % 0)
   ">" #(> % 0), "<" #(< % 0), "=" #(= % 0)})

(defn parse-clause
  "One comparison such as \">=1.2\" becomes [op [1 2]]. A bare version
   means equality."
  [s]
  (if-let [[_ op version] (re-matches #"\s*(>=|<=|!=|>|<|=)?\s*([\d.]+)\s*" s)]
    [(or op "=") (parse-version version)]
    (throw (ex-info (str "bad constraint " (pr-str s)) {:constraint s}))))

(defn parse-constraint
  "A constraint is clauses separated by commas, all of which must hold.
   \"*\" or the empty string accepts any version."
  [s]
  (if (contains? #{"*" ""} (str/trim s))
    []
    (mapv parse-clause (str/split s #","))))

(defn meets?
  "True if version string `version` meets every clause of `constraint`."
  [constraint version]
  (let [v (parse-version version)]
    (every? (fn [[op wanted]] ((operators op) (compare-versions v wanted)))
            (parse-constraint constraint))))

(defn allowed-versions
  "The subset of `available` version strings that satisfy `constraint`."
  [constraint available]
  (set (filter #(meets? constraint %) available)))
```

```clojure
(parse-constraint ">=1.2,<3")                                 ;; => [[">=" [1 2]] ["<" [3]]]
(meets? ">=1.2,<3" "2.5")                                     ;; => true
(allowed-versions ">=1.2,<3" ["1.0" "1.2" "2.0" "3.0"])       ;; => #{"1.2" "2.0"}
```

The regular expression in `parse-clause` makes the operator group
optional, so a bare `2.0.1` means exactly that version, and `*` means
no clauses at all, which `every?` accepts vacuously. A grammar this
small fits in a regular expression; a bigger one would call for the
tokenizer-and-parser split of [the parsing chapter](../05-parser/). A constraint that
cannot be parsed is an error, as it should be: `~1.2` means something in
some package managers and nothing in ours, and guessing would be worse
than refusing. `allowed-versions` is the bridge to stage 2. Given a
constraint and the versions a package actually has, it produces the set
that stage 2 understands, and compiling a manifest is applying it to
every constraint:

```clojure
(defn compile-manifest
  "Replace every constraint expression in `manifest` with the set of the
   dependency's versions that satisfy it. The result is a stage 2
   manifest."
  [manifest]
  (into {}
        (for [[package versions] manifest]
          [package
           (into {}
                 (for [[version depends] versions]
                   [version
                    (into {}
                          (for [[dep constraint] depends]
                            (if-let [available (keys (manifest dep))]
                              [dep (allowed-versions constraint available)]
                              (throw (ex-info (str package " " version
                                                   " depends on unknown package " dep)
                                              {:package package :version version :missing dep})))))]))])))

(defn solve
  "Find every consistent assignment for a manifest with constraint
   expressions, trying newest versions first."
  [manifest]
  (let [compiled (compile-manifest manifest)]
    (bt/solve compiled {:order bt/most-constrained-first
                        :versions-of (fn [package] (newest-first (keys (compiled package))))})))
```

Three nested `(into {} (for ...))` rebuild the manifest's three levels
with the innermost values replaced. Here is the repository's example
manifest, `resources/pkg/manifest.edn`, and what it compiles to:

```clojure
(def example
  {"app" {"1.0" {"web" ">=2", "db" ">=1"}
          "1.1" {"web" ">=2.1", "db" ">=1.2"}}
   "web" {"2.0" {"db" "<2"}
          "2.1" {"db" ">=1.1,<3"}
          "3.0" {"db" ">=2"}}
   "db"  {"1.0" {} "1.2" {} "2.0" {}}})

(compile-manifest example)
;; => {"app" {"1.0" {"web" #{"3.0" "2.1" "2.0"}, "db" #{"1.0" "1.2" "2.0"}},
;;            "1.1" {"web" #{"3.0" "2.1"}, "db" #{"1.2" "2.0"}}},
;;     "web" {"2.0" {"db" #{"1.0" "1.2"}},
;;            "2.1" {"db" #{"1.2" "2.0"}},
;;            "3.0" {"db" #{"2.0"}}},
;;     "db"  {"1.0" {}, "1.2" {}, "2.0" {}}}
```

Notice that `db` `<2` admits `1.2` as well as `1.0`, which a quick
reading of `<2` as "only version 1" would miss. Evaluating constraints
against the real version list, once, in one place, is what gives such
questions a single answer that the tests can pin down. A constraint no version meets compiles to an
empty set, and an empty set makes the search prune that branch
immediately, so "unsatisfiable" needs no special handling anywhere.

The `solve` in this stage chooses `most-constrained-first` as the
package order and tries versions newest first, so the first solution
found is the one with the newest versions that work together, which is
what a user wants installed:

```clojure
(solve example)
;; => {:solutions [{"db" "2.0", "web" "3.0", "app" "1.1"} ...seven more...],
;;     :checked 22}
```

The tests in `test/sdx/pkg/constraints_test.clj` cover the version
comparator, each operator, the compiler, an unsatisfiable manifest, and
the end-to-end solve. The unsatisfiable case is the one every package
manager must handle gracefully, since it is the one users hit when two
of their dependencies disagree:

```clojure
(deftest unsatisfiable-constraints
  (let [impossible {"a" {"1.0" {"c" ">=2"}}
                    "b" {"1.0" {"c" "<2"}}
                    "c" {"1.0" {} "2.0" {}}}]
    (is (= [] (:solutions (c/solve impossible)))))
  (testing "a constraint no version meets compiles to an empty set"
    (let [compiled (c/compile-manifest {"a" {"1.0" {"c" ">=9"}} "c" {"1.0" {}}})]
      (is (= #{} (get-in compiled ["a" "1.0" "c"])))
      (is (= [] (:solutions (c/solve {"a" {"1.0" {"c" ">=9"}} "c" {"1.0" {}}})))))))
```

## The command line

A small `-main` reads a manifest from an EDN file and prints every
solution, newest first, with the count of versions tried so that the
cost of the search is visible:

```clojure
(defn format-solution
  "\"app 1.1, db 2.0, web 3.0\""
  [solution]
  (str/join ", " (for [[package version] (sort solution)] (str package " " version))))

(defn -main [manifest-path]
  (let [manifest (edn/read-string (slurp manifest-path))
        {:keys [solutions checked]} (constraints/solve manifest)]
    (doseq [solution solutions]
      (println (format-solution solution)))
    (println (count solutions) "solutions," checked "versions tried")))
```

```
$ clojure -M -m sdx.pkg.main resources/pkg/manifest.edn
app 1.1, db 2.0, web 3.0
app 1.0, db 2.0, web 3.0
app 1.1, db 2.0, web 2.1
app 1.0, db 2.0, web 2.1
app 1.1, db 1.2, web 2.1
app 1.0, db 1.2, web 2.1
app 1.0, db 1.2, web 2.0
app 1.0, db 1.0, web 2.0
8 solutions, 22 versions tried
```

A real package manager would stop at the first line. Ours prints them
all because seeing every solution, and the order they arrive in, is the
best way to understand what the search is doing.

## Summary

- Dependency resolution is a search problem: pick one version of each
  package so that every constraint holds. In general, no shortcut is
  known that avoids searching.
- Enumerating every candidate is a cartesian product, which is easy to
  write as a lazy sequence and impossible to finish once there are more
  than a handful of packages: the number of candidates grows
  exponentially with the number of packages.
- Backtracking chooses one package at a time and abandons a partial
  choice as soon as it conflicts with an earlier one, so that whole
  subtrees of the candidate space are never generated. On structured
  input this turns an exponential search into a nearly linear one.
- The order in which packages are decided changes how much is pruned.
  Heuristics such as most-constrained-first usually help, but a
  heuristic is a guess, and measuring is the only way to know.
- Counting the work done, and returning the count as data, makes these
  claims testable rather than anecdotal.
- A richer input language does not have to mean a richer core: compile
  constraint expressions into the sets the search already understands,
  once, before searching.

## Exercises

1. **First solution only.** Change `search` so that it can stop at the
   first solution found. Clojure's `reduced` can short-circuit a
   `reduce`; how do you propagate it out of the recursive call?

2. **Lazy solutions.** Rewrite the search as a lazy sequence of
   solutions, so that `(first (solutions manifest))` does only as much
   work as needed. What happens to the `:checked` count, and is there a
   way to keep it without an atom?

3. **Forward checking.** When a version is chosen, compute which
   versions of each remaining package are still possible, and prune
   immediately if any package has none left. Measure the effect on the
   `bigger` manifest's count.

4. **Dynamic ordering.** Instead of fixing the package order up front,
   choose at each level the undecided package with the fewest versions
   still compatible with the choices so far. Compare its count with the
   three static orders.

5. **Pre-release versions.** Extend `parse-version` and
   `compare-versions` to handle a suffix like `2.0.0-beta.1`, which
   sorts before `2.0.0`. What should `>=2.0.0` say about it?

6. **Caret and tilde.** Many package managers accept `^1.2.3` (any
   version with the same first component, at or above `1.2.3`) and
   `~1.2.3` (same first two components). Add them as clauses that
   expand to the existing operators.

7. **Explaining failure.** When there are no solutions, report which
   pair of constraints could not both be met. What would `search` have
   to record to make that possible?

8. **Optional dependencies.** Allow a version to declare a dependency as
   optional: if the package is chosen, the constraint applies; if it is
   not, nothing is required. How does this change `fits?`, and how does
   it change the set of candidates?

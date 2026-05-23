(ns dev.zeko.stube.lifecycle
  "Component lifecycle hooks: `:start`, `:stop`, `:wakeup`.

  Each hook returns `[self' effects]` (or just `effects`, for terse
  cleanup hooks).  The functions in this namespace fold a hook's
  emitted effects through `run-effects`, returning `[conv' fragments]`
  for the caller.

  To keep the dependency direction one-way (kernel requires lifecycle,
  not the other way), the kernel's `run-effects` is passed in as
  `run-effects-fn`.  No back-reference from lifecycle to kernel."
  (:require [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.effects      :as e]
            [dev.zeko.stube.registry     :as registry]))

(defn coerce-return
  "Normalise whatever a handler or lifecycle hook returned into the
  kernel's canonical `[self' effects]` pair.  Accepted shapes:

      nil          → [self []]      ; explicit no-op (cleanup hooks)
      <map>        → [<map> []]     ; state change, no effects
      [<map> <v>]  → as-is          ; the canonical pair
      <vec>        → [self <vec>]   ; effects only, same self

  The map and pair cases let `:handle` return `(assoc self :n 1)` or
  `[(s/answer :ok)]` directly when one side of the pair would be
  ceremony; the original `[self' effects]` form keeps working."
  [self result]
  (cond
    (nil? result)
    [self []]

    (map? result)
    [result []]

    (and (vector? result) (= 2 (count result)) (map? (first result)))
    result

    :else
    [self result]))

(defn run-start-hook
  "Run `:start` for `iid` if present, without rendering the instance.
  Callers decide which newly-started frame should be rendered."
  [run-effects-fn conv iid]
  (if-let [inst (conv/instance conv iid)]
    (let [cdef (registry/lookup! (:instance/type inst))]
      (if-let [start-fn (:start cdef)]
        (let [[inst' fx] (coerce-return inst (start-fn inst))
              inst'      (conv/preserve-meta inst inst')
              conv'      (conv/put-instance conv inst')
              [conv'' frags] (e/with-origin iid
                               (run-effects-fn conv' fx))]
          [conv'' frags])
        [conv []]))
    [conv []]))

(defn run-start-hooks
  "Run `:start` for each iid in order, collecting fragments."
  [run-effects-fn conv iids]
  (reduce (fn [[c frags] iid]
            (let [[c' more] (run-start-hook run-effects-fn c iid)]
              [c' (into frags more)]))
          [conv []]
          iids))

(defn run-stop-hooks
  "Run `:stop` for the instance ids, preserving any emitted fragments.
  The instances are still present while hooks run; callers remove them
  afterwards.

  Uses `lookup` (not `lookup!`) for the cdef: a component that was
  de-registered after the instance was created (hot-reload, test
  teardown, shutdown after `registry/clear!`) simply has no `:stop` to
  run.  `:start` and `:wakeup` still error loudly on missing cdefs —
  the asymmetry is intentional, since stop has nowhere to surface a
  late-binding error to."
  [run-effects-fn conv iids]
  (reduce (fn [[c frags] iid]
            (if-let [inst (conv/instance c iid)]
              (if-let [stop-fn (some-> (registry/lookup (:instance/type inst))
                                       :stop)]
                (let [[_ fx] (coerce-return inst (stop-fn inst))
                      [c' more] (e/with-origin iid
                                  (run-effects-fn c fx))]
                  [c' (into frags more)])
                [c frags])
              [c frags]))
          [conv []]
          iids))

(defn wakeup-frame
  "Run `:wakeup` for `iid`, updating the instance before rendering."
  [run-effects-fn conv iid]
  (if-let [inst (conv/instance conv iid)]
    (let [cdef (registry/lookup! (:instance/type inst))]
      (if-let [wakeup-fn (:wakeup cdef)]
        (let [[inst' fx] (coerce-return inst (wakeup-fn inst))
              inst'      (conv/preserve-meta inst inst')
              conv'      (conv/put-instance conv inst')
              [conv'' frags] (e/with-origin iid
                               (run-effects-fn conv' fx))]
          [conv'' frags])
        [conv []]))
    [conv []]))

(defn run-wakeup-hooks
  "Run `:wakeup` for each iid in order, collecting fragments."
  [run-effects-fn conv iids]
  (reduce (fn [[c frags] iid]
            (let [[c' more] (wakeup-frame run-effects-fn c iid)]
              [c' (into frags more)]))
          [conv []]
          iids))

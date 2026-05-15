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

(defn- lifecycle-pair
  "Normalise a lifecycle hook result.  Hooks mirror `:start` and should
  return `[self' effects]`, but accepting nil keeps cleanup-only hooks
  terse."
  [self result]
  (cond
    (nil? result) [self []]
    (and (vector? result) (= 2 (count result)) (map? (first result))) result
    :else [self result]))

(defn run-start-hook
  "Run `:start` for `iid` if present, without rendering the instance.
  Callers decide which newly-started frame should be rendered."
  [run-effects-fn conv iid]
  (if-let [inst (conv/instance conv iid)]
    (let [cdef (registry/lookup! (:instance/type inst))]
      (if-let [start-fn (:start cdef)]
        (let [[inst' fx] (lifecycle-pair inst (start-fn inst))
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
  afterwards."
  [run-effects-fn conv iids]
  (reduce (fn [[c frags] iid]
            (if-let [inst (conv/instance c iid)]
              (let [cdef (registry/lookup! (:instance/type inst))]
                (if-let [stop-fn (:stop cdef)]
                  (let [[_ fx] (lifecycle-pair inst (stop-fn inst))
                        [c' more] (e/with-origin iid
                                    (run-effects-fn c fx))]
                    [c' (into frags more)])
                  [c frags]))
              [c frags]))
          [conv []]
          iids))

(defn wakeup-frame
  "Run `:wakeup` for `iid`, updating the instance before rendering."
  [run-effects-fn conv iid]
  (if-let [inst (conv/instance conv iid)]
    (let [cdef (registry/lookup! (:instance/type inst))]
      (if-let [wakeup-fn (:wakeup cdef)]
        (let [[inst' fx] (lifecycle-pair inst (wakeup-fn inst))
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

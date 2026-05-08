(ns stube.core
  "Public API of the stube framework.

  Most users only need this namespace.  It re-exports the small set of
  functions that are intended to be called from application code; the
  internals live in [[stube.kernel]], [[stube.conversation]],
  [[stube.registry]], [[stube.render]], [[stube.http]] and
  [[stube.server]].

  ──────────────────────────────────────────────────────────────────────
  At a glance
  ──────────────────────────────────────────────────────────────────────

      (require '[stube.core :as s])

      (s/defcomponent :ui/prompt
        :init   (fn [{:keys [text]}] {:text text :answer \"\"})
        :keep   #{:answer}
        :render (fn [self]
                  [:form (merge {:id (:instance/id self)}
                                (s/on self :submit))
                   [:label (:text self)]
                   [:input (merge {:name \"answer\"} (s/bind :answer))]
                   [:button \"OK\"]])
        :handle (fn [self _]
                  [self [[:answer (parse-long (:answer self))]]]))

      (s/defcomponent :demo/guess
        :init   (fn [_] {:target (rand-int 100)})
        :handle (fn [self _]
                  [self [[:call (s/embed :ui/prompt {:text \"Guess 1–100\"})
                          :resume :on-guess]]])
        :on-guess (fn [self n]
                    (cond
                      (< n (:target self)) [self [[:call (s/embed :ui/prompt
                                                                  {:text \"Too low\"})
                                                   :resume :on-guess]]]
                      :else                 [self [[:end {:winner true}]]])))

      (s/mount! \"/guess\" :demo/guess)
      (s/start! {:port 8080})

  ──────────────────────────────────────────────────────────────────────
  Stability of this surface
  ──────────────────────────────────────────────────────────────────────
  Everything in this namespace is intended to remain stable across
  framework versions.  Names and arities outside this namespace are
  considered internal until the framework reaches 1.0."
  (:require [stube.conversation :as conv]
            [stube.kernel       :as kernel]
            [stube.registry     :as registry]
            [stube.render       :as render]
            [stube.server       :as server]))

;; ---------------------------------------------------------------------------
;; Component definition
;; ---------------------------------------------------------------------------

(defn defcomponent
  "Define a component and add it to the global registry.  Conventionally
  called as

      (s/defcomponent :auth/login
        :init   (fn [args] state-map)
        :keep   #{:signal-keys}            ;; optional
        :render (fn [self] hiccup)
        :handle (fn [self event]           ;; event is {:event …, :signals …}
                  [self' effects])
        :on-foo (fn [self answer-value]    ;; resume keys, optional
                  [self' effects]))

  Returns the registered component map."
  [id & {:as opts}]
  (registry/register!
    (-> opts
        (assoc :component/id id)
        ;; Lift colocated lifecycle keys into the namespaced shape the
        ;; kernel actually reads.  Resume keys (`:on-foo`) stay in the
        ;; top-level map verbatim.
        (cond->
          (contains? opts :init)   (-> (assoc :component/init   (:init   opts))
                                       (dissoc :init))
          (contains? opts :render) (-> (assoc :component/render (:render opts))
                                       (dissoc :render))
          (contains? opts :handle) (-> (assoc :component/handle (:handle opts))
                                       (dissoc :handle))
          (contains? opts :keep)   (-> (assoc :component/keep   (:keep   opts))
                                       (dissoc :keep))))))

;; ---------------------------------------------------------------------------
;; Compositional helpers
;; ---------------------------------------------------------------------------

(def ^{:doc "See [[stube.conversation/embed]]."}
  embed conv/embed)

(def ^{:doc "See [[stube.render/on]]."}     on   render/on)
(def ^{:doc "See [[stube.render/bind]]."}   bind render/bind)

;; ---------------------------------------------------------------------------
;; Lifecycle / mounting
;; ---------------------------------------------------------------------------

(def ^{:doc "See [[stube.server/mount!]]."}    mount!     server/mount!)
(def ^{:doc "See [[stube.server/unmount!]]."}  unmount!   server/unmount!)
(def ^{:doc "See [[stube.server/start!]]."}    start!     server/start!)
(def ^{:doc "See [[stube.server/stop!]]."}     stop!      server/stop!)

;; ---------------------------------------------------------------------------
;; REPL / test surface
;; ---------------------------------------------------------------------------

(def ^{:doc "Pure event dispatch — `(dispatch conv event) → [conv' fragments]`.
  Useful from the REPL or for tests; production code goes through the http
  layer."}
  dispatch kernel/dispatch)

(def ^{:doc "Pure boot effects for a flow — see [[stube.kernel/boot]]."}
  boot kernel/boot)

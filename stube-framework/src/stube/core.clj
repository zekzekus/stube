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
  ;; Shadow `clojure.core/await`; see [[stube.flow]] for the rationale.
  (:refer-clojure :exclude [await])
  (:require [stube.conversation :as conv]
            ;; `:refer [await]` is intentional — it makes `stube.core/await`
            ;; resolve to the *same var* as `stube.flow/await`, which is
            ;; required for cloroutine to recognise `(s/await …)` calls in
            ;; user `defflow` bodies as suspend points (cloroutine compares
            ;; vars by identity, not by value).
            [stube.flow         :as flow :refer [await]]
            [stube.kernel       :as kernel]
            [stube.registry     :as registry]
            [stube.render       :as render]
            [stube.server       :as server]
            [stube.store        :as store]))

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

(def ^{:doc "See [[stube.render/on]]."}          on          render/on)
(def ^{:doc "See [[stube.render/bind]]."}        bind        render/bind)
(def ^{:doc "See [[stube.render/render-slot]]."} render-slot render/render-slot)

;; ---------------------------------------------------------------------------
;; Decorations (slice 2)
;; ---------------------------------------------------------------------------

(defn decorate
  "Build a new component definition by overriding keys of `base-cdef`.

  `overrides` is either:

  * a **map** that replaces the corresponding entries verbatim, or
  * a **function** of the base cdef returning such a map (so the
    override can call into the original `:render` / `:handle` etc.).

  Returns a fresh map; the caller is responsible for giving it a fresh
  `:component/id` and registering it.

      (def site-header
        (s/decorate (s/registry-lookup :booking/wizard)
          (fn [base]
            {:component/id     :booking/wizard-with-banner
             :component/render
             (fn [self]
               [:div {:id (:instance/id self)}
                [:header.banner \"Welcome\"]
                ((:component/render base) self)])})))

  No new runtime concept: this is just `merge` lifted into the
  framework's vocabulary so the call site reads like Seaside-style
  behavioural composition."
  [base-cdef overrides]
  (merge base-cdef
         (if (fn? overrides) (overrides base-cdef) overrides)))

(def ^{:doc "Look up a registered component definition by id (or nil)."}
  registry-lookup registry/lookup)

;; ---------------------------------------------------------------------------
;; History & persistence (slice 3)
;; ---------------------------------------------------------------------------

(def ^{:doc "An effect that walks one step backward through the
  conversation's history.  Use it from a handler to wire a \"Back\"
  button:

      (s/defcomponent :ui/back-button
        :render (fn [self]
                  [:button (merge {:type \"button\"}
                                  (s/on self :click :as :go-back))
                   \"Back\"])
        :handle (fn [self _] [self [s/back]]))

  When emitted from a top-level handler, it pops the most recent
  conversation snapshot off `:conv/history` and re-renders the
  restored top frame.  No-op if the history is empty."}
  back [:back])

(def ^{:doc "See [[stube.store/in-memory-store]]."}  in-memory-store store/in-memory-store)
(def ^{:doc "See [[stube.store/file-store]]."}        file-store      store/file-store)

;; ---------------------------------------------------------------------------
;; Linear flows (slice 1)
;; ---------------------------------------------------------------------------

(defmacro defflow
  "Define a linear flow component.  See [[stube.flow/defflow]] for the
  full docstring; this is a thin re-export so application code only ever
  needs `[stube.core :as s]`."
  [id bindings & body]
  `(stube.flow/defflow ~id ~bindings ~@body))

;; `await` is brought in via `:refer` above, on purpose; see the require.
;; This explicit attribution exists only to surface it in `(dir 'stube.core)`.
(alter-meta! #'await assoc
             :stube.core/re-export 'stube.flow/await)

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

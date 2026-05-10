(ns dev.zeko.stube.core
  "Public API of the stube framework.

  Most users only need this namespace.  It re-exports the small set of
  functions that are intended to be called from application code; the
  internals live in [[dev.zeko.stube.kernel]], [[dev.zeko.stube.conversation]],
  [[dev.zeko.stube.registry]], [[dev.zeko.stube.render]], [[dev.zeko.stube.http]] and
  [[dev.zeko.stube.server]].

  ──────────────────────────────────────────────────────────────────────
  At a glance
  ──────────────────────────────────────────────────────────────────────

      (require '[dev.zeko.stube.core :as s])

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
  ;; Shadow `clojure.core/await`; see [[dev.zeko.stube.flow]] for the rationale.
  (:refer-clojure :exclude [await])
  (:require [dev.zeko.stube.conversation :as conv]
            ;; `:refer [await]` is intentional — it makes `dev.zeko.stube.core/await`
            ;; resolve to the *same var* as `dev.zeko.stube.flow/await`, which is
            ;; required for cloroutine to recognise `(s/await …)` calls in
            ;; user `defflow` bodies as suspend points (cloroutine compares
            ;; vars by identity, not by value).
            [dev.zeko.stube.flow         :as flow :refer [await]]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render]
            [dev.zeko.stube.server       :as server]
            [dev.zeko.stube.store        :as store]
            [dev.zeko.stube.ui           :as ui]))

;; ---------------------------------------------------------------------------
;; Component definition
;; ---------------------------------------------------------------------------

(defn defcomponent
  "Define a component and add it to the global registry.  Conventionally
  called as

      (s/defcomponent :auth/login
        :doc    \"Prompt for credentials.\"
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
                                       (dissoc :keep))
          (contains? opts :doc)    (-> (assoc :component/doc    (:doc    opts))
                                       (dissoc :doc))))))

;; ---------------------------------------------------------------------------
;; Compositional helpers
;; ---------------------------------------------------------------------------

(def ^{:doc "See [[dev.zeko.stube.conversation/embed]]."}
  embed conv/embed)

(def ^{:doc "See [[dev.zeko.stube.render/on]]."}          on          render/on)
(def ^{:doc "See [[dev.zeko.stube.render/bind]]."}        bind        render/bind)
(def ^{:doc "See [[dev.zeko.stube.render/local-signal]]."} local-signal render/local-signal)
(def ^{:doc "See [[dev.zeko.stube.render/local-bind]]."}   local-bind   render/local-bind)
(def ^{:doc "See [[dev.zeko.stube.render/back-button]]."}  back-button  render/back-button)
(def ^{:doc "See [[dev.zeko.stube.render/render-slot]]."} render-slot render/render-slot)

(def ^{:doc "Sentinel returned by cancellable stock UI components."}
  cancel ui/cancel)

(defn- ensure-ui! []
  (ui/register!))

(defn confirm
  "Return an embed spec for the stock yes/no confirmation component."
  [question]
  (ensure-ui!)
  (embed :ui/confirm {:question question}))

(defn prompt
  "Return an embed spec for the stock text prompt component."
  ([label] (prompt label nil))
  ([label default]
   (ensure-ui!)
   (embed :ui/prompt {:label label :default default})))

(defn choose
  "Return an embed spec for the stock one-of-N choice component."
  ([options] (choose options "Pick one:"))
  ([options caption]
   (ensure-ui!)
   (embed :ui/choose {:options options :caption caption})))

(defn info
  "Return an embed spec for the stock informational OK dialog."
  [text]
  (ensure-ui!)
  (embed :ui/info {:text text}))

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

(def ^{:doc "Return a registered component's docstring, or nil."}
  help registry/help)

;; ---------------------------------------------------------------------------
;; History & persistence (slice 3)
;; ---------------------------------------------------------------------------

(def ^{:doc "An effect that walks one step backward through the
  conversation's history.  Use it from a handler to wire a \"Back\"
  button, or render `(s/back-button \"Back\")` for the stock
  conversation-level button.

  When emitted from a top-level handler, it pops the most recent
  conversation snapshot off `:conv/history` and re-renders the
  restored top frame.  No-op if the history is empty."}
  back [:back])

(def ^{:doc "See [[dev.zeko.stube.store/in-memory-store]]."}  in-memory-store store/in-memory-store)
(def ^{:doc "See [[dev.zeko.stube.store/file-store]]."}        file-store      store/file-store)

;; ---------------------------------------------------------------------------
;; Linear flows (slice 1)
;; ---------------------------------------------------------------------------

(defmacro defflow
  "Define a linear flow component.  See [[dev.zeko.stube.flow/defflow]] for the
  full docstring; this is a thin re-export so application code only ever
  needs `[dev.zeko.stube.core :as s]`."
  [id bindings & body]
  `(dev.zeko.stube.flow/defflow ~id ~bindings ~@body))

;; `await` is brought in via `:refer` above, on purpose; see the require.
;; This explicit attribution exists only to surface it in `(dir 'dev.zeko.stube.core)`.
(alter-meta! #'await assoc
             :dev.zeko.stube.core/re-export 'dev.zeko.stube.flow/await)

;; ---------------------------------------------------------------------------
;; Lifecycle / mounting
;; ---------------------------------------------------------------------------

(def ^{:doc "See [[dev.zeko.stube.server/mount!]]."}    mount!     server/mount!)
(def ^{:doc "See [[dev.zeko.stube.server/unmount!]]."}  unmount!   server/unmount!)
(def ^{:doc "See [[dev.zeko.stube.server/start!]]."}    start!     server/start!)
(def ^{:doc "See [[dev.zeko.stube.server/stop!]]."}     stop!      server/stop!)
(def ^{:doc "See [[dev.zeko.stube.server/active-conversations]]."}
  active-conversations server/active-conversations)
(def ^{:doc "See [[dev.zeko.stube.server/end!]]."}      end!       server/end!)
(def ^{:doc "See [[dev.zeko.stube.server/mounts]]."}    mounts     server/mounts)
(def ^{:doc "See [[dev.zeko.stube.server/inspect]]."}   inspect    server/inspect)

;; ---------------------------------------------------------------------------
;; REPL / test surface
;; ---------------------------------------------------------------------------

(defn- replay-start [baseline]
  (cond
    (map? baseline)
    [baseline []]

    (qualified-keyword? baseline)
    (kernel/run-effects (conv/new-conversation) (kernel/boot baseline))

    :else
    (throw (ex-info "replay baseline must be a conversation map or flow id"
                    {:baseline baseline}))))

(defn- replay-event [conv event]
  (let [event (if (fn? event) (event conv) event)]
    (cond-> event
      (nil? (:instance-id event)) (assoc :instance-id (conv/top-id conv))
      (nil? (:signals event))     (assoc :signals {}))))

(defn replay
  "Replay `events` against a baseline conversation or root flow id.

  Returns `[conv fragments]`, matching [[dispatch]] / [[boot]].  When
  the baseline is a flow id, replay boots a fresh conversation first.
  Event maps may omit `:instance-id` to target the current top frame and
  may omit `:signals` to use `{}`.  An event may also be a function of
  the current conversation returning such a map."
  ([events]
   (replay (conv/new-conversation) events))
  ([baseline events]
   (let [[c0 boot-frags] (replay-start baseline)]
     (reduce (fn [[c frags] event]
               (let [[c' more] (kernel/dispatch c (replay-event c event))]
                 [c' (into frags more)]))
             [c0 (vec boot-frags)]
             events))))

(def ^{:doc "Pure event dispatch — `(dispatch conv event) → [conv' fragments]`.
  Useful from the REPL or for tests; production code goes through the http
  layer."}
  dispatch kernel/dispatch)

(def ^{:doc "Pure boot effects for a flow — see [[dev.zeko.stube.kernel/boot]]."}
  boot kernel/boot)

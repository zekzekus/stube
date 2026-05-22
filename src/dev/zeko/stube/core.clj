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
                  [:form (s/root-attrs self (s/on self :submit))
                   [:label (:text self)]
                   [:input (merge {:name \"answer\"} (s/bind :answer))]
                   [:button \"OK\"]])
        :handle (fn [self _]
                  [(s/answer (parse-long (:answer self)))]))

      (s/defcomponent :demo/guess
        :init   (fn [_] {:target (rand-int 100)})
        :handle (fn [_ _]
                  [(s/call :ui/prompt {:text \"Guess 1–100\"} :on-guess)])
        :on-guess (fn [self n]
                    (cond
                      (< n (:target self))
                      [(s/call :ui/prompt {:text \"Too low\"} :on-guess)]
                      :else
                      [(s/end {:winner true})])))

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
            [dev.zeko.stube.effects      :as effects]
            ;; `:refer [await]` is intentional — it makes `dev.zeko.stube.core/await`
            ;; resolve to the *same var* as `dev.zeko.stube.flow/await`, which is
            ;; required for cloroutine to recognise `(s/await …)` calls in
            ;; user `defflow` bodies as suspend points (cloroutine compares
            ;; vars by identity, not by value).
            [dev.zeko.stube.flow         :as flow :refer [await]]
            [dev.zeko.stube.halos        :as halos]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render]
            [dev.zeko.stube.server       :as server]
            [dev.zeko.stube.store        :as store]
            [dev.zeko.stube.ui           :as ui]))

;; ---------------------------------------------------------------------------
;; Component definition
;; ---------------------------------------------------------------------------

(def ^:private colocated-keys
  "Top-level `defcomponent` keys lifted to their `:component/<name>`
  homes so the kernel finds them."
  [:init :render :handle :keep :doc])

(defn- lift-colocated [opts]
  (reduce (fn [m k]
            (if (contains? m k)
              (-> m
                  (assoc (keyword "component" (name k)) (get m k))
                  (dissoc k))
              m))
          opts
          colocated-keys))

(defn register-component!
  "Plain function form of [[defcomponent]].  Two arities:

      (register-component! id opts)
      (register-component! id opts source-map)

  `source-map` is `{:file ... :line ...}` to be attached under
  `:component/source` (so the halos dev tool can jump to a definition).
  The macro supplies it from call-site `&form` meta; hand-rolled
  data-driven callers can pass nil."
  ([id opts]
   (register-component! id opts nil))
  ([id opts source]
   (registry/register!
     (cond-> (-> opts lift-colocated (assoc :component/id id))
       source (assoc :component/source source)))))

(defmacro defcomponent
  "Define a component and add it to the global registry.

      (s/defcomponent :auth/login
        :doc    \"Prompt for credentials.\"
        :init   (fn [args] state-map)
        :keep   #{:signal-keys}            ;; optional
        :render (fn [self] hiccup)
        :handle (fn [self event]           ;; event is {:event …, :signals …}
                  [self' effects])
        :on-foo (fn [self answer-value]    ;; resume keys, optional
                  [self' effects]))

  The macro captures the call-site `:file`/`:line` so the halos dev
  tool can jump to the definition.  Use [[register-component!]] from
  data-driven code that prefers a function shape."
  [id & opts]
  ;; `*file*` is bound during compilation, so unquote it here to embed
  ;; the literal source path of the call site into the expansion.
  ;; Without the unquote we'd emit a reference to `*file*` itself,
  ;; which reads "NO_SOURCE_PATH" at eval time.
  `(register-component! ~id
                        (hash-map ~@opts)
                        {:file ~*file* :line ~(:line (meta &form))}))

;; ---------------------------------------------------------------------------
;; Effect constructors
;; ---------------------------------------------------------------------------
;;
;; Handlers and lifecycle hooks emit effect vectors.  Use these named
;; constructors instead of writing the wire-shape vectors by hand:
;;
;;     [self [(s/call :ui/prompt {:text "Hi"} :on-pick)]]
;;     [self [(s/answer total)]]
;;     [self [(s/patch [:p "done"])]]
;;
;; The wire format is unchanged — `(s/call ...)` returns the same
;; `[:call <embed> :resume <k>]` vector the kernel has always understood
;; — so existing literal-vector code keeps working.

(defn call
  "Push a child onto the stack.  On `:answer`, the parent's resume key
  is invoked with the answered value.

      (s/call :ui/prompt)                  ; no args, no resume
      (s/call :ui/prompt :on-pick)         ; no args, resume :on-pick
      (s/call :ui/prompt {:text \"Hi\"})     ; args, no resume
      (s/call :ui/prompt {:text \"Hi\"} :on-pick) ; args + resume

  Wraps the component id with [[embed]] internally; pass an existing
  embed spec via [[dev.zeko.stube.effects/call]] if you already have one."
  ([component-id]
   (effects/call (conv/embed component-id)))
  ([component-id args-or-resume]
   (if (keyword? args-or-resume)
     (effects/call (conv/embed component-id) args-or-resume)
     (effects/call (conv/embed component-id args-or-resume))))
  ([component-id args resume]
   (effects/call (conv/embed component-id args) resume)))

(defn become
  "Pop the current frame and push another in its place (Seaside
  `become:`).  The replacement inherits the original parent linkage
  and resume key, so an eventual `:answer` still flows back to whoever
  called the original frame.

      (s/become :wizard/step-2)
      (s/become :wizard/step-2 {:from data})

  Named `become` instead of `replace` to avoid shadowing
  `clojure.core/replace`."
  ([component-id]
   (effects/replace (conv/embed component-id)))
  ([component-id args]
   (effects/replace (conv/embed component-id args))))

(defn call-in-slot
  "Temporarily swap an embedded slot's child; the new child answers
  back to the parent without taking over the page.

      (s/call-in-slot :slot/main :feature/edit)
      (s/call-in-slot :slot/main :feature/edit {:id 7} :on-saved)"
  ([slot component-id]
   (effects/call-in-slot slot (conv/embed component-id)))
  ([slot component-id args-or-resume]
   (if (keyword? args-or-resume)
     (effects/call-in-slot slot (conv/embed component-id) args-or-resume)
     (effects/call-in-slot slot (conv/embed component-id args-or-resume))))
  ([slot component-id args resume]
   (effects/call-in-slot slot (conv/embed component-id args) resume)))

(def ^{:doc "Pop this frame; deliver `value` to the parent under its
  resume key.  See [[dev.zeko.stube.effects/answer]]."}
  answer effects/answer)

(def ^{:doc "Emit an extra DOM patch without changing the stack.
  See [[dev.zeko.stube.effects/patch]]."}
  patch effects/patch)

(def ^{:doc "Push a Datastar signal patch (writes signal values back
  to the browser).  See [[dev.zeko.stube.effects/patch-signals]]."}
  patch-signals effects/patch-signals)

(def ^{:doc "Run literal JS in the browser.  Last-resort escape hatch;
  prefer Datastar attributes for most needs.  See
  [[dev.zeko.stube.effects/execute-script]]."}
  execute-script effects/execute-script)

(def ^{:doc "Fire-and-forget `(thunk)` off the request thread.
  See [[dev.zeko.stube.effects/io]]."}
  io effects/io)

(def ^{:doc "Terminate the conversation with a final value.  After
  this the SSE channel closes and the conversation is forgotten.
  See [[dev.zeko.stube.effects/end]]."}
  end effects/end)

;; ---------------------------------------------------------------------------
;; Compositional helpers
;; ---------------------------------------------------------------------------

(def ^{:doc "See [[dev.zeko.stube.conversation/embed]]."}
  embed conv/embed)

(def ^{:doc "See [[dev.zeko.stube.render/on]]."}          on          render/on)
(def ^{:doc "See [[dev.zeko.stube.render/bind]]."}        bind        render/bind)
(def ^{:doc "See [[dev.zeko.stube.render/root-attrs]]."}  root-attrs  render/root-attrs)
(def ^{:doc "See [[dev.zeko.stube.render/local-signal]]."} local-signal render/local-signal)
(def ^{:doc "See [[dev.zeko.stube.render/local-bind]]."}   local-bind   render/local-bind)
(def ^{:doc "See [[dev.zeko.stube.render/back-button]]."}  back-button  render/back-button)
(def ^{:doc "See [[dev.zeko.stube.render/upload-attrs]]."} upload-attrs render/upload-attrs)
(def ^{:doc "See [[dev.zeko.stube.render/upload-frame]]."} upload-frame render/upload-frame)
(def ^{:doc "See [[dev.zeko.stube.render/render-slot]]."} render-slot render/render-slot)

(defn context
  "Return adapter/application context injected into this conversation.

  Embedders pass `:context-fn` to `dev.zeko.stube.kernel/make-kernel`;
  handlers and lifecycle hooks can then call `(s/context self)` to reach
  request-scoped dependencies such as a database handle."
  [self]
  (:stube/context self))

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
  behavioural composition.

  See [[decorate!]] for the register-on-the-way variant."
  [base-cdef overrides]
  (merge base-cdef
         (if (fn? overrides) (overrides base-cdef) overrides)))

(defn decorate!
  "Like [[decorate]] but also registers the resulting cdef and returns
  the registered map.  Convenient for the common case:

      (s/decorate! (s/registry-lookup :booking/wizard)
        {:component/id     :booking/wizard-with-banner
         :component/render
         (fn [self]
           [:div (s/root-attrs self)
            [:header.banner \"Welcome\"]
            ((:component/render (s/registry-lookup :booking/wizard)) self)])})

  Use [[decorate]] when you want to inspect or further modify the cdef
  before deciding whether to register it."
  [base-cdef overrides]
  (registry/register! (decorate base-cdef overrides)))

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
  back effects/back)

(def ^{:doc "See [[dev.zeko.stube.store/in-memory-store]]."}  in-memory-store store/in-memory-store)
(def ^{:doc "See [[dev.zeko.stube.store/file-store]]."}        file-store      store/file-store)

;; ---------------------------------------------------------------------------
;; Async / publish-subscribe (Tier 3)
;; ---------------------------------------------------------------------------

(def ^{:doc "Effect: dispatch `route-event` to the current instance after
  `delay-ms`.

      [self [(s/after 1000 :tick)]]

  If the conversation or instance is gone when the timer fires, the event
  is dropped.  `route-event` accepts the same keyword/vector shape as
  `(s/on self :click :as route-event)`."}
  after effects/after)

(def ^{:doc "Effect: subscribe the current instance to `topic`.

  Published messages arrive as `route-event` with the published value in
  `:payload`.  Re-emit this from `:wakeup` for components that should
  resubscribe after crash-resume."}
  subscribe effects/subscribe)

(def ^{:doc "Effect: remove this instance's topic subscription(s)."}
  unsubscribe effects/unsubscribe)

(def ^{:doc "Publish `msg` to every live instance subscribed to `topic`.
  Delivery is asynchronous and cid/iid-scoped; stale subscribers are
  ignored.  Returns the number of subscribers targeted."}
  publish! server/publish!) ;; re-export through server which re-exports from async

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
;; Halos / dev-tooling REPL helpers (slice 0)
;; ---------------------------------------------------------------------------
;;
;; `inspect` (above) prints a compact summary; these surface the smaller
;; views we wanted from the halos plan. All four are also rendered into
;; the in-browser side-panel when halos are enabled.

(defn tree
  "Pretty-print the component tree for live conversation `cid` and
  return the tree data. nil if the conversation is unknown."
  [cid]
  (when-let [c (server/conversation cid)]
    (halos/tree c)))

(defn instance
  "Return the instance map for `iid` in live conversation `cid`."
  [cid iid]
  (some-> (server/conversation cid) (halos/instance iid)))

(defn history
  "Summarise `:conv/history` for live conversation `cid`."
  [cid]
  (some-> (server/conversation cid) halos/history))

(defn where
  "Return the source location captured for component `type-kw` at
  `defcomponent` time, or nil."
  [type-kw]
  (halos/where type-kw))

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

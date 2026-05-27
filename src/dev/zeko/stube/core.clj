(ns dev.zeko.stube.core
  "Public API of the stube framework.

  Most users only need this namespace.  It re-exports the small set of
  functions that are intended to be called from application code; the
  internals live in [[dev.zeko.stube.kernel]], [[dev.zeko.stube.conversation]],
  [[dev.zeko.stube.registry]], [[dev.zeko.stube.render]], [[dev.zeko.stube.runtime]],
  [[dev.zeko.stube.http]] and [[dev.zeko.stube.server]].  Hosts embedding stube
  in their own Ring app reach for [[dev.zeko.stube.embed]] and
  [[dev.zeko.stube.adapter.ring]].

  ----------------------------------------------------------------------
  At a glance
  ----------------------------------------------------------------------

      (require '[dev.zeko.stube.core :as s])

      (s/defcomponent :demo/prompt-number
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
                  [(s/call :demo/prompt-number {:text \"Guess 1–100\"} :on-guess)])
        :on-guess (fn [self n]
                    (cond
                      (< n (:target self))
                      [(s/call :demo/prompt-number {:text \"Too low\"} :on-guess)]
                      :else
                      [(s/end {:winner true})])))

      (s/mount! \"/guess\" :demo/guess)
      (s/start! {:port 8080})

  ----------------------------------------------------------------------
  Stability of this surface
  ----------------------------------------------------------------------
  Everything in this namespace is intended to remain stable across
  framework versions.  Host-framework integration also has a stable
  surface in [[dev.zeko.stube.embed]] / [[dev.zeko.stube.adapter.ring]].
  Other namespaces are internal until the framework reaches 1.0."
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
            [dev.zeko.stube.http         :as http]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.keyed        :as keyed]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render]
            [dev.zeko.stube.server       :as server]
            [dev.zeko.stube.store        :as store]
            [dev.zeko.stube.ui           :as ui]))

;; ---------------------------------------------------------------------------
;; Re-export plumbing
;; ---------------------------------------------------------------------------

(defmacro ^:private defalias
  "Define `sym` as a value alias of `target-sym`, carrying `doc` and
  (when the target is a function) the target's `:arglists`.  Plain
  `(def ^{:doc \"...\"} foo target/foo)` drops `:arglists` and so loses
  the signature from `(doc s/foo)` / CIDER eldoc; this macro keeps it."
  [sym target-sym doc]
  (let [arglists (:arglists (meta (resolve target-sym)))]
    `(def ~(with-meta sym (cond-> {:doc doc}
                            arglists (assoc :arglists (list 'quote arglists))))
       ~target-sym)))

;; ---------------------------------------------------------------------------
;; Component definition
;; ---------------------------------------------------------------------------

(defn register-component!
  "Plain function form of [[defcomponent]].  Two arities:

      (register-component! id opts)
      (register-component! id opts source-map)

  `source-map` is `{:file ... :line ...}` to be attached under
  `:component/source` (so the halos dev tool can jump to a definition).
  The macro supplies it from call-site `&form` meta; hand-rolled
  data-driven callers can pass nil.

  `registry/register!` lifts colocated author keys (`:init`, `:render`,
  `:handle`, `:keep`, `:doc`, `:state`, `:start`, `:stop`, `:wakeup`,
  `:children`, `:url`) to `:component/<name>` so cdefs are uniform
  regardless of which entry point produced them."
  ([id opts]
   (register-component! id opts nil))
  ([id opts source]
   (registry/register!
     (cond-> (assoc opts :component/id id)
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

(defn- embed-spec? [x]
  (and (map? x) (contains? x :embed/type)))

(defn- ->embed [component-or-embed]
  (if (embed-spec? component-or-embed)
    component-or-embed
    (conv/embed component-or-embed)))

(defn- ->embed-with-args [component-or-embed args]
  (if (embed-spec? component-or-embed)
    (throw (ex-info (str "stube embed specs already include args; "
                         "pass either an embed spec or component id + args")
                    {:embed component-or-embed
                     :args  args}))
    (conv/embed component-or-embed args)))

(defn call
  "Push a child onto the stack.  On `:answer`, the parent's resume key
  is invoked with the answered value.

      (s/call :ui/prompt)                  ; no args, no resume
      (s/call :ui/prompt :on-pick)         ; no args, resume :on-pick
      (s/call :ui/prompt {:text \"Hi\"})     ; args, no resume
      (s/call :ui/prompt {:text \"Hi\"} :on-pick) ; args + resume
      (s/call (s/prompt \"Name?\") :on-pick) ; existing embed spec

  Wraps component ids with [[embed]] internally.  Passing an existing
  embed spec is useful with stock UI helpers and `defflow`-style helper
  functions."
  ([component-id-or-embed]
   (effects/call (->embed component-id-or-embed)))
  ([component-id-or-embed args-or-resume]
   (if (keyword? args-or-resume)
     (effects/call (->embed component-id-or-embed) args-or-resume)
     (effects/call (->embed-with-args component-id-or-embed args-or-resume))))
  ([component-id-or-embed args resume]
   (effects/call (->embed-with-args component-id-or-embed args) resume)))

(defn become
  "Pop the current frame and push another in its place (Seaside
  `become:`).  The replacement inherits the original parent linkage
  and resume key, so an eventual `:answer` still flows back to whoever
  called the original frame.

      (s/become :wizard/step-2)
      (s/become :wizard/step-2 {:from data})
      (s/become (s/embed :wizard/step-2 {:from data}))

  Named `become` instead of `replace` to avoid shadowing
  `clojure.core/replace`."
  ([component-id-or-embed]
   (effects/replace (->embed component-id-or-embed)))
  ([component-id-or-embed args]
   (effects/replace (->embed-with-args component-id-or-embed args))))

(defn call-in-slot
  "Temporarily swap an embedded slot's child; the new child answers
  back to the parent without taking over the page.

      (s/call-in-slot :slot/main :feature/edit)
      (s/call-in-slot :slot/main :feature/edit {:id 7} :on-saved)
      (s/call-in-slot :slot/main (s/embed :feature/edit {:id 7}) :on-saved)"
  ([slot component-id-or-embed]
   (effects/call-in-slot slot (->embed component-id-or-embed)))
  ([slot component-id-or-embed args-or-resume]
   (if (keyword? args-or-resume)
     (effects/call-in-slot slot (->embed component-id-or-embed) args-or-resume)
     (effects/call-in-slot slot (->embed-with-args component-id-or-embed args-or-resume))))
  ([slot component-id-or-embed args resume]
   (effects/call-in-slot slot (->embed-with-args component-id-or-embed args) resume)))

(defalias answer effects/answer
  "Pop this frame; deliver `value` to the parent under its resume key.
  See [[dev.zeko.stube.effects/answer]].")

(defalias answer-error effects/answer-error
  "Pop this frame and route the exception through the parent's
  `:on-error-<key>` resume handler instead of `:on-<key>`.

  If the parent declares only `:on-<key>`, the kernel falls back to
  it with a wrapped value `[:error ex]` and logs a one-time
  deprecation warning per component.  If neither is declared, the
  parent surfaces a default error banner just like an intra-component
  throw.  See [[dev.zeko.stube.effects/answer-error]].")

(defalias patch effects/patch
  "Emit an extra DOM patch without changing the stack.
  See [[dev.zeko.stube.effects/patch]].")

(defalias patch-signals effects/patch-signals
  "Push a Datastar signal patch (writes signal values back to the
  browser).  See [[dev.zeko.stube.effects/patch-signals]].")

(defalias execute-script effects/execute-script
  "Run literal JS in the browser.  Last-resort escape hatch; prefer
  Datastar attributes for most needs.  See
  [[dev.zeko.stube.effects/execute-script]].")

(defalias history effects/history
  "Sync the browser URL without a page reload.

      (s/history :replace \"/notes?id=42\")
      (s/history :push    \"/notes/42\")

  `:replace` calls `history.replaceState`; `:push` calls
  `history.pushState`.  Use `:replace` for in-place state mutations
  (e.g. filter changes) and `:push` for navigations the user should
  be able to Back-button out of.

  See [[dev.zeko.stube.effects/history]].")

(defalias io effects/io
  "Ask the active runtime to run `(thunk)` off the request thread,
  fire-and-forget.  Pure `dispatch`/`replay` leave this effect inert
  unless a runtime hook is bound.  See [[dev.zeko.stube.effects/io]].")

(defalias end effects/end
  "Terminate the conversation with a final value.  After this the SSE
  channel closes and the conversation is forgotten.  See
  [[dev.zeko.stube.effects/end]].")

;; ---------------------------------------------------------------------------
;; Compositional helpers
;; ---------------------------------------------------------------------------

(defalias embed conv/embed
  "See [[dev.zeko.stube.conversation/embed]].")

(defalias on           render/on           "See [[dev.zeko.stube.render/on]].")
(defalias on-target    render/on-target    "See [[dev.zeko.stube.render/on-target]].")
(defalias on-parent    render/on-parent    "See [[dev.zeko.stube.render/on-parent]].")
(defalias instance-id  render/instance-id  "See [[dev.zeko.stube.render/instance-id]].")
(defalias event-url    render/event-url    "See [[dev.zeko.stube.render/event-url]].")
(defalias bind         render/bind         "See [[dev.zeko.stube.render/bind]].")
(defalias root-attrs   render/root-attrs   "See [[dev.zeko.stube.render/root-attrs]].")
(defalias preserve     render/preserve     "See [[dev.zeko.stube.render/preserve]].")
(defalias on-mount     render/on-mount     "See [[dev.zeko.stube.render/on-mount]].")
(defalias on-unmount   render/on-unmount   "See [[dev.zeko.stube.render/on-unmount]].")
;; `render/local-signal` is itself a re-export of `conv/local-signal`, which
;; doesn't carry `:arglists` on the render var — point at the source so
;; `defalias` finds the signature.
(defalias local-signal conv/local-signal   "See [[dev.zeko.stube.conversation/local-signal]].")
(defalias local-bind   render/local-bind   "See [[dev.zeko.stube.render/local-bind]].")
(defalias back-button  render/back-button  "See [[dev.zeko.stube.render/back-button]].")
(defalias upload-attrs render/upload-attrs "See [[dev.zeko.stube.render/upload-attrs]].")
(defalias upload-frame render/upload-frame "See [[dev.zeko.stube.render/upload-frame]].")
(defalias render-slot  render/render-slot  "See [[dev.zeko.stube.render/render-slot]].")

(defalias set-keyed-children effects/set-keyed-children
  "Effect: reconcile keyed children for a slot.  See
  [[dev.zeko.stube.effects/set-keyed-children]] and the diff semantics
  documented in [[dev.zeko.stube.keyed]].")

(defn keyed-children
  "Render the keyed-children container for `slot` on `self`.  Returns
  hiccup `[:div {:id …} child-hiccup…]` where each child instance's
  `:render` is inlined in the declared order.

      :handle (fn [self _]
                [self [(s/set-keyed-children
                         :slot/cols
                         (map (fn [id] [id (s/embed :demo/counter)])
                              (:col-ids self)))]])

      :render (fn [self]
                [:section (s/root-attrs self)
                 (s/keyed-children self :slot/cols)])"
  [self slot]
  (into [:div {:id (keyed/container-id (:instance/id self) slot)}]
        (keyed/render-children-hiccup self slot)))

(defn context
  "Return adapter/application context injected into this conversation.

  Embedders pass `:context-fn` to `dev.zeko.stube.embed/make-kernel`;
  handlers and lifecycle hooks can then call `(s/context self)` to reach
  request-scoped dependencies such as a database handle."
  [self]
  (:stube/context self))

(defn app
  "Return the opaque host-app value the embedder attached to the kernel
  via `:app`.  Typically a small map of dependencies such as
  `{:db ds :auth-fn (fn [request] ...)}`.

  Returns `nil` outside a runtime dispatch/render (e.g. when component
  code is exercised through `core/replay` in a unit test).  Component
  tests that need a stand-in can wrap the call site with
  [[with-app]]."
  []
  kernel/*current-app*)

(defn principal
  "Return the authenticated principal the embedder stamped onto this
  conversation via `:principal-fn` at mint time.

  Returns `nil` for anonymous conversations.  The principal is fixed
  for the life of the conversation — to refresh it (post-login, after
  account switching), end the conversation and re-mint.

  Tests that need a stand-in can wrap the call site with
  [[with-principal]]."
  []
  kernel/*current-principal*)

(defmacro with-app
  "Run `body` with `(s/app)` returning `app-value`.  Intended for
  component-author tests that exercise handler/render code through
  [[replay]] or [[dispatch]] without a running runtime.

      (s/with-app {:db stub-conn}
        (s/replay :my/component events))"
  [app-value & body]
  `(binding [kernel/*current-app* ~app-value]
     ~@body))

(defmacro with-principal
  "Run `body` with `(s/principal)` returning `principal-value`.  Pairs
  with [[with-app]] for tests that need a stand-in principal without
  minting a real conversation."
  [principal-value & body]
  `(binding [kernel/*current-principal* ~principal-value]
     ~@body))

(defalias cancel ui/cancel
  "Sentinel returned by cancellable stock UI components.")

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

(defalias registry-lookup registry/lookup
  "Look up a registered component definition by id (or nil).")

(defalias help registry/help
  "Return a registered component's docstring, or nil.")

;; ---------------------------------------------------------------------------
;; History & persistence (slice 3)
;; ---------------------------------------------------------------------------

(defalias back effects/back
  "Construct a `[:back]` effect that walks one step backward through
  the conversation's `:conv/history`.  Use it from a handler to wire
  a \"Back\" button, or render `(s/back-button \"Back\")` for the
  stock conversation-level button.

      [(s/back)]   ; from inside a handler

  When emitted from a top-level handler, it pops the most recent
  conversation snapshot off `:conv/history` and re-renders the
  restored top frame.  No-op if the history is empty.

  `s/back` is a zero-arity function, matching every other effect
  constructor (`s/end`, `s/after`, etc.).  Always call it with parens.")

(defalias in-memory-store store/in-memory-store "See [[dev.zeko.stube.store/in-memory-store]].")
(defalias file-store      store/file-store      "See [[dev.zeko.stube.store/file-store]].")

;; ---------------------------------------------------------------------------
;; Async / publish-subscribe (Tier 3)
;; ---------------------------------------------------------------------------

(defalias after effects/after
  "Effect: dispatch `route-event` to the current instance after
  `delay-ms`.

      [self [(s/after 1000 :tick)]]

  If the conversation or instance is gone when the timer fires, the
  event is dropped.  `route-event` accepts the same keyword/vector
  shape as `(s/on self :click :as route-event)`.")

(defalias subscribe effects/subscribe
  "Effect: subscribe the current instance to `topic`.

  Published messages arrive as `route-event` with the published value
  in `:payload`.  Re-emit this from `:wakeup` for components that
  should resubscribe after crash-resume.")

(defalias unsubscribe effects/unsubscribe
  "Effect: remove this instance's topic subscription(s).")

(defalias publish! server/publish!
  "Publish `msg` to every live instance subscribed to `topic`.
  Delivery is asynchronous and cid/iid-scoped; stale subscribers are
  ignored.  From component code this targets the active runtime
  kernel; outside a dispatch it targets the standalone server kernel.
  Returns the number of subscribers targeted.")

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

(defalias mount! server/mount!
  "See [[dev.zeko.stube.server/mount!]].  Accepts an optional opts map
  with `:init-args-fn` to seed component state from GET request
  params.")

(defalias unmount! server/unmount! "See [[dev.zeko.stube.server/unmount!]].")
(defalias start!   server/start!   "See [[dev.zeko.stube.server/start!]].")
(defalias stop!    server/stop!    "See [[dev.zeko.stube.server/stop!]].")

(defalias query-value http/query-value
  "Return the decoded value of query-param `param-name` from a Ring
  request, or nil if absent.  Useful in `:init-args-fn` callbacks:

      (s/mount! \"/counter\" :demo/counter
        {:init-args-fn (fn [req]
                         {:n (parse-long (or (s/query-value req \"n\") \"0\"))})})

  See [[dev.zeko.stube.http/query-value]].")

(defalias active-conversations server/active-conversations
  "See [[dev.zeko.stube.server/active-conversations]].")
(defalias end!    server/end!    "See [[dev.zeko.stube.server/end!]].")
(defalias mounts  server/mounts  "See [[dev.zeko.stube.server/mounts]].")
(defalias inspect server/inspect "See [[dev.zeko.stube.server/inspect]].")

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

(defn conv-history
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
               (let [[c' more] (kernel/dispatch c (conv/replay-event c event))]
                 [c' (into frags more)]))
             [c0 (vec boot-frags)]
             events))))

(defalias dispatch kernel/dispatch
  "Pure event dispatch — `(dispatch conv event) → [conv' fragments]`.
  Useful from the REPL or for tests; production code goes through the
  http layer.")

(defalias boot kernel/boot
  "Pure boot effects for a flow — see [[dev.zeko.stube.kernel/boot]].")

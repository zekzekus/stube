(ns ^{:dev.zeko.stube/rationale
      "After the Phase 5 split (lifecycle.clj, frame.clj, fragments.clj,
       effects.clj all extracted), the kernel still hosts the full step
       multimethod plus dispatch.  The step methods carry the semantics
       of embedding, history rewind, slot-local calls, and the resume-
       parent continuation; together with their cond-on-elements
       branches they push the file over the §15.4 350-line budget.
       Keep the single-multimethod shape; further splitting would break
       readability of the effect language without a clear runtime win."}
    dev.zeko.stube.kernel
  "The pure runtime: `step`, `run-effects`, `dispatch`.

  Reading guide
  ─────────────

  Everything in this namespace operates on plain values.  A handler
  returns `[self' effects]`; `run-effects` folds those effects into the
  conversation, producing the next conversation value and the list of
  *fragments* that must be pushed to the browser.  A fragment is just a
  small map describing one Datastar event — see [[fragment-shape]] for
  the schema.

  No I/O, no atoms, no SSE — that all lives one layer up in
  [[dev.zeko.stube.http]] / [[dev.zeko.stube.server]].  This means the entire interaction
  loop is testable from a REPL with `dispatch` and a hand-built
  conversation value, with no server running.

  Effect vocabulary
  ─────────────────

      [:call <embed> :resume <k>]   push a child frame; on `:answer` the
                                    parent's `:k` function is invoked
      [:call-in-slot <slot> <embed> :resume <k>]
                                    temporarily swap one embedded slot;
                                    the child answers back to the parent
                                    without taking over the page
      [:answer <value>]             pop this frame; deliver `value` to
                                    the parent under its resume key
      [:replace <embed>]            pop this frame and push another in
                                    its place (Seaside `become:`)
      [:patch <hiccup>]             extra DOM patch (no stack change)
      [:patch-signals <map>]        push a Datastar signal patch
      [:execute-script <js>]        run literal JS in the browser
      [:history :replace|:push url]  sync browser URL (replaceState/pushState)
      [:io <fn>]                    call `(fn)` off-thread (fire-and-forget)
      [:after ms event]             schedule a future event for this instance
      [:subscribe topic event]      subscribe this instance to published messages
      [:unsubscribe topic?]         remove this instance's topic subscription(s)
      [:back]                       restore the previous conversation
                                    from `:conv/history` (slice 3)
      [:end <value>]                terminate the conversation

  All effects produce zero or more fragments and an updated conversation.

  Component lifecycle keys
  ────────────────────────
  Beyond the keys read directly by `:render` / `:handle`, a component
  may include:

      :start  (fn [self] [self' effects])
      :stop   (fn [self] [self' effects])
      :wakeup (fn [self] [self' effects])

  `:start` runs once immediately after instantiation for both stack
  frames and embedded children, which lets \"task\" components launch
  their first `:call` without a synthetic user event and lets widgets
  subscribe or schedule timers.  `:stop` runs just before a frame/subtree
  is removed; `:wakeup` runs when a persisted or history-restored frame
  becomes live again."
  (:require [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.effects      :as e]
            [dev.zeko.stube.errors       :as errors]
            [dev.zeko.stube.fragments    :as f]
            [dev.zeko.stube.frame        :as frame]
            [dev.zeko.stube.lifecycle    :as lc]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render]))

;; ---------------------------------------------------------------------------
;; Kernel-bound hooks (set by the server)
;; ---------------------------------------------------------------------------

(defn- default-handle
  "Default handler: ignore the event, no effects."
  [self _event]
  [self []])

(defn- rendered-output?
  "True when `frags` contains a fragment that has already drawn the
  current instance — a normal `:elements` patch or an `:error` banner
  from the [[dev.zeko.stube.errors]] catch.  Either way the kernel
  must not re-render on top of it."
  [frags]
  (boolean (some #(#{:elements :error} (:fragment/kind %)) frags)))

(defn- effect-origin [conv]
  (or e/*effect-iid* (conv/top-id conv)))

(defn- attach-context
  "Carry conversation context onto freshly-instantiated instances so
  handlers and lifecycle hooks can read it through `s/context`."
  [conv inst]
  (cond-> inst
    (contains? conv :conv/context)
    (assoc :stube/context (:conv/context conv))))

(defn- attach-tree-context [conv inst descendants]
  [(attach-context conv inst)
   (mapv #(attach-context conv %) descendants)])

(def ^:dynamic *schedule-event!*
  "Optional side-effect hook bound by the server while folding effects.
  The kernel stays ignorant of threads and SSE; it only describes the
  delayed event that should be sent later."
  nil)

(def ^:dynamic *subscribe!*
  "Optional side-effect hook bound by the server for `[:subscribe …]`."
  nil)

(def ^:dynamic *unsubscribe!*
  "Optional side-effect hook bound by the server for `[:unsubscribe …]`."
  nil)

(declare run-effects)

;; ---------------------------------------------------------------------------
;; Lifecycle / frame thin wrappers
;; ---------------------------------------------------------------------------
;;
;; Lifecycle and frame each take `run-effects` as a parameter to keep
;; the dep direction one-way (they don't require the kernel).  Hide the
;; threading from step methods with these one-liners.

(defn- run-start-hooks  [conv iids] (lc/run-start-hooks  run-effects conv iids))
(defn- run-stop-hooks   [conv iids] (lc/run-stop-hooks   run-effects conv iids))
(defn- run-wakeup-hooks [conv iids] (lc/run-wakeup-hooks run-effects conv iids))

(def ^:private render-frame    frame/render-frame)
(def ^:private render-instance frame/render-instance)

(defn resume-top
  "Run `:wakeup` for the current top frame and render it as a restored
  frame.  Used by the http layer when a persisted conversation reattaches."
  [conv]
  (if-let [iid (conv/top-id conv)]
    (let [conv'          (assoc-in conv [:conv/instances iid :instance/rendered?] false)
          [conv'' wake]  (run-wakeup-hooks conv' (conv/descendant-ids conv' iid))
          [conv''' frag] (render-frame conv'' iid)]
      [conv''' (conj (vec wake) frag)])
    [conv []]))

(defn redraw-top
  "Re-render the current top frame in-place without running `:wakeup`
  or snapshotting.  Returns `[conv' [frag]]`.  Used by dev-tooling
  paths (e.g. enabling halos on a live conversation) that want a fresh
  frame against the *current* state."
  [conv]
  (if-let [iid (conv/top-id conv)]
    (let [conv'       (assoc-in conv [:conv/instances iid :instance/rendered?] false)
          [conv'' fr] (render-frame conv' iid)]
      [conv'' [fr]])
    [conv []]))

;; ---------------------------------------------------------------------------
;; The step function — one effect at a time
;; ---------------------------------------------------------------------------

(defmulti ^:private step
  "Apply a single effect to the conversation and return
  `[conv' fragments]`.  Implemented as a multimethod keyed on the effect
  tag so user code can extend the vocabulary later (slice 4)."
  (fn [_conv [op & _]] op))

(defmethod step :call
  [conv eff]
  (let [embed-spec (e/call-embed eff)
        resume     (e/call-resume eff)
        parent-id (conv/top-id conv)
        cdef      (registry/lookup! (:embed/type embed-spec))
        ;; `instantiate-tree` materialises the called component *and*
        ;; every child its `:children` map declares.  Children live in
        ;; `:conv/instances` alongside their parent but stay off the
        ;; stack — they are addressed through `:instance/children`,
        ;; not through call/answer.
        [inst descendants]
        (apply attach-tree-context conv
               (conv/instantiate-tree cdef embed-spec parent-id resume registry/lookup!))
        conv'     (-> conv
                      (conv/push-instance inst)
                      (conv/put-many descendants))
        iid       (:instance/id inst)]
    ;; `:start` runs for the whole instantiated subtree.  Stack/task
    ;; components still get the old behaviour, while embedded children can
    ;; subscribe or schedule timers as soon as their parent is booted.
    (let [[conv'' fr] (run-start-hooks conv'
                                       (into [iid] (map :instance/id descendants)))]
      ;; Three outcomes after `:start`:
      ;;  1. it produced HTML directly (e.g. via `:call`-ing a child);
      ;;     we don't add a placeholder render on top.
      ;;  2. it `:answer`-ed or `:end`-ed and the frame is gone;
      ;;     there is nothing here to render.  This is the common
      ;;     `defflow` case where the body had zero `await`s and
      ;;     completed synchronously.
      ;;  3. the frame is still here with no HTML yet; render the
      ;;     placeholder so the page sees *something*.
      (cond
        (rendered-output? fr)
        [conv'' fr]

        (nil? (conv/instance conv'' iid))
        [conv'' fr]

        :else
        (let [[c f] (render-frame conv'' iid)]
          [c (conj (vec fr) f)])))))

(defmethod step :call-in-slot
  [conv eff]
  (let [slot       (e/slot-call-slot eff)
        embed-spec (e/slot-call-embed eff)
        resume     (e/slot-call-resume eff)
        parent-id (or (effect-origin conv)
                      (throw (ex-info ":call-in-slot needs an emitting parent"
                                      {:slot slot})))
        parent    (or (conv/instance conv parent-id)
                      (throw (ex-info ":call-in-slot parent is missing"
                                      {:parent parent-id
                                       :slot   slot})))
        old-iid   (get-in parent [:instance/children slot])
        cdef      (registry/lookup! (:embed/type embed-spec))
        [inst descendants]
        (apply attach-tree-context conv
               (conv/instantiate-tree cdef embed-spec parent-id resume registry/lookup!))
        inst      (assoc inst
                         :instance/slot slot
                         :instance/previous old-iid)
        iid       (:instance/id inst)
        conv'     (-> conv
                      (conv/set-child-slot parent-id slot iid)
                      (conv/put-instance inst)
                      (conv/put-many descendants))]
    (let [[conv'' fr] (run-start-hooks conv'
                                       (into [iid] (map :instance/id descendants)))]
      (cond
        (rendered-output? fr)
        [conv'' fr]

        (nil? (conv/instance conv'' iid))
        [conv'' fr]

        :else
        (let [[c f] (frame/render-slot-overlay conv'' parent-id old-iid iid)]
          [c (conj (vec fr) f)])))))

(defn- resume-parent
  "Deliver `value` to `parent-id` under `resume-key`, then render the
  parent if the resume effects did not already produce elements.

  Both stack calls and slot-local calls share this continuation path;
  only the way the answering child is removed differs."
  [conv parent-id resume-key value]
  (cond
    ;; Answering the root means the conversation is done.  We translate
    ;; this into an `:end` so behaviour is consistent.
    (nil? parent-id)
    (step conv (e/end value))

    ;; Bare child answer with no resume key — nothing to deliver to the
    ;; parent.  Just re-render the parent so the user sees something.
    (nil? resume-key)
    (let [[c' f] (render-frame conv parent-id)]
      [c' [f]])

    :else
    (let [parent    (conv/instance conv parent-id)
          cdef      (registry/lookup! (:instance/type parent))
          resume-fn (or (get cdef resume-key)
                        (throw (ex-info (str "Parent has no resume fn for " resume-key)
                                        {:parent     parent-id
                                         :resume-key resume-key})))
          [parent' fx]   (lc/coerce-return parent
                                           (resume-fn parent value))
          parent'        (conv/preserve-meta parent parent')
          conv'          (conv/put-instance conv parent')
          [conv'' more]  (e/with-origin parent-id
                           (run-effects conv' fx))
          ;; If the resume produced no further element fragments,
          ;; re-render the parent so its state changes are visible.
          [conv-final extra]
          (if (or (rendered-output? more)
                  (nil? (conv/instance conv'' parent-id)))
            [conv'' []]
            (let [[c' f] (render-frame conv'' parent-id)]
              [c' [f]]))]
      [conv-final (into (vec extra) more)])))

(defn- answer-from-stack [conv value]
  (let [leaving-id      (conv/top-id conv)
        leaving         (conv/top-instance conv)
        resume-key      (:instance/resume leaving)
        stop-iids       (conv/descendant-ids conv leaving-id)
        [conv-stopped stop-frags] (run-stop-hooks conv stop-iids)
        [conv' _popped] (conv/pop-top conv-stopped)
        parent-id       (conv/top-id conv')
        [conv'' frags]  (resume-parent conv' parent-id resume-key value)]
    [conv'' (into (vec stop-frags) frags)]))

(defn- answer-from-slot [conv leaving value]
  (let [leaving-id (:instance/id leaving)
        parent-id  (:instance/parent leaving)
        slot       (:instance/slot leaving)]
    (when-not slot
      (throw (ex-info "Embedded :answer is only supported for [:call-in-slot ...] children"
                      {:instance-id leaving-id
                       :parent      parent-id})))
    (let [resume-key (:instance/resume leaving)
          previous   (:instance/previous leaving)
          stop-iids  (conv/descendant-ids conv leaving-id)
          [conv-stopped stop-frags] (run-stop-hooks conv stop-iids)
          conv'      (-> conv-stopped
                         (conv/remove-subtree leaving-id)
                         (conv/set-child-slot parent-id slot previous))
          [conv'' frags] (resume-parent conv' parent-id resume-key value)]
      [conv'' (into (vec stop-frags) frags)])))

(defmethod step :answer
  [conv eff]
  (let [value     (e/answer-value eff)
        origin-id (effect-origin conv)
        leaving   (conv/instance conv origin-id)]
    (cond
      (nil? leaving)
      [conv []]

      (= origin-id (conv/top-id conv))
      (answer-from-stack conv value)

      :else
      (answer-from-slot conv leaving value))))

(defmethod step :replace
  [conv eff]
  (let [embed-spec            (e/replace-embed eff)
        old-inst              (conv/top-instance conv)
        old-id                (:instance/id old-inst)
        stop-iids             (conv/descendant-ids conv old-id)
        [conv-stopped stop-frags] (run-stop-hooks conv stop-iids)
        [conv-popped _popped-id] (conv/pop-top conv-stopped)
        parent                (when-let [pid (conv/top-id conv-popped)]
                                (conv/instance conv-popped pid))
        ;; Inherit parent linkage and resume key from the frame we are
        ;; replacing, so that `:answer` from the new frame still flows
        ;; back to the original parent.
        cdef                  (registry/lookup! (:embed/type embed-spec))
        [new-inst descendants]
        (apply attach-tree-context conv
               (conv/instantiate-tree cdef embed-spec
                                      (some-> parent :instance/id)
                                      (:instance/resume old-inst)
                                      registry/lookup!))
        conv'    (-> conv-popped
                     (conv/push-instance new-inst)
                     (conv/put-many descendants))
        iid      (:instance/id new-inst)
        [conv'' start-frags] (run-start-hooks conv'
                                              (into [iid] (map :instance/id descendants)))]
    (cond
      (rendered-output? start-frags)
      [conv'' (into (vec stop-frags) start-frags)]

      (nil? (conv/instance conv'' iid))
      [conv'' (into (vec stop-frags) start-frags)]

      :else
      (let [[conv''' frag] (render-frame conv'' iid)]
        [conv''' (conj (into (vec stop-frags) start-frags) frag)]))))

(defmethod step :patch
  [conv eff]
  [conv [(f/elements (render/html (e/patch-hiccup eff)))]])

(defmethod step :patch-signals
  [conv eff]
  [conv [(f/signals (e/patch-signals-map eff))]])

(defmethod step :execute-script
  [conv eff]
  [conv [(f/script (e/script-source eff))]])

(defmethod step :history
  [conv eff]
  [conv [(f/history-script (e/history-mode eff) (e/history-url eff))]])

(defmethod step :io
  [conv eff]
  ;; Fire and forget.  Side-effecting work belongs off the request
  ;; thread so SSE pushes stay snappy.  If you want to feed results back
  ;; into the conversation, the `f` itself can call back into the
  ;; framework via the public dispatch API.
  (let [f (e/io-thunk eff)]
    (future (try (f)
                 (catch Throwable t
                   (println "stube :io effect threw:" (ex-message t))))))
  [conv []])

(defmethod step :after
  [conv eff]
  (when-let [schedule! *schedule-event!*]
    (schedule! {:cid         (:conv/id conv)
                :instance-id (effect-origin conv)
                :delay-ms    (e/after-delay eff)
                :event       (e/after-event eff)}))
  [conv []])

(defmethod step :subscribe
  [conv eff]
  (when-let [subscribe! *subscribe!*]
    (subscribe! {:cid         (:conv/id conv)
                 :instance-id (effect-origin conv)
                 :topic       (e/subscribe-topic eff)
                 :event       (e/subscribe-event eff)}))
  [conv []])

(defmethod step :unsubscribe
  [conv eff]
  (when-let [unsubscribe! *unsubscribe!*]
    (unsubscribe! {:cid         (:conv/id conv)
                   :instance-id (effect-origin conv)
                   :topic       (e/unsubscribe-topic eff)}))
  [conv []])

(defmethod step :back
  [conv _]
  ;; Walk one step backward through `:conv/history`.  Each entry on the
  ;; history vector is a *previous* full conversation value (with its
  ;; own `:conv/history` stripped, by design — see `conv/snapshot`).
  ;;
  ;; To make the restored conversation render cleanly into the existing
  ;; DOM, we mark every instance as not-rendered.  The next call to
  ;; `render-frame` therefore takes the "first render" path and emits
  ;; `selector #root, mode inner` — replacing the entire shell with the
  ;; restored state in one shot.  Without this reset we'd morph
  ;; restored HTML into elements that may not be in the DOM anymore.
  (if-let [prev (peek (:conv/history conv))]
    (let [restored
          (-> prev
              (assoc :conv/history (pop (:conv/history conv)))
              ;; Walk every persisted instance and clear its rendered
              ;; flag.  Simple and correct; if a conversation ever grows
              ;; large enough that this is hot, switch to lazy renderer
              ;; metadata.
              (update :conv/instances
                      (fn [m]
                        (into {}
                              (map (fn [[iid inst]]
                                     [iid (assoc inst :instance/rendered? false)])
                                   m)))))]
      (if-let [iid (conv/top-id restored)]
        (let [[c wake] (run-wakeup-hooks restored (conv/descendant-ids restored iid))
              [c' f]  (render-frame c iid)]
          [c' (conj (vec wake) f)])
        ;; Empty stack: nothing to render.  Surface as :end so the
        ;; conversation tears down cleanly.
        (step restored (e/end nil))))
    ;; No history → nothing to do, no fragments emitted.
    [conv []]))

(defmethod step :end
  [conv _eff]
  (let [stop-iids (vec (mapcat #(conv/descendant-ids conv %) (:conv/stack conv)))
        [conv' stop-frags] (run-stop-hooks conv stop-iids)]
    [(assoc conv' :conv/ended? true) (conj (vec stop-frags) f/close)]))

(defmethod step :default
  [_conv [op & _]]
  (throw (ex-info (str "Unknown effect: " op)
                  {:effect op})))

;; ---------------------------------------------------------------------------
;; Folding many effects
;; ---------------------------------------------------------------------------

(defn run-effects
  "Apply a sequence of effects to `conv` left to right, collecting
  fragments.  Returns `[conv' fragments]`."
  [conv effects]
  (reduce (fn [[c frags] eff]
            (let [[c' fs] (step c eff)]
              [c' (into frags fs)]))
          [conv []]
          effects))

;; ---------------------------------------------------------------------------
;; Dispatch — the kernel's external entry point
;; ---------------------------------------------------------------------------

(defn- event-summary [{:keys [instance-id event payload signals] :as ev}]
  (cond-> {:instance-id instance-id
           :event       event}
    (contains? ev :payload)
    (assoc :payload payload)

    (seq signals)
    (assoc :signal-keys (->> (keys signals) (sort-by str) vec))))

(defn boot
  "Mint the initial set of effects for a freshly minted conversation
  whose root flow is `flow-id`.  Pulled out so the http layer can ask
  for them on first SSE connect.  `flow-id` may also be an embed spec,
  which lets an adapter preserve root init args until the SSE attaches."
  ([flow-id]
   (boot flow-id {}))
  ([flow-id init-args]
   [(e/call (if (conv/embed? flow-id)
              flow-id
              (conv/embed flow-id init-args)))]))

(defn dispatch
  "Apply one client event to a conversation.  `event` is the map

      {:instance-id \"ix-7e2\"
       :event       :submit         ; or any keyword the component knows
       :payload     any-edn-value   ; optional, from (s/on ... :as [:event v])
       :signals     {:answer \"42\"}}

  Returns `[conv' fragments]`.  Pure: no I/O, no globals.

  Stale events — those whose `instance-id` no longer exists in the
  conversation — are dropped silently.  This matters for buttons that
  are still in the DOM after their owning frame has been popped (e.g.
  the user double-clicks an OK button: the first click `:answer`s and
  removes the instance; the second click arrives with an iid that's
  already gone).  Throwing here would surface as a 500 in the http
  layer for what is, semantically, a no-op."
  [conv {:keys [instance-id event payload signals] :as ev}]
  (if (nil? (conv/instance conv instance-id))
    [conv []]
    (try
      (let [;; Compute the merged self from the unmodified `conv` first;
        ;; `merged-self` only consults `:conv/instances`, so it is
        ;; insensitive to whether we have snapshotted yet.  This lets
        ;; us decide whether to snapshot only AFTER seeing what the
        ;; handler emitted.
        self       (conv/merged-self conv instance-id signals)
        cdef       (registry/lookup! (:instance/type self))
        handle     (or (:component/handle cdef) default-handle)
        [self' fx] (lc/coerce-return self
                                     (handle self {:event   event
                                                   :payload payload
                                                   :signals signals}))
        ;; A handler that walks history backwards (`[:back]`) must NOT
        ;; have its own pre-state pushed onto that history first — if
        ;; it did, `:back` would just pop the snapshot we'd just taken
        ;; and "restore" the same state, leaving the user stuck.  So
        ;; we look at the produced effects and skip the snapshot in
        ;; that case.  Every other dispatch behaves as before.
        back?      (some #(e/op? :back %) fx)
        conv*      (cond-> conv
                     (not back?) conv/snapshot
                     true        conv/touch
                     true        (assoc :conv/last-event (event-summary ev)))
        ;; Protect instance metadata against an accidentally clobbering
        ;; handler.
        self'      (conv/preserve-meta (conv/instance conv* instance-id) self')
        conv**     (conv/put-instance conv* self')
        [conv''' more-frags] (e/with-origin instance-id
                               (run-effects conv** fx))
        ;; If the handler produced no frame-changing effect, re-render
        ;; the same instance so the user sees state changes.  Skip the
        ;; re-render if the instance no longer exists (the handler must
        ;; have answered or ended the conversation).
        [conv-final extra]
        (if (or (rendered-output? more-frags)
                (nil? (conv/instance conv''' instance-id)))
          [conv''' []]
          (let [[c' f] (render-frame conv''' instance-id)]
            [c' [f]]))]
        [conv-final (into (vec extra) more-frags)])
      (catch Throwable t
        [conv [(errors/build-fragment conv instance-id t :handle)]]))))

;; ---------------------------------------------------------------------------
;; Embeddable runtime API
;; ---------------------------------------------------------------------------
;;
;; These functions intentionally delegate through `requiring-resolve` so
;; the pure kernel above does not require the runtime namespace at load
;; time.  The mutable atoms live in `dev.zeko.stube.runtime`; this
;; namespace remains the stable place embedders discover the API.

(defn- ^:no-doc runtime-var [sym]
  (or (requiring-resolve sym)
      (throw (ex-info "Unable to resolve stube runtime var" {:var sym}))))

(defn make-kernel
  "Create an embeddable stube runtime instance.  See
  `dev.zeko.stube.runtime/make-kernel` for supported options."
  ([]
   ((runtime-var 'dev.zeko.stube.runtime/make-kernel)))
  ([opts]
   ((runtime-var 'dev.zeko.stube.runtime/make-kernel) opts)))

(defn mint-conversation!
  "Register a conversation in `k` and return its cid."
  ([k root-id request]
   ((runtime-var 'dev.zeko.stube.runtime/mint-conversation!) k root-id request))
  ([k root-id init-args request]
   ((runtime-var 'dev.zeko.stube.runtime/mint-conversation!) k root-id init-args request)))

(defn shell-for
  "Return an embeddable Hiccup shell fragment for conversation `cid`."
  [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/shell-for) k cid))

(defn dispatch!
  "Dispatch an event into live conversation `cid` in runtime `k` and
  return the produced fragments."
  [k cid event]
  ((runtime-var 'dev.zeko.stube.runtime/dispatch!) k cid event))

(defn replay
  "Purely replay `events` against `root-id` using runtime `k`'s render
  configuration.  Runtime state is not mutated."
  [k root-id events]
  ((runtime-var 'dev.zeko.stube.runtime/replay) k root-id events))

(defn halt!
  "Close open SSE streams and clear runtime registries for `k`."
  [k]
  ((runtime-var 'dev.zeko.stube.runtime/halt!) k))

(defn ^:no-doc create-conversation! [k root-id owner-token]
  ((runtime-var 'dev.zeko.stube.runtime/create-conversation!) k root-id owner-token))

(defn ^:no-doc ensure-session [k request]
  ((runtime-var 'dev.zeko.stube.runtime/ensure-session) k request))

(defn ^:no-doc pending-root [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/pending-root) k cid))

(defn ^:no-doc conversation [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/conversation) k cid))

(defn ^:no-doc active-conversations [k]
  ((runtime-var 'dev.zeko.stube.runtime/active-conversations) k))

(defn ^:no-doc swap-conv! [k cid f]
  ((runtime-var 'dev.zeko.stube.runtime/swap-conv!) k cid f))

(defn ^:no-doc end-conversation! [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/end-conversation!) k cid))

(defn ^:no-doc end! [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/end!) k cid))

(defn ^:no-doc reap! [k ttl]
  ((runtime-var 'dev.zeko.stube.runtime/reap!) k ttl))

(defn shutting-down?
  "True after [[halt!]] has begun draining `k`.  HTTP adapters should
  refuse new conversation mints (typically 503) while this is true."
  [k]
  ((runtime-var 'dev.zeko.stube.runtime/shutting-down?) k))

(defn ^:no-doc register-sse! [k cid sse-gen]
  ((runtime-var 'dev.zeko.stube.runtime/register-sse!) k cid sse-gen))

(defn ^:no-doc unregister-sse! [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/unregister-sse!) k cid))

(defn ^:no-doc sse [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/sse) k cid))

(defn ^:no-doc run-effects! [k cid effects]
  ((runtime-var 'dev.zeko.stube.runtime/run-effects!) k cid effects))

(defn ^:no-doc apply-conv! [k cid f]
  ((runtime-var 'dev.zeko.stube.runtime/apply-conv!) k cid f))

(defn ^:no-doc schedule-event! [k event]
  ((runtime-var 'dev.zeko.stube.runtime/schedule-event!) k event))

(defn ^:no-doc subscribe! [k sub]
  ((runtime-var 'dev.zeko.stube.runtime/subscribe!) k sub))

(defn ^:no-doc unsubscribe! [k sub]
  ((runtime-var 'dev.zeko.stube.runtime/unsubscribe!) k sub))

(defn ^:no-doc subscriptions [k]
  ((runtime-var 'dev.zeko.stube.runtime/subscriptions) k))

(defn ^:no-doc publish! [k topic msg]
  ((runtime-var 'dev.zeko.stube.runtime/publish!) k topic msg))

(defn ^:no-doc authorized? [k request cid]
  ((runtime-var 'dev.zeko.stube.runtime/authorized?) k request cid))

(defn ^:no-doc current-store [k]
  ((runtime-var 'dev.zeko.stube.runtime/current-store) k))

(defn ^:no-doc with-kernel-bindings [k cid f]
  ((runtime-var 'dev.zeko.stube.runtime/with-kernel-bindings) k cid f))

(defn ^:no-doc enable-halos! [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/enable-halos!) k cid))

(defn ^:no-doc enable-halos-and-redraw! [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/enable-halos-and-redraw!) k cid))

(defn ^:no-doc ui-css? [k]
  ((runtime-var 'dev.zeko.stube.runtime/ui-css?) k))

(defn ^:no-doc halos? [k]
  ((runtime-var 'dev.zeko.stube.runtime/halos?) k))

(defn ^:no-doc base-path [k]
  ((runtime-var 'dev.zeko.stube.runtime/base-path) k))

(defn ^:no-doc route-style [k]
  ((runtime-var 'dev.zeko.stube.runtime/route-style) k))

(defn ^:no-doc root-selector [k]
  ((runtime-var 'dev.zeko.stube.runtime/root-selector) k))

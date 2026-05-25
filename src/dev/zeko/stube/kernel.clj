(ns dev.zeko.stube.kernel
  "The pure runtime: `step`, `run-effects`, `dispatch`.

  Hosts embedding stube do not call into this namespace directly; the
  documented embedder surface lives in [[dev.zeko.stube.embed]].  This
  file is the value-language only: every function takes a conversation
  value (plus the event for `dispatch`) and returns the next conversation
  value plus the fragments to emit.

  Reading guide
  ─────────────

  Everything in this namespace operates on plain values.  A handler
  returns `[self' effects]`; `run-effects` folds those effects into the
  conversation, producing the next conversation value and the list of
  *fragments* that must be pushed to the browser.  A fragment is just a
  small map describing one Datastar event — see [[fragment-shape]] for
  the schema.

  No I/O, no atoms, no SSE — that all lives one layer up in
  [[dev.zeko.stube.runtime]] / [[dev.zeko.stube.http]] / [[dev.zeko.stube.server]].
  This means the entire interaction loop is testable from a REPL with
  `dispatch` and a hand-built conversation value, with no server running.

  Effect vocabulary
  ─────────────────

      [:call <embed> :resume <k>]   push a child frame; on `:answer` the
                                    parent's `:k` function is invoked
      [:call-in-slot <slot> <embed> :resume <k>]
                                    temporarily swap one embedded slot;
                                    the child answers back to the parent
                                    without taking over the page
      [:set-keyed-children <slot> <[[key embed]…]>]
                                    reconcile an ordered, key-addressed
                                    set of children; emits per-child
                                    append/remove/outer fragments
                                    instead of re-rendering the parent
      [:answer <value>]             pop this frame; deliver `value` to
                                    the parent under its resume key
      [:replace <embed>]            pop this frame and push another in
                                    its place (Seaside `become:`)
      [:patch <hiccup>]             extra DOM patch (no stack change)
      [:patch-signals <map>]        push a Datastar signal patch
      [:execute-script <js>]        run literal JS in the browser
      [:history :replace|:push url]  sync browser URL (replaceState/pushState)
      [:io <fn>]                    ask the bound runtime to run `(fn)`
                                    off-thread; pure folds leave it inert
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
            [dev.zeko.stube.dev          :as dev]
            [dev.zeko.stube.effects      :as e]
            [dev.zeko.stube.errors       :as errors]
            [dev.zeko.stube.fragments    :as f]
            [dev.zeko.stube.frame        :as frame]
            [dev.zeko.stube.keyed        :as keyed]
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

(def ^:dynamic *run-io!*
  "Optional side-effect hook bound by the runtime for `[:io f]`.  Keeping
  this as a hook preserves pure `run-effects`/`replay`: without a runtime
  binding, `:io` is data that produces no fragments and does not execute."
  nil)

(def ^:dynamic *current-kernel*
  "Runtime kernel currently folding an effect.  Standalone helpers use
  this to route regular functions such as `s/publish!` to the embedded
  kernel when called from component code, while preserving the historical
  default-kernel behaviour outside a runtime dispatch."
  nil)

(def ^:dynamic *current-app*
  "Opaque host value attached to the kernel via the `:app` option.  Bound
  by the runtime during dispatch and render so component code can read
  it through `dev.zeko.stube.core/app` without threading a kernel
  reference."
  nil)

(def ^:dynamic *current-principal*
  "Authenticated principal stamped onto the conversation at mint time
  via `:principal-fn`.  Bound by the runtime around dispatch and render
  so component code can read it through `dev.zeko.stube.core/principal`."
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
        iid       (:instance/id inst)
        [conv'' fr] (run-start-hooks conv'
                                     (into [iid] (map :instance/id descendants)))]
    ;; `:start` runs for the whole instantiated subtree.  Stack/task
    ;; components still get the old behaviour, while embedded children can
    ;; subscribe or schedule timers as soon as their parent is booted.
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
        [c (conj (vec fr) f)]))))

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
                      (conv/put-many descendants))
        [conv'' fr] (run-start-hooks conv'
                                     (into [iid] (map :instance/id descendants)))]
    (cond
      (rendered-output? fr)
      [conv'' fr]

      (nil? (conv/instance conv'' iid))
      [conv'' fr]

      :else
      (let [[c f] (frame/render-slot-overlay conv'' parent-id old-iid iid)]
        [c (conj (vec fr) f)]))))

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
          _              (dev/validate! cdef parent' resume-key)
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
        ;; The whole frame is being destroyed — pull in previous-chained
        ;; slot occupants too so their `:stop` fires and they get swept
        ;; from `:conv/instances` by pop-top below.
        stop-iids       (conv/subtree-ids conv leaving-id)
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
        ;; The whole old frame goes; sweep previous-chain instances too.
        stop-iids             (conv/subtree-ids conv old-id)
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

(defmethod step :set-keyed-children
  [conv eff]
  (let [slot     (e/keyed-children-slot eff)
        pairs    (e/keyed-children-pairs eff)
        parent-id (or (effect-origin conv)
                      (throw (ex-info ":set-keyed-children needs an emitting parent"
                                      {:slot slot})))]
    (keyed/reconcile! conv parent-id slot pairs run-effects)))

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
  ;; Fire and forget when a runtime is interpreting effects.  Pure
  ;; `run-effects`/`replay` leave the thunk untouched so tests never
  ;; execute arbitrary side effects by accident.
  (when-let [run! *run-io!*]
    (run! (e/io-thunk eff)))
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
  ;; The whole conversation is going away — sweep previous-chain
  ;; instances too so their `:stop` hooks fire.
  (let [stop-iids (vec (mapcat #(conv/subtree-ids conv %) (:conv/stack conv)))
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

;; ---------------------------------------------------------------------------
;; Declarative `:url` syncing (S-11)
;; ---------------------------------------------------------------------------
;;
;; A component may declare a `:url` fn alongside `:render` / `:handle`.
;; Only the **root** frame's `:url` fn is consulted; nested instances'
;; `:url` declarations are ignored because the address bar is a singleton.
;;
;; The fn is called with the same `self` shape that `:render` sees and
;; must return one of:
;;
;;     nil                     — leave the URL alone
;;     "/path?q=…"             — equivalent to `[:replace "/path?q=…"]`
;;     [:replace "/path?q=…"]  — calls history.replaceState
;;     [:push    "/path?q=…"]  — calls history.pushState
;;
;; After every `dispatch`, the kernel:
;;   1. Computes the new URL from the root's `:url` fn.
;;   2. If it differs from `:conv/last-url` AND the handler did not
;;      itself emit a `[:history …]` effect, emits one automatically.
;;   3. Stores the result back into `:conv/last-url` so the next
;;      dispatch has a baseline.
;;
;; First-dispatch seeding without an emit happens via
;; [[ensure-url-seeded]]: when no `:conv/last-url` exists yet, the
;; pre-dispatch value is recorded silently — the browser already shows
;; the URL it loaded via GET.

(defn- coerce-url-result [result]
  (cond
    (nil? result)
    nil

    (string? result)
    [:replace result]

    (and (vector? result)
         (= 2 (count result))
         (#{:replace :push} (first result))
         (string? (second result)))
    (vec result)

    :else
    (throw (ex-info ":url fn must return nil, a string, or [op url]"
                    {:got result}))))

(defn- root-iid [conv]
  (first (:conv/stack conv)))

(defn- root-url-spec [conv]
  (when-let [rid (root-iid conv)]
    (let [inst (conv/instance conv rid)
          cdef (registry/lookup (:instance/type inst))]
      (when-let [url-fn (:component/url cdef)]
        (coerce-url-result (url-fn inst))))))

(defn- ensure-url-seeded
  "If the conversation's root has a `:url` fn and `:conv/last-url` is
  absent, store the pre-dispatch URL silently.  Mirrors the boot path —
  the browser already shows that URL, so an emit would be redundant."
  [conv]
  (if (contains? conv :conv/last-url)
    conv
    (if-let [spec (root-url-spec conv)]
      (assoc conv :conv/last-url (second spec))
      conv)))

(defn- history-in-fx? [fx]
  (boolean (some #(e/op? :history %) fx)))

(defn- maybe-emit-url
  "After dispatch effects fold, compare the post-dispatch root URL
  against `:conv/last-url`.  Returns `[conv' extra-frags]` — an empty
  vector if nothing changed or the handler already emitted its own
  `[:history …]`."
  [conv fx]
  (let [spec (root-url-spec conv)]
    (if (nil? spec)
      [conv []]
      (let [[mode url] spec
            last-url   (:conv/last-url conv)]
        (cond
          (= url last-url)
          [conv []]

          (history-in-fx? fx)
          [(assoc conv :conv/last-url url) []]

          :else
          (run-effects (assoc conv :conv/last-url url)
                       [(e/history mode url)]))))))

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
      (let [;; Seed `:conv/last-url` on first dispatch from the root's
        ;; `:url` fn (if declared) so subsequent comparisons have a
        ;; baseline.  No fragments emitted — the browser already shows
        ;; the URL it loaded via GET.
        conv       (ensure-url-seeded conv)
        ;; Compute the merged self from the unmodified `conv` first;
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
        _          (dev/validate! cdef self' :handle)
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
            [c' [f]]))
        ;; S-11: after the dispatch settles, compare the root's `:url`
        ;; fn output against `:conv/last-url` and auto-emit a `:history`
        ;; effect if the URL changed.  Suppressed when the handler
        ;; already emitted its own `[:history …]`.
        [conv-with-url url-frags] (maybe-emit-url conv-final fx)]
        [conv-with-url (-> (vec extra) (into more-frags) (into url-frags))])
      (catch Throwable t
        ;; Dev-mode `:component/state` schema failures are programmer
        ;; errors meant to surface loudly with a stack trace, not be
        ;; pacified into a banner — let them rip through.
        (if (:stube.dev/component (ex-data t))
          (throw t)
          [conv [(errors/build-fragment conv instance-id t :handle)]])))))


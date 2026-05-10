(ns stube.kernel
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
  [[stube.http]] / [[stube.server]].  This means the entire interaction
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
      [:io <fn>]                    call `(fn)` off-thread (fire-and-forget)
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

  …which the kernel runs once immediately after instantiation.  This
  lets \"task\" components (a wizard that exists only to sequence
  others) launch their first `:call` without needing a synthetic user
  event.  `:stop` runs just before a frame/subtree is removed; `:wakeup`
  runs when a persisted or history-restored frame becomes live again."
  (:require [stube.conversation :as conv]
            [stube.registry     :as registry]
            [stube.render       :as render]))

;; ---------------------------------------------------------------------------
;; Fragment helpers
;; ---------------------------------------------------------------------------

(defn fragment-shape
  "Documentation-only.  Every fragment has a `:fragment/kind` plus
  payload keys appropriate to that kind:

      ;; HTML to morph into the DOM
      {:fragment/kind :elements
       :fragment/html \"<form id=ix-001>…</form>\"
       :fragment/opts {…passed through to patch-elements!…}}

      ;; JSON-mergeable signals
      {:fragment/kind :signals
       :fragment/data {:foo 1}
       :fragment/opts {}}

      ;; Browser-side JS
      {:fragment/kind :script
       :fragment/script \"alert('hi')\"
       :fragment/opts {}}

      ;; Close the SSE connection (after [:end …])
      {:fragment/kind :close}"
  [])

(defn- elements-fragment
  ([html]      (elements-fragment html {}))
  ([html opts] {:fragment/kind :elements
                :fragment/html html
                :fragment/opts opts}))

(defn- signals-fragment [m]
  {:fragment/kind :signals
   :fragment/data m
   :fragment/opts {}})

(defn- script-fragment [js]
  {:fragment/kind :script
   :fragment/script js
   :fragment/opts {}})

(def ^:private close-fragment
  {:fragment/kind :close})

;; ---------------------------------------------------------------------------
;; Rendering a frame
;; ---------------------------------------------------------------------------

(defn- default-render
  "Default renderer for components that don't supply one (e.g. tasks).
  An empty hidden div with the instance id keeps the morph-by-id wire
  contract intact."
  [self]
  [:div {:id (:instance/id self) :hidden true}])

(defn- default-handle
  "Default handler: ignore the event, no effects."
  [self _event]
  [self []])

(def ^:dynamic ^:private *effect-iid*
  "Instance id whose handler/lifecycle hook emitted the effects currently
  being folded.  Stack calls historically used `(conv/top-id conv)`, but
  embedded children can also handle events; slot-local effects need the
  actual emitting instance."
  nil)

(defn- effect-origin [conv]
  (or *effect-iid* (conv/top-id conv)))

(defn- render-instance
  "Render `iid` with explicit Datastar patch `opts`, mark it and its
  rendered descendants as present in the DOM, and return `[conv' frag]`."
  [conv iid opts]
  (let [inst      (conv/instance conv iid)
        cdef      (registry/lookup! (:instance/type inst))
        render-fn (or (:component/render cdef) default-render)
        hiccup    (binding [render/*conv* conv]
                    (render-fn inst))]
    [(conv/mark-rendered conv iid)
     (elements-fragment (render/html hiccup) opts)]))

(defn- render-frame
  "Produce the elements fragment for `iid` and return
  `[conv' fragment]`.

  Patching strategy (slice 2):

  * **First render of a frame** — its id is not yet in the DOM, so we
    target the shell's `<div id=\"root\">` with `mode inner`.  This
    case covers the very first SSE message of a conversation, every
    `:call`-pushed top frame, and the result of `:replace`.
  * **Subsequent renders** — the id is in the DOM (either we put it
    there last time as a top frame, or its parent inlined it via
    `s/render-slot`), so we let Datastar's default morph-by-id do the
    work.  Sibling DOM state — input focus, selection, scroll
    position — is preserved.

  `*conv*` is bound around the user's `:render` so `s/render-slot` can
  resolve embedded children."
  [conv iid]
  (let [inst        (conv/instance conv iid)
        first-time? (not (:instance/rendered? inst))
        opts        (if first-time?
                      {:selector "#root" :patch-mode :inner}
                      ;; No selector → Datastar morphs by element id.
                      {})]
    (render-instance conv iid opts)))

(declare run-effects)

;; ---------------------------------------------------------------------------
;; Lifecycle hooks
;; ---------------------------------------------------------------------------

(defn- lifecycle-pair
  "Normalise a lifecycle hook result.  Hooks mirror `:start` and should
  return `[self' effects]`, but accepting nil keeps cleanup-only hooks
  terse."
  [self result]
  (cond
    (nil? result) [self []]
    (and (vector? result) (= 2 (count result)) (map? (first result))) result
    :else [self result]))

(defn- run-stop-hooks
  "Run `:stop` for the instance ids, preserving any emitted fragments.
  The instances are still present while hooks run; callers remove them
  afterwards."
  [conv iids]
  (reduce (fn [[c frags] iid]
            (if-let [inst (conv/instance c iid)]
              (let [cdef (registry/lookup! (:instance/type inst))]
                (if-let [stop-fn (:stop cdef)]
                  (let [[_ fx] (lifecycle-pair inst (stop-fn inst))
                        [c' more] (binding [*effect-iid* iid]
                                    (run-effects c fx))]
                    [c' (into frags more)])
                  [c frags]))
              [c frags]))
          [conv []]
          iids))

(defn- wakeup-frame
  "Run `:wakeup` for `iid`, updating the instance before rendering."
  [conv iid]
  (if-let [inst (conv/instance conv iid)]
    (let [cdef (registry/lookup! (:instance/type inst))]
      (if-let [wakeup-fn (:wakeup cdef)]
        (let [[inst' fx] (lifecycle-pair inst (wakeup-fn inst))
              inst'      (conv/preserve-meta inst inst')
              conv'      (conv/put-instance conv inst')
              [conv'' frags] (binding [*effect-iid* iid]
                                (run-effects conv' fx))]
          [conv'' frags])
        [conv []]))
    [conv []]))

(defn resume-top
  "Run `:wakeup` for the current top frame and render it as a restored
  frame.  Used by the http layer when a persisted conversation reattaches."
  [conv]
  (if-let [iid (conv/top-id conv)]
    (let [conv'          (assoc-in conv [:conv/instances iid :instance/rendered?] false)
          [conv'' wake]  (wakeup-frame conv' iid)
          [conv''' frag] (render-frame conv'' iid)]
      [conv''' (conj (vec wake) frag)])
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
  [conv [_ embed-spec & {:keys [resume]}]]
  (let [parent-id (conv/top-id conv)
        cdef      (registry/lookup! (:embed/type embed-spec))
        ;; `instantiate-tree` materialises the called component *and*
        ;; every child its `:children` map declares.  Children live in
        ;; `:conv/instances` alongside their parent but stay off the
        ;; stack — they are addressed through `:instance/children`,
        ;; not through call/answer.
        [inst descendants]
        (conv/instantiate-tree cdef embed-spec parent-id resume registry/lookup!)
        conv'     (-> conv
                      (conv/push-instance inst)
                      (conv/put-many descendants))
        iid       (:instance/id inst)]
    (if-let [start-fn (:start cdef)]
      ;; "Task" components carry a `:start` hook that runs once on
      ;; instantiation.  This is what makes hand-rolled flows (slice 0)
      ;; readable: the wizard can immediately delegate without waiting
      ;; for a synthetic user event.
      (let [[inst' fx]   (start-fn inst)
            inst'        (conv/preserve-meta inst inst')
            conv''       (conv/put-instance conv' inst')
            [conv''' fr] (binding [*effect-iid* iid]
                            (run-effects conv'' fx))]
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
          (some #(= :elements (:fragment/kind %)) fr)
          [conv''' fr]

          (nil? (conv/instance conv''' iid))
          [conv''' fr]

          :else
          (let [[c f] (render-frame conv''' iid)]
            [c (conj (vec fr) f)])))
      (let [[conv'' frag] (render-frame conv' iid)]
        [conv'' [frag]]))))

(defn- render-slot-overlay
  "Render the child currently occupying `slot` after a `:call-in-slot`.
  If the slot already had a DOM root, patch that root `outer`; otherwise
  fall back to re-rendering the parent because there is no child anchor
  yet."
  [conv parent-id old-iid new-iid]
  (if old-iid
    (render-instance conv new-iid {:selector (str "#" old-iid)
                                   :patch-mode :outer})
    (render-frame conv parent-id)))

(defmethod step :call-in-slot
  [conv [_ slot embed-spec & {:keys [resume]}]]
  (let [parent-id (or (effect-origin conv)
                      (throw (ex-info ":call-in-slot needs an emitting parent"
                                      {:slot slot})))
        parent    (or (conv/instance conv parent-id)
                      (throw (ex-info ":call-in-slot parent is missing"
                                      {:parent parent-id
                                       :slot   slot})))
        old-iid   (get-in parent [:instance/children slot])
        cdef      (registry/lookup! (:embed/type embed-spec))
        [inst descendants]
        (conv/instantiate-tree cdef embed-spec parent-id resume registry/lookup!)
        inst      (assoc inst
                         :instance/slot slot
                         :instance/previous old-iid)
        iid       (:instance/id inst)
        conv'     (-> conv
                      (conv/set-child-slot parent-id slot iid)
                      (conv/put-instance inst)
                      (conv/put-many descendants))]
    (if-let [start-fn (:start cdef)]
      (let [[inst' fx]   (start-fn inst)
            inst'        (conv/preserve-meta inst inst')
            conv''       (conv/put-instance conv' inst')
            [conv''' fr] (binding [*effect-iid* iid]
                            (run-effects conv'' fx))]
        (cond
          (some #(= :elements (:fragment/kind %)) fr)
          [conv''' fr]

          (nil? (conv/instance conv''' iid))
          [conv''' fr]

          :else
          (let [[c f] (render-slot-overlay conv''' parent-id old-iid iid)]
            [c (conj (vec fr) f)])))
      (let [[conv'' frag] (render-slot-overlay conv' parent-id old-iid iid)]
        [conv'' [frag]]))))

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
    (step conv [:end value])

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
          [parent' fx]   (resume-fn parent value)
          parent'        (conv/preserve-meta parent parent')
          conv'          (conv/put-instance conv parent')
          [conv'' more]  (binding [*effect-iid* parent-id]
                            (run-effects conv' fx))
          ;; If the resume produced no further element fragments,
          ;; re-render the parent so its state changes are visible.
          [conv-final extra]
          (if (or (some #(= :elements (:fragment/kind %)) more)
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
  [conv [_ value]]
  (let [origin-id (effect-origin conv)
        leaving   (conv/instance conv origin-id)]
    (cond
      (nil? leaving)
      [conv []]

      (= origin-id (conv/top-id conv))
      (answer-from-stack conv value)

      :else
      (answer-from-slot conv leaving value))))

(defmethod step :replace
  [conv [_ embed-spec]]
  (let [old-inst              (conv/top-instance conv)
        old-id                (:instance/id old-inst)
        stop-iids             (conv/descendant-ids conv old-id)
        [conv-stopped stop-frags] (run-stop-hooks conv stop-iids)
        [conv-popped popped-id] (conv/pop-top conv-stopped)
        parent                (when-let [pid (conv/top-id conv-popped)]
                                (conv/instance conv-popped pid))
        ;; Inherit parent linkage and resume key from the frame we are
        ;; replacing, so that `:answer` from the new frame still flows
        ;; back to the original parent.
        cdef                  (registry/lookup! (:embed/type embed-spec))
        [new-inst descendants]
        (conv/instantiate-tree cdef embed-spec
                               (some-> parent :instance/id)
                               (:instance/resume old-inst)
                               registry/lookup!)
        conv'    (-> conv-popped
                     (conv/push-instance new-inst)
                     (conv/put-many descendants))
        [conv'' frag] (render-frame conv' (:instance/id new-inst))]
    [conv'' (conj (vec stop-frags) frag)]))

(defmethod step :patch
  [conv [_ hiccup]]
  [conv [(elements-fragment (render/html hiccup))]])

(defmethod step :patch-signals
  [conv [_ m]]
  [conv [(signals-fragment m)]])

(defmethod step :execute-script
  [conv [_ js]]
  [conv [(script-fragment js)]])

(defmethod step :io
  [conv [_ f]]
  ;; Fire and forget.  Side-effecting work belongs off the request
  ;; thread so SSE pushes stay snappy.  If you want to feed results back
  ;; into the conversation, the `f` itself can call back into the
  ;; framework via the public dispatch API.
  (future (try (f)
               (catch Throwable t
                 (println "stube :io effect threw:" (ex-message t)))))
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
        (let [[c wake] (wakeup-frame restored iid)
              [c' f]  (render-frame c iid)]
          [c' (conj (vec wake) f)])
        ;; Empty stack: nothing to render.  Surface as :end so the
        ;; conversation tears down cleanly.
        (step restored [:end nil])))
    ;; No history → nothing to do, no fragments emitted.
    [conv []]))

(defmethod step :end
  [conv [_ _value]]
  (let [stop-iids (vec (mapcat #(conv/descendant-ids conv %) (:conv/stack conv)))
        [conv' stop-frags] (run-stop-hooks conv stop-iids)]
    [(assoc conv' :conv/ended? true) (conj (vec stop-frags) close-fragment)]))

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

(defn boot
  "Mint the initial set of effects for a freshly minted conversation
  whose root flow is `flow-id`.  Pulled out so the http layer can ask
  for them on first SSE connect."
  [flow-id]
  [[:call (conv/embed flow-id) :resume nil]])

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
  [conv {:keys [instance-id event payload signals]}]
  (if (nil? (conv/instance conv instance-id))
    [conv []]
    (let [;; Compute the merged self from the unmodified `conv` first;
        ;; `merged-self` only consults `:conv/instances`, so it is
        ;; insensitive to whether we have snapshotted yet.  This lets
        ;; us decide whether to snapshot only AFTER seeing what the
        ;; handler emitted.
        self       (conv/merged-self conv instance-id signals)
        cdef       (registry/lookup! (:instance/type self))
        handle     (or (:component/handle cdef) default-handle)
        [self' fx] (handle self {:event   event
                                 :payload payload
                                 :signals signals})
        ;; A handler that walks history backwards (`[:back]`) must NOT
        ;; have its own pre-state pushed onto that history first — if
        ;; it did, `:back` would just pop the snapshot we'd just taken
        ;; and "restore" the same state, leaving the user stuck.  So
        ;; we look at the produced effects and skip the snapshot in
        ;; that case.  Every other dispatch behaves as before.
        back?      (some #(= :back (first %)) fx)
        conv*      (cond-> conv
                     (not back?) conv/snapshot
                     true        conv/touch)
        ;; Protect instance metadata against an accidentally clobbering
        ;; handler.
        self'      (conv/preserve-meta (conv/instance conv* instance-id) self')
        conv**     (conv/put-instance conv* self')
        [conv''' more-frags] (binding [*effect-iid* instance-id]
                                (run-effects conv** fx))
        ;; If the handler produced no frame-changing effect, re-render
        ;; the same instance so the user sees state changes.  Skip the
        ;; re-render if the instance no longer exists (the handler must
        ;; have answered or ended the conversation).
        [conv-final extra]
        (if (or (some #(= :elements (:fragment/kind %)) more-frags)
                (nil? (conv/instance conv''' instance-id)))
          [conv''' []]
          (let [[c' f] (render-frame conv''' instance-id)]
            [c' [f]]))]
    [conv-final (into (vec extra) more-frags)])))

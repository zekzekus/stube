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
      [:answer <value>]             pop this frame; deliver `value` to
                                    the parent under its resume key
      [:replace <embed>]            pop this frame and push another in
                                    its place (Seaside `become:`)
      [:patch <hiccup>]             extra DOM patch (no stack change)
      [:patch-signals <map>]        push a Datastar signal patch
      [:execute-script <js>]        run literal JS in the browser
      [:io <fn>]                    call `(fn)` off-thread (fire-and-forget)
      [:end <value>]                terminate the conversation

  All effects produce zero or more fragments and an updated conversation.

  Component lifecycle keys
  ────────────────────────
  Beyond the keys read directly by `:render` / `:handle`, a component
  may include:

      :start  (fn [self] [self' effects])

  …which the kernel runs once immediately after instantiation.  This
  lets \"task\" components (a wizard that exists only to sequence
  others) launch their first `:call` without needing a synthetic user
  event."
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

(defn- render-frame
  "Produce the elements fragment for `iid` and return
  `[conv' fragment]`.

  Slice 0 renders only the top frame, and every emitted patch targets
  the shell's `<div id=\"root\">` with `mode inner`.  This sidesteps
  Datastar's morph-by-id for the root: when a `:call` pushes a new
  instance with a fresh id, there is by definition no element with that
  id in the DOM yet, so a default morph would have nowhere to land.
  Replacing `#root`'s contents wholesale always works.

  Once embedding lands in slice 2, parents will render hiccup that
  includes their children's ids inline; from that point morph-by-id can
  preserve sibling state.  For now, simplicity wins."
  [conv iid]
  (let [inst (conv/instance conv iid)
        cdef (registry/lookup! (:instance/type inst))
        ;; The component sees the same shape it would inside `dispatch`:
        ;; instance metadata + state, no signals merged because there
        ;; aren't any in the kernel's render path.
        render-fn (or (:component/render cdef) default-render)
        hiccup    (render-fn inst)
        opts      {:selector "#root" :patch-mode :inner}]
    [(conv/mark-rendered conv iid)
     (elements-fragment (render/html hiccup) opts)]))

;; ---------------------------------------------------------------------------
;; The step function — one effect at a time
;; ---------------------------------------------------------------------------

(declare run-effects)

(defmulti ^:private step
  "Apply a single effect to the conversation and return
  `[conv' fragments]`.  Implemented as a multimethod keyed on the effect
  tag so user code can extend the vocabulary later (slice 4)."
  (fn [_conv [op & _]] op))

(defmethod step :call
  [conv [_ embed-spec & {:keys [resume]}]]
  (let [parent-id (conv/top-id conv)
        cdef      (registry/lookup! (:embed/type embed-spec))
        inst      (conv/instantiate cdef embed-spec parent-id resume)
        conv'     (conv/push-instance conv inst)
        iid       (:instance/id inst)]
    (if-let [start-fn (:start cdef)]
      ;; "Task" components carry a `:start` hook that runs once on
      ;; instantiation.  This is what makes hand-rolled flows (slice 0)
      ;; readable: the wizard can immediately delegate without waiting
      ;; for a synthetic user event.
      (let [[inst' fx]   (start-fn inst)
            inst'        (conv/preserve-meta inst inst')
            conv''       (conv/put-instance conv' inst')
            [conv''' fr] (run-effects conv'' fx)]
        ;; If `:start`'s effects already produced HTML (e.g. by `:call`-ing
        ;; a child) we don't render this placeholder over the top.
        (if (some #(= :elements (:fragment/kind %)) fr)
          [conv''' fr]
          (let [[c f] (render-frame conv''' iid)]
            [c (conj (vec fr) f)])))
      (let [[conv'' frag] (render-frame conv' iid)]
        [conv'' [frag]]))))

(defmethod step :answer
  [conv [_ value]]
  (let [;; The resume key lives on the *child* (the frame being popped):
        ;; the call site that pushed it remembered which slot in the
        ;; parent's component definition should receive the answer.
        leaving         (conv/top-instance conv)
        resume-key      (:instance/resume leaving)
        [conv' _popped] (conv/pop-top conv)
        parent-id       (conv/top-id conv')]
    (cond
      ;; Answering the root means the conversation is done.  We translate
      ;; this into an `:end` so behaviour is consistent.
      (nil? parent-id)
      (step conv' [:end value])

      ;; Bare child answer with no resume key — nothing to deliver to the
      ;; parent.  Just re-render the parent so the user sees something.
      (nil? resume-key)
      (let [[c' f] (render-frame conv' parent-id)]
        [c' [f]])

      :else
      (let [parent    (conv/instance conv' parent-id)
            cdef      (registry/lookup! (:instance/type parent))
            resume-fn (or (get cdef resume-key)
                          (throw (ex-info (str "Parent has no resume fn for " resume-key)
                                          {:parent     parent-id
                                           :resume-key resume-key})))
            [parent' fx]   (resume-fn parent value)
            parent'        (conv/preserve-meta parent parent')
            conv''         (conv/put-instance conv' parent')
            [conv''' more] (run-effects conv'' fx)
            ;; If the resume produced no further element fragments,
            ;; re-render the parent so its state changes are visible.
            [conv-final extra]
            (if (or (some #(= :elements (:fragment/kind %)) more)
                    (nil? (conv/instance conv''' parent-id)))
              [conv''' []]
              (let [[c' f] (render-frame conv''' parent-id)]
                [c' [f]]))]
        [conv-final (into (vec extra) more)]))))

(defmethod step :replace
  [conv [_ embed-spec]]
  (let [[conv-popped popped-id] (conv/pop-top conv)
        parent (when-let [pid (conv/top-id conv-popped)]
                 (conv/instance conv-popped pid))
        ;; Inherit parent linkage and resume key from the frame we are
        ;; replacing, so that `:answer` from the new frame still flows
        ;; back to the original parent.
        old-inst (conv/instance conv popped-id)
        cdef     (registry/lookup! (:embed/type embed-spec))
        new-inst (conv/instantiate cdef embed-spec
                                   (some-> parent :instance/id)
                                   (:instance/resume old-inst))
        conv'    (conv/push-instance conv-popped new-inst)
        [conv'' frag] (render-frame conv' (:instance/id new-inst))]
    [conv'' [frag]]))

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

(defmethod step :end
  [conv [_ _value]]
  [(assoc conv :conv/ended? true) [close-fragment]])

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
       :signals     {:answer \"42\"}}

  Returns `[conv' fragments]`.  Pure: no I/O, no globals."
  [conv {:keys [instance-id event signals]}]
  (let [;; Snapshot the *previous* conversation onto the history stack
        ;; before we mutate it, so the back button can rewind to here.
        conv*  (-> conv conv/snapshot conv/touch)
        self   (conv/merged-self conv* instance-id signals)
        cdef   (registry/lookup! (:instance/type self))
        handle (or (:component/handle cdef) default-handle)
        [self' fx] (handle self {:event event :signals signals})
        ;; Protect instance metadata against an accidentally clobbering
        ;; handler.
        self'  (conv/preserve-meta (conv/instance conv* instance-id) self')
        conv** (conv/put-instance conv* self')
        [conv''' more-frags] (run-effects conv** fx)
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
    [conv-final (into (vec extra) more-frags)]))

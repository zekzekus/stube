(ns dev.zeko.stube.conversation
  "The conversation data model — pure helpers, no I/O.

  A *conversation* is the entire server-side state of a single user's
  session against one mounted flow.  It is a plain map; persistence,
  history, and concurrency are all handled by working with these values.

  ──────────────────────────────────────────────────────────────────────
  Shape
  ──────────────────────────────────────────────────────────────────────

      {:conv/id        \"cv-019\"
       :conv/instances {\"ix-7e2\" {…instance map…} …}
       :conv/stack     [\"ix-7c1\" \"ix-7e2\"]   ; bottom → top
       :conv/history   [previous-conv …]
       :conv/created   #inst \"…\"
       :conv/touched   #inst \"…\"}

  An *instance* is the merged shape of an instantiated component:

      {:instance/id        \"ix-7e2\"
       :instance/type      :auth/login
       :instance/parent    \"ix-7c1\" | nil
       :instance/resume    :on-login | nil
       :instance/rendered? false        ; toggled on first emitted patch
       …user state from (:component/init cdef)…}

  The user-defined state lives at the top level of the instance map (not
  under a `:state` key).  Handler functions therefore see one merged map
  and can both read instance metadata (`:instance/id`) and their own
  domain fields by simple keyword lookup.  Handlers must not clobber the
  `:instance/*` keys."
  (:require [dev.zeko.stube.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Id minting
;; ---------------------------------------------------------------------------
;;
;; Ids are short, human-readable, and unique per process.  They are *not*
;; secrets — the cid in the URL is paired with session ownership at slice
;; 4.  For slice 0 they only need to be unique.

(defonce ^:private !cid-counter      (atom 0))
(defonce ^:private !instance-counter (atom 0))

(defn new-cid
  "Mint a fresh conversation id."
  []
  (str "cv-" (format "%06x" (swap! !cid-counter inc))))

(defn new-instance-id
  "Mint a fresh instance id."
  []
  (str "ix-" (format "%06x" (swap! !instance-counter inc))))

;; ---------------------------------------------------------------------------
;; Embed specs
;; ---------------------------------------------------------------------------
;;
;; `(s/embed :ui/login {:redirect-to \"/home\"})` produces a small map
;; that the kernel uses to instantiate a child.  Keeping it as data (not
;; a closure) means flows are still pure and serialisable.

(defn embed
  "Return an embed spec for component `type` initialised with `args`."
  ([type]      (embed type {}))
  ([type args] {:embed/type type :embed/args args}))

(defn embed?
  "True if `x` looks like an embed spec."
  [x]
  (and (map? x) (contains? x :embed/type)))

(defn local-signal
  "Return the per-instance signal key for logical `signal` on `self`.

  Datastar signals are page-global.  Binding two embedded components to
  the same `$answer` would therefore make them share client-side state.
  A local signal keeps the user-facing logical name (`:answer`) while
  suffixing the actual wire key with the instance id:

      (local-signal {:instance/id \"ix-1\"} :answer)
      ;; => :answer-ix-1

  [[merge-kept-signals]] maps local signal keys back to their logical
  names when the component lists the logical key in `:component/keep`."
  [self signal]
  (let [iid (or (:instance/id self)
                (throw (ex-info "local-signal requires an instance map"
                                {:got self})))]
    (keyword (str (name signal) "-" iid))))

;; ---------------------------------------------------------------------------
;; Instance construction
;; ---------------------------------------------------------------------------

(def instance-meta-keys
  "Keys the kernel manages on every instance map.  Handlers must treat
  these as read-only; the kernel ignores any user changes to them.

  `:instance/children` is the slot→iid map populated when a component
  declares `:children` in its definition.  See [[instantiate-tree]].

  `:instance/slot` and `:instance/previous` are used by the
  `[:call-in-slot …]` overlay primitive: the temporary child remembers
  which parent slot it occupies and which child iid should be restored
  when it answers.

  `:stube/context` is adapter-supplied request/application context.  It
  is protected like instance metadata so handlers can read it via
  `s/context` without accidentally persisting edits to the context map."
  #{:instance/id :instance/type :instance/parent
    :instance/resume :instance/rendered? :instance/children
    :instance/slot :instance/previous :stube/context})

(defn instantiate
  "Build a fresh instance map from a component definition and an embed
  spec.  `parent-id` and `resume-key` may be nil for root instances.

  This is the *flat* constructor: it does not look at `:children`.
  Use [[instantiate-tree]] when you want the kernel to materialise the
  whole subtree."
  [cdef {:keys [embed/args]} parent-id resume-key]
  (let [init-fn (or (:component/init cdef) (constantly {}))
        state   (init-fn (or args {}))]
    (merge state
           {:instance/id        (new-instance-id)
            :instance/type      (:component/id cdef)
            :instance/parent    parent-id
            :instance/resume    resume-key
            :instance/rendered? false
            :instance/children  {}})))

(defn instantiate-tree
  "Build a parent instance plus every child eagerly declared by its
  component definition's `:children` map.

  `:children` is either a map `{slot-key embed-spec}` or a function of
  the freshly-initialised parent state returning such a map.  Slot keys
  are arbitrary keywords the parent can reference from its `:render`
  via `(s/render-slot self slot-key)`.

  `lookup-cdef` is a 1-arg function `(component-id) → cdef-map`.  Pass
  `dev.zeko.stube.registry/lookup!` from the kernel; the indirection lets this
  namespace stay registry-agnostic and easy to test in isolation.

  Returns `[parent-inst descendants]` where `descendants` is a
  flat seq of every transitively-instantiated child instance, ready to
  be merged into `:conv/instances`.  The returned `parent-inst` carries
  `:instance/children` populated with `{slot-key child-iid}`."
  [cdef embed-spec parent-id resume-key lookup-cdef]
  (let [self          (instantiate cdef embed-spec parent-id resume-key)
        children-spec (:children cdef)
        children-spec (if (fn? children-spec)
                        (children-spec self)
                        children-spec)
        ;; Walk each slot, recursively building child sub-trees.  Each
        ;; entry yields [slot child-self deeper-descendants].
        slot-entries  (for [[slot child-embed] children-spec]
                        (let [child-cdef (lookup-cdef (:embed/type child-embed))
                              [child-self deeper]
                              (instantiate-tree child-cdef
                                                child-embed
                                                (:instance/id self)
                                                nil
                                                lookup-cdef)]
                          [slot child-self deeper]))
        children-map  (into {} (map (fn [[s c _]] [s (:instance/id c)]))
                            slot-entries)
        descendants   (mapcat (fn [[_ c d]] (cons c d)) slot-entries)]
    [(assoc self :instance/children children-map)
     (vec descendants)]))

(defn preserve-meta
  "Merge `new-state` over `old-instance` while protecting the
  `:instance/*` keys.  Used after a handler returns `self'` so a buggy
  handler can't break the instance metadata."
  [old-instance new-state]
  (merge new-state (select-keys old-instance instance-meta-keys)))

;; ---------------------------------------------------------------------------
;; Conversation construction & basic accessors
;; ---------------------------------------------------------------------------

(defn new-conversation
  "Build an empty conversation."
  []
  (let [now (java.time.Instant/now)]
    {:conv/id        (new-cid)
     :conv/instances {}
     :conv/stack     []
     :conv/history   []
     :conv/created   now
     :conv/touched   now}))

(defn top-id
  "Id of the topmost frame on the stack, or nil if the stack is empty."
  [conv]
  (peek (:conv/stack conv)))

(defn instance
  "The instance map for `iid`, or nil."
  [conv iid]
  (get-in conv [:conv/instances iid]))

(defn top-instance
  "The instance map at the top of the stack, or nil if empty."
  [conv]
  (some->> (top-id conv) (instance conv)))

(defn touch
  "Bump the `:conv/touched` timestamp."
  [conv]
  (assoc conv :conv/touched (java.time.Instant/now)))

(defn snapshot
  "Append the current value of `conv` to its own `:conv/history` so the
  back button can rewind to it.  Persistent maps make this essentially
  free in space.  We strip the previous `:conv/history` from the snapshot
  so history doesn't grow quadratically."
  [conv]
  (update conv :conv/history (fnil conj []) (assoc conv :conv/history [])))

;; ---------------------------------------------------------------------------
;; Stack mutations (used by the kernel)
;; ---------------------------------------------------------------------------

(defn put-many
  "Add a flat seq of instances to `:conv/instances` without touching
  the stack.  Used by the kernel after [[instantiate-tree]] to deposit
  the eagerly-built children alongside their parent."
  [conv instances]
  (update conv :conv/instances merge
          (into {} (map (juxt :instance/id identity)) instances)))

(defn push-instance
  "Add `inst` to `:conv/instances` and push its id onto the stack."
  [conv inst]
  (-> conv
      (assoc-in [:conv/instances (:instance/id inst)] inst)
      (update :conv/stack conj (:instance/id inst))))

(defn descendant-ids
  "Return a vector of all instance ids transitively reachable from `iid`
  via `:instance/children`, including `iid` itself, in pre-order."
  [conv iid]
  (loop [acc [] frontier [iid]]
    (if-let [i (first frontier)]
      (let [inst   (instance conv i)
            child-iids (vals (:instance/children inst))]
        (recur (conj acc i) (into (vec (rest frontier)) child-iids)))
      acc)))

(defn pop-top
  "Pop the top frame and remove its instance — and every embedded
  descendant — from the conversation.  Returns `[conv' popped-id]`."
  [conv]
  (let [popped     (top-id conv)
        all-gone   (descendant-ids conv popped)]
    [(-> conv
         (update :conv/stack pop)
         (update :conv/instances #(apply dissoc % all-gone)))
     popped]))

(defn remove-subtree
  "Remove `iid` and every embedded descendant from `:conv/instances`
  without touching the call stack."
  [conv iid]
  (update conv :conv/instances #(apply dissoc % (descendant-ids conv iid))))

(defn put-instance
  "Replace `inst` in the instances map without touching the stack."
  [conv inst]
  (assoc-in conv [:conv/instances (:instance/id inst)] inst))

(defn child-slot
  "Return the slot key in `parent` currently pointing at `child-iid`, or nil."
  [parent child-iid]
  (some (fn [[slot iid]] (when (= iid child-iid) slot))
        (:instance/children parent)))

(defn set-child-slot
  "Point `parent-id`'s `slot` at `child-iid`.  If `child-iid` is nil,
  remove the slot."
  [conv parent-id slot child-iid]
  (if child-iid
    (assoc-in conv [:conv/instances parent-id :instance/children slot] child-iid)
    (update-in conv [:conv/instances parent-id :instance/children] dissoc slot)))

(defn mark-rendered
  "Set `:instance/rendered? true` on `iid` and *every* descendant the
  parent's render placed into the DOM.  Called once the kernel has
  emitted a frame's first patch onto the wire.

  Marking the whole subtree at once matches reality: Datastar morphs
  the parent's HTML into the page in one shot, so children that the
  parent inlined via `s/render-slot` are now in the DOM and any
  *future* render of that child can use the (cheaper) morph-by-id
  default instead of re-emitting the shell."
  [conv iid]
  (reduce (fn [c i]
            (assoc-in c [:conv/instances i :instance/rendered?] true))
          conv
          (descendant-ids conv iid)))

;; ---------------------------------------------------------------------------
;; Signal merging
;; ---------------------------------------------------------------------------

(defn merge-kept-signals
  "Lift the entries of `signals` whose keys appear in `keep-keys` onto the
  instance map.  This is the per-event two-way binding step: the user
  types in the browser, Datastar updates the signal client-side, and on
  every event the relevant signals land back on `self` before the handler
  sees it.

  If the browser sends a per-instance key produced by [[local-signal]],
  that value is lifted onto the logical kept key.  Local values win over
  same-named global values so a component can safely say `:keep #{:answer}`
  and render `(s/local-bind self :answer)`."
  [inst signals keep-keys]
  (if (empty? keep-keys)
    inst
    (reduce (fn [acc k]
              (let [local-k (when (:instance/id inst)
                              (local-signal inst k))]
                (cond
                  (and local-k (contains? signals local-k))
                  (assoc acc k (get signals local-k))

                  (contains? signals k)
                  (assoc acc k (get signals k))

                  :else
                  acc)))
            inst
            keep-keys)))

(defn merged-self
  "Look up `iid` in `conv`, find its component definition, and return the
  instance with kept signals merged in.  This is the value passed to
  `:render` and `:handle`."
  [conv iid signals]
  (let [inst (instance conv iid)
        cdef (registry/lookup! (:instance/type inst))]
    (cond-> (merge-kept-signals inst signals (:component/keep cdef))
      (contains? conv :conv/context)
      (assoc :stube/context (:conv/context conv)))))

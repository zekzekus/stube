(ns stube.conversation
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
  (:require [stube.registry :as registry]))

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

;; ---------------------------------------------------------------------------
;; Instance construction
;; ---------------------------------------------------------------------------

(def instance-meta-keys
  "Keys the kernel manages on every instance map.  Handlers must treat
  these as read-only; the kernel ignores any user changes to them."
  #{:instance/id :instance/type :instance/parent
    :instance/resume :instance/rendered?})

(defn instantiate
  "Build a fresh instance map from a component definition and an embed
  spec.  `parent-id` and `resume-key` may be nil for root instances."
  [cdef {:keys [embed/args]} parent-id resume-key]
  (let [init-fn (or (:component/init cdef) (constantly {}))
        state   (init-fn (or args {}))]
    (merge state
           {:instance/id        (new-instance-id)
            :instance/type      (:component/id cdef)
            :instance/parent    parent-id
            :instance/resume    resume-key
            :instance/rendered? false})))

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

(defn push-instance
  "Add `inst` to `:conv/instances` and push its id onto the stack."
  [conv inst]
  (-> conv
      (assoc-in [:conv/instances (:instance/id inst)] inst)
      (update :conv/stack conj (:instance/id inst))))

(defn pop-top
  "Pop the top frame and remove its instance from the conversation.
  Returns `[conv' popped-id]`."
  [conv]
  (let [popped (top-id conv)]
    [(-> conv
         (update :conv/stack pop)
         (update :conv/instances dissoc popped))
     popped]))

(defn put-instance
  "Replace `inst` in the instances map without touching the stack."
  [conv inst]
  (assoc-in conv [:conv/instances (:instance/id inst)] inst))

(defn mark-rendered
  "Set `:instance/rendered? true` on `iid`.  Called once the kernel has
  emitted a frame's first patch onto the wire."
  [conv iid]
  (assoc-in conv [:conv/instances iid :instance/rendered?] true))

;; ---------------------------------------------------------------------------
;; Signal merging
;; ---------------------------------------------------------------------------

(defn merge-kept-signals
  "Lift the entries of `signals` whose keys appear in `keep-keys` onto the
  instance map.  This is the per-event two-way binding step: the user
  types in the browser, Datastar updates the signal client-side, and on
  every event the relevant signals land back on `self` before the handler
  sees it."
  [inst signals keep-keys]
  (if (empty? keep-keys)
    inst
    (merge inst (select-keys signals (vec keep-keys)))))

(defn merged-self
  "Look up `iid` in `conv`, find its component definition, and return the
  instance with kept signals merged in.  This is the value passed to
  `:render` and `:handle`."
  [conv iid signals]
  (let [inst (instance conv iid)
        cdef (registry/lookup! (:instance/type inst))]
    (merge-kept-signals inst signals (:component/keep cdef))))

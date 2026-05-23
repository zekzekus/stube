(ns dev.zeko.stube.keyed
  "Keyed-children primitive.

  A parent can declare an ordered set of child component instances
  identified by stable user-facing keys instead of fixed slot names.
  When the set changes, the kernel emits per-child element fragments
  (`:append` / `:remove` / `:outer` against the container) rather than
  re-rendering the whole parent.

  Surface
  ───────

  - **Effect** `(s/set-keyed-children slot pairs)` where `pairs` is
    `[[stable-key embed-spec] ...]` in display order.  Triggers the
    diff fold in [[reconcile!]].
  - **Render helper** `(s/keyed-children self slot)` returns hiccup
    for the container `<div id=PARENTIID--SLOTNAME>` with each child's
    current HTML inlined in order.

  State shape on the parent instance:

      :instance/keyed-slots
      {:slot/cols {:order    [:c1 :c2]
                   :children {:c1 {:iid \"ix-000005\"
                                   :embed {:embed/type :demo/counter
                                           :embed/args {:start 0}}}
                              :c2 {:iid \"ix-000006\"
                                   :embed {…}}}}}

  Diff rules
  ──────────

  - Key in new but not old           → mint, `:start`, emit `:append`
                                       at the right position
                                       (`:append`/`:prepend`/`:after`).
  - Key in old but not new           → `:stop`, remove, emit `:remove`
                                       targeting the child iid.
  - Same key, different embed args   → re-`:init` the child in place
                                       (iid preserved, descendants rebuilt),
                                       emit `:outer`
                                       at the child iid.
  - Same key, same embed args        → no fragment.
  - Different order, same key-set    → emit one `:outer` against the
                                       whole container (cheaper than a
                                       parent re-render; still
                                       distinguishable from individual
                                       diffs in replay traces).

  Before the parent has been rendered the first time, `reconcile!`
  performs all the bookkeeping silently and emits no fragments — the
  parent's normal `render-frame` will pick up the populated state and
  emit one container."
  (:require [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.frame        :as frame]
            [dev.zeko.stube.fragments    :as f]
            [dev.zeko.stube.lifecycle    :as lc]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render]))

(defn container-id
  "DOM id of the keyed-children container element under `parent-id`/`slot`."
  [parent-id slot]
  (str parent-id "--" (name slot)))

(defn child-iid
  "Look up the iid of the child mounted under `slot`/`key` on `self`."
  [self slot key]
  (get-in self [:instance/keyed-slots slot :children key :iid]))

(defn- attach-context
  "Mirror the kernel's context attachment for keyed children, which are
  instantiated from this namespace rather than through `step :call`."
  [conv inst]
  (cond-> inst
    (contains? conv :conv/context)
    (assoc :stube/context (:conv/context conv))))

(defn- attach-tree-context [conv inst descendants]
  [(attach-context conv inst)
   (mapv #(attach-context conv %) descendants)])

(defn- instantiate-child-tree [conv parent-id embed]
  (let [cdef (registry/lookup! (:embed/type embed))]
    (apply attach-tree-context conv
           (conv/instantiate-tree cdef embed parent-id nil registry/lookup!))))

(defn render-children-hiccup
  "Inline the child instances currently registered for `slot` on `self`.
  Mirrors `render/render-slot`: looks each child up in `render/*conv*`,
  invokes its `:render`, returns the hiccup so Chassis serialises it in
  one pass with the parent."
  [self slot]
  (let [conv  (or render/*conv*
                  (throw (ex-info "render/*conv* is unbound; keyed-children needs the render binding"
                                  {:slot   slot
                                   :parent (:instance/id self)})))
        slot-state (get-in self [:instance/keyed-slots slot])
        order      (:order slot-state)
        children   (:children slot-state)]
    (for [k order
          :let [child-iid (get-in children [k :iid])
                child     (some-> child-iid (->> (get (:conv/instances conv))))]
          :when child]
      (let [cdef     (registry/lookup! (:instance/type child))
            render-fn (or (:component/render cdef)
                          frame/default-render)]
        (render-fn child)))))

;; ---------------------------------------------------------------------------
;; Reconciliation
;; ---------------------------------------------------------------------------

(defn- mint-child [conv parent-id embed run-effects-fn]
  (let [[child descendants] (instantiate-child-tree conv parent-id embed)
        child-iid (:instance/id child)
        conv (-> conv
                 (conv/put-instance child)
                 (conv/put-many descendants))
        [conv start-frags]
        (lc/run-start-hooks run-effects-fn conv
                            (into [child-iid] (map :instance/id descendants)))]
    [conv child-iid start-frags]))

(defn- drop-child [conv child-iid run-effects-fn]
  (let [iids (conv/descendant-ids conv child-iid)
        [conv stop-frags] (lc/run-stop-hooks run-effects-fn conv iids)]
    [(conv/remove-subtree conv child-iid) stop-frags]))

(defn- preserve-root-iid
  "Install a freshly-instantiated subtree under the existing keyed-child
  root iid.  Direct descendants must be repointed from the temporary root
  iid to the preserved iid; deeper descendants already point at their
  freshly-minted parents."
  [child descendants child-iid]
  (let [fresh-iid (:instance/id child)]
    [(assoc child :instance/id child-iid :instance/rendered? false)
     (mapv (fn [desc]
             (cond-> desc
               (= fresh-iid (:instance/parent desc))
               (assoc :instance/parent child-iid)))
           descendants)]))

(defn- reinit-child
  "Same key, fresh component state/tree, same root iid.  Stops the old
  subtree, removes it, instantiates the new subtree, preserves the root
  iid, runs `:start`, and returns `[conv' child-iid lifecycle-frags]`."
  [conv parent-id child-iid embed run-effects-fn]
  (let [[conv-stopped stop-frags] (drop-child conv child-iid run-effects-fn)
        [child descendants]      (instantiate-child-tree conv-stopped parent-id embed)
        [child descendants]      (preserve-root-iid child descendants child-iid)
        conv'                    (-> conv-stopped
                                     (conv/put-instance child)
                                     (conv/put-many descendants))
        [conv'' start-frags]     (lc/run-start-hooks
                                    run-effects-fn
                                    conv'
                                    (into [child-iid] (map :instance/id descendants)))]
    [conv'' child-iid (into (vec stop-frags) start-frags)]))

(defn- render-child-outer
  "Render `child-iid` with explicit selector + :outer patch mode so it
  replaces an existing DOM node by id.  Returns `[conv' fragment]`."
  [conv child-iid]
  (frame/render-instance conv child-iid
                         {:selector (str "#" child-iid)
                          :patch-mode :outer}))

(defn- render-child-html
  "Render `child-iid` with default morph-by-id opts; return only the HTML
  string so the caller can wrap it in a container patch fragment."
  [conv child-iid]
  (let [[conv' frag] (frame/render-instance conv child-iid {})]
    [conv' (:fragment/html frag)]))

(defn- container-frag
  ([conv parent-id slot html mode]
   (container-frag conv parent-id slot html mode nil))
  ([_conv parent-id slot html mode neighbor-iid]
   (f/elements html (cond-> {:patch-mode mode}
                      neighbor-iid
                      (assoc :selector (str "#" neighbor-iid))
                      (not neighbor-iid)
                      (assoc :selector (str "#" (container-id parent-id slot)))))))

(defn- remove-frag [child-iid]
  (f/elements "" {:selector (str "#" child-iid) :patch-mode :remove}))

(defn- diff [old-order new-pairs old-children]
  (let [new-keys     (mapv first new-pairs)
        new-embeds   (into {} new-pairs)
        old-key-set  (set old-order)
        new-key-set  (set new-keys)
        added        (filter (complement old-key-set) new-keys)
        removed      (remove new-key-set old-order)
        changed-args (filter (fn [k]
                               (and (old-key-set k)
                                    (not= (get-in old-children [k :embed])
                                          (get new-embeds k))))
                             new-keys)
        reorder?     (and (= old-key-set new-key-set)
                          (not= old-order new-keys))]
    {:added        added
     :removed      removed
     :changed-args changed-args
     :reorder?     reorder?
     :new-keys     new-keys
     :new-embeds   new-embeds}))

(defn- update-slot-state [parent slot new-keys new-embeds child-iids]
  (let [children (into {} (map (fn [k]
                                 [k {:iid   (get child-iids k)
                                     :embed (get new-embeds k)}]))
                       new-keys)]
    (assoc-in parent [:instance/keyed-slots slot]
              {:order (vec new-keys) :children children})))

(defn- prior-iid-for-position [new-keys idx child-iids]
  ;; idx is the index in new-keys where the appended key now sits.
  ;; Find the iid of the preceding new-key (if any).  Returns nil if
  ;; the appended key is at the head.
  (when (pos? idx)
    (get child-iids (nth new-keys (dec idx)))))

(defn- drop-step [run-effects-fn rendered? old-children]
  (fn [[c fs] k]
    (let [iid (get-in old-children [k :iid])
          [c' stop-frags] (drop-child c iid run-effects-fn)]
      [c' (cond-> (into fs stop-frags)
            rendered? (conj (remove-frag iid)))])))

(defn- reinit-step [run-effects-fn rendered? parent-id new-embeds old-children]
  (fn [[c fs] k]
    (let [iid    (get-in old-children [k :iid])
          [c' _ lifecycle-frags] (reinit-child c parent-id iid
                                               (get new-embeds k)
                                               run-effects-fn)]
      (if rendered?
        (let [[c'' frag] (render-child-outer c' iid)]
          [c'' (conj (into fs lifecycle-frags) frag)])
        [c' (into fs lifecycle-frags)]))))

(defn- mint-step
  [run-effects-fn rendered? parent-id slot new-keys new-embeds]
  (let [last-idx (dec (count new-keys))]
    (fn [[c fs iids] k]
      (let [[c' iid start-frags]
            (mint-child c parent-id (get new-embeds k) run-effects-fn)
            iids' (assoc iids k iid)]
        (if rendered?
          (let [[c'' html] (render-child-html c' iid)
                idx       (.indexOf ^java.util.List new-keys k)
                neighbor  (prior-iid-for-position new-keys idx iids')
                ;; tail → :append on container; head → :prepend on
                ;; container; middle → :after the preceding sibling so
                ;; replay traces stay positional.
                mode      (cond
                            (= idx last-idx) :append
                            (nil? neighbor)  :prepend
                            :else            :after)
                frag      (container-frag c'' parent-id slot html mode
                                          (when (= mode :after) neighbor))]
            [c'' (into fs (conj start-frags frag)) iids'])
          [c' (into fs start-frags) iids'])))))

(defn reconcile!
  "Apply the `:set-keyed-children` effect: update `parent-id`'s
  `:instance/keyed-slots` for `slot` to match `pairs`, mint/remove/
  re-init children, and emit per-change element fragments when the
  parent is already rendered.  Returns `[conv' fragments]`."
  [conv parent-id slot pairs run-effects-fn]
  (let [parent       (conv/instance conv parent-id)
        rendered?    (boolean (:instance/rendered? parent))
        old-state    (get-in parent [:instance/keyed-slots slot])
        old-order    (vec (:order old-state))
        old-children (:children old-state)
        {:keys [added removed changed-args reorder? new-keys new-embeds]}
        (diff old-order pairs old-children)

        ;; iids the new state inherits from the old (= preserved keys).
        preserved-iids
        (into {}
              (keep (fn [k]
                      (when-let [iid (get-in old-children [k :iid])]
                        [k iid])))
              (filter (set old-order) new-keys))

        [conv frags]
        (reduce (drop-step run-effects-fn rendered? old-children)
                [conv []]
                removed)

        [conv frags]
        (reduce (reinit-step run-effects-fn rendered? parent-id
                             new-embeds old-children)
                [conv frags]
                changed-args)

        [conv frags child-iids]
        (reduce (mint-step run-effects-fn rendered? parent-id slot
                           new-keys new-embeds)
                [conv frags preserved-iids]
                added)

        parent'   (update-slot-state (conv/instance conv parent-id)
                                     slot new-keys new-embeds child-iids)
        conv      (conv/put-instance conv parent')

        ;; Reorder-only (no add/remove/change-args): one container
        ;; :outer patch is cheaper than a parent re-render and stays
        ;; distinguishable in replay traces.
        [conv frags]
        (if (and reorder? rendered?
                 (empty? added) (empty? removed) (empty? changed-args))
          (let [[conv htmls]
                (reduce (fn [[c hs] k]
                          (let [[c' html] (render-child-html c (get child-iids k))]
                            [c' (conj hs html)]))
                        [conv []]
                        new-keys)
                container-html (str "<div id=\"" (container-id parent-id slot) "\">"
                                    (apply str htmls)
                                    "</div>")]
            [conv (conj frags (container-frag conv parent-id slot
                                              container-html :outer))])
          [conv frags])]
    [conv frags]))

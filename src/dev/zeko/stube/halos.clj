(ns dev.zeko.stube.halos
  "Development overlay — Seaside-style halos.

  Off by default; activated when (a) the server was started with
  `:halos? true` and (b) the conversation carries `:conv/halos? true`
  (set by the shell handler when the URL has `?halos=1`).

  Three layers live here:

  1. **Hiccup decoration** — [[decorate-root]] merges
     `data-stube-iid` / `data-stube-type` into every instance's outer
     hiccup attrs. The kernel calls this once per render when halos are
     active.
  2. **Side-panel hiccup** — [[panel-hiccup]] builds the inspector
     content fetched by `halos.js` from `<base>/halos/<cid>/panel`.
     The panel is plain server-rendered HTML; no SSE / no extra conv.
  3. **REPL helpers** — [[tree]], [[instance]], [[history]],
     [[where]]. Re-exported by `dev.zeko.stube.core`.

  No I/O lives here. The http layer turns these values into responses."
  (:require [clojure.pprint              :as pprint]
            [clojure.string              :as str]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.registry     :as registry]))

;; ---------------------------------------------------------------------------
;; Hiccup decoration
;; ---------------------------------------------------------------------------

(defn- data-attrs [inst]
  {:data-stube-iid  (:instance/id   inst)
   :data-stube-type (str (:instance/type inst))})

(defn decorate-root
  "Merge halo data-attrs onto the outer attribute map of `hiccup` so the
  client overlay can find the instance root.

  Three shapes show up in practice:

  * `[:tag {…} …children…]`         — common case; merge into the attr map
  * `[:tag …children…]` (no attrs)  — splice a fresh attr map in
  * anything else                   — leave untouched (the overlay simply
                                       skips this root)"
  [hiccup inst]
  (cond
    (and (vector? hiccup) (map? (second hiccup)))
    (let [[tag attrs & rest] hiccup]
      (into [tag (merge (data-attrs inst) attrs)] rest))

    (and (vector? hiccup) (keyword? (first hiccup)))
    (let [[tag & rest] hiccup]
      (into [tag (data-attrs inst)] rest))

    :else
    hiccup))

;; ---------------------------------------------------------------------------
;; REPL helpers — pure, take a conversation value
;; ---------------------------------------------------------------------------

(defn- source-of [type-kw]
  (some-> (registry/lookup type-kw) :component/source))

(defn tree-data
  "Plain-data view of the conversation's instance tree. Order: top of
  stack first, embedded children indented under their parent.

  Each node carries:

  * `:slots`        — fixed `:instance/children` slots (sorted by key)
  * `:keyed-slots`  — `:instance/keyed-slots` content; each entry has
                      `:slot` + `:children` (ordered `[{:key … :child …}]`)"
  [conv]
  (letfn [(node [iid]
            (let [inst (conv/instance conv iid)]
              {:iid         iid
               :type        (:instance/type inst)
               :resume      (:instance/resume inst)
               :slots       (->> (:instance/children inst)
                                 (sort-by key)
                                 (mapv (fn [[s ciid]]
                                         {:slot s :child (node ciid)})))
               :keyed-slots (->> (:instance/keyed-slots inst)
                                 (sort-by key)
                                 (mapv (fn [[slot {:keys [order children]}]]
                                         {:slot     slot
                                          :children (mapv (fn [k]
                                                            {:key   k
                                                             :child (node (get-in children [k :iid]))})
                                                          order)})))}))]
    (mapv node (:conv/stack conv))))

(defn- print-tree-node [node depth]
  (let [pad (apply str (repeat depth "  "))]
    (println (str pad (:type node) "  " (:iid node)
                  (when-let [r (:resume node)] (str "  :resume " r))))
    (doseq [{:keys [slot child]} (:slots node)]
      (println (str pad "  └ " slot))
      (print-tree-node child (+ depth 2)))
    (doseq [{:keys [slot children]} (:keyed-slots node)]
      (println (str pad "  └ " slot " (keyed)"))
      (doseq [{:keys [key child]} children]
        (println (str pad "    [" key "]"))
        (print-tree-node child (+ depth 3))))))

(defn tree
  "Pretty-print the conversation's component tree. Returns the tree
  data so it stays REPL-inspectable."
  [conv]
  (let [data (tree-data conv)]
    (doseq [n data] (print-tree-node n 0))
    data))

(defn instance
  "Return the instance map for `iid`."
  [conv iid]
  (conv/instance conv iid))

(defn history
  "Summarise `:conv/history` as `[{:idx :touched :top-iid :top-type}]`."
  [conv]
  (vec
    (map-indexed (fn [i snap]
                   {:idx      i
                    :touched  (:conv/touched snap)
                    :top-iid  (conv/top-id snap)
                    :top-type (some->> (conv/top-id snap)
                                       (conv/instance snap)
                                       :instance/type)})
                 (:conv/history conv))))

(defn- instance-summary [inst]
  {:id       (:instance/id inst)
   :type     (:instance/type inst)
   :parent   (:instance/parent inst)
   :resume   (:instance/resume inst)
   :children (:instance/children inst)
   :state    (apply dissoc inst conv/instance-meta-keys)})

(defn inspect-summary
  "Compact summary of a conversation value, used by `s/inspect` to
  pretty-print at the REPL.  Pure: returns plain data, no I/O."
  [c]
  {:id            (:conv/id c)
   :created       (:conv/created c)
   :touched       (:conv/touched c)
   :ended?        (boolean (:conv/ended? c))
   :history-count (count (:conv/history c))
   :last-event    (:conv/last-event c)
   :stack         (mapv #(instance-summary (conv/instance c %))
                        (:conv/stack c))
   :instances     (into (sorted-map)
                        (map (fn [[iid inst]] [iid (instance-summary inst)]))
                        (:conv/instances c))})

(defn where
  "Return the registered source location for component `type-kw`
  (`{:file … :line …}` if available), captured by the `defcomponent`
  macro at definition time."
  [type-kw]
  (source-of type-kw))

;; ---------------------------------------------------------------------------
;; Side panel — pure hiccup, rendered by the http layer to plain HTML
;; ---------------------------------------------------------------------------

(def ^:private tabs [:tree :instance :html :history])

(defn- tab-button [current tab]
  [:button (cond-> {:data-halo-tab (name tab)}
             (= current tab) (assoc :class "active"))
   (str/capitalize (name tab))])

(defn- panel-header [current-tab]
  (into [:header]
        (concat
          (for [t tabs] (tab-button current-tab t))
          [[:span {:style "flex:1"}]
           [:button {:data-halo-refresh "1" :title "Refresh"} "↻"]])))

(defn- nav-link [iid label]
  [:a {:data-halo-iid iid} label])

(defn- pretty [x]
  (with-out-str (pprint/pprint x)))

(defn- node-label [node]
  (str (:type node) "  " (:iid node)
       (when-let [r (:resume node)] (str "  :resume " r))))

(defn- render-tree-node [node depth]
  [:div {:style (str "padding-left:" (* depth 10) "px;")}
   (nav-link (:iid node) (node-label node))
   (for [{:keys [slot child]} (:slots node)]
     [:div
      [:span {:style "color:#888"} (str "└ " slot)]
      (render-tree-node child (inc depth))])
   (for [{:keys [slot children]} (:keyed-slots node)]
     [:div
      [:span {:style "color:#888"} (str "└ " slot " (keyed)")]
      (for [{:keys [key child]} children]
        [:div {:style (str "padding-left:" (* (inc depth) 10) "px;")}
         [:span {:style "color:#666"} (str "[" key "]  ")]
         (render-tree-node child (+ depth 2))])])])

(defn- tree-body [conv _selected-iid]
  (let [data (tree-data conv)]
    (if (seq data)
      (for [n data] (render-tree-node n 0))
      [:p {:style "color:#888"} "No instances on the stack."])))

(defn- field-row [label value]
  [:tr
   [:td {:style "color:#888;padding-right:8px;vertical-align:top;white-space:nowrap"}
    (str label)]
   [:td value]])

(defn- field-link [iid]
  (when iid (nav-link iid iid)))

(defn- instance-section [title body]
  [:div {:style "margin-bottom:10px"}
   [:div {:style "color:#9333ea;font-weight:bold;margin-bottom:4px"} title]
   body])

(defn- user-state
  "The user-defined keys on an instance — what handler code reads/writes."
  [inst]
  (apply dissoc inst (conj conv/instance-meta-keys :instance/last-html)))

(defn- instance-body [conv iid]
  (if-let [inst (and iid (conv/instance conv iid))]
    (let [type        (:instance/type inst)
          src         (source-of type)
          parent      (:instance/parent inst)
          children    (:instance/children inst)
          keyed-slots (:instance/keyed-slots inst)
          state       (user-state inst)]
      [:div
       (instance-section
         "Meta"
         [:table
          (field-row "iid" iid)
          (field-row "type" (str type))
          (field-row "parent" (or (field-link parent) "—"))
          (when-let [r (:instance/resume inst)] (field-row "resume" (str r)))
          (when src (field-row "source" (str (:file src) ":" (:line src))))
          (field-row "rendered?" (str (boolean (:instance/rendered? inst))))])
       (when (seq children)
         (instance-section
           "Children"
           [:table
            (for [[slot ciid] (sort-by key children)]
              (field-row (str slot) (field-link ciid)))]))
       (when (seq keyed-slots)
         (instance-section
           "Keyed children"
           [:div
            (for [[slot {:keys [order children]}] (sort-by key keyed-slots)]
              [:div {:style "margin-bottom:6px"}
               [:div {:style "color:#888"} (str slot)]
               [:table
                (for [k order
                      :let [ciid (get-in children [k :iid])]]
                  (field-row (str "  [" k "]") (field-link ciid)))]])]))
       (instance-section
         "State"
         (if (seq state)
           [:pre (pretty state)]
           [:p {:style "color:#888;margin:0"} "(empty)"]))])
    [:p {:style "color:#888"} "Select an instance from the Tree tab."]))

(defn- html-body [conv iid]
  (if-let [inst (and iid (conv/instance conv iid))]
    (if-let [html (:instance/last-html inst)]
      [:pre html]
      [:p {:style "color:#888"}
       "No render captured yet for "
       (str (:instance/type inst) "  " iid) "."])
    [:p {:style "color:#888"} "Select an instance from the Tree tab."]))

(defn- format-touched [^java.time.Instant t]
  (when t (subs (str t) 0 (min 19 (count (str t))))))

(defn instance-history-data
  "State of `iid` across each historical conversation snapshot, oldest
  first, plus the current state as the final entry. Snapshots where the
  instance did not yet (or no longer) exist are skipped."
  [conv iid]
  (let [snaps (conj (vec (:conv/history conv)) conv)]
    (vec
      (keep-indexed
        (fn [idx snap]
          (when-let [inst (conv/instance snap iid)]
            {:idx     idx
             :touched (:conv/touched snap)
             :state   (user-state inst)}))
        snaps))))

(defn- changed?
  "True if user-state differs from the previous entry's user-state."
  [entries idx]
  (or (zero? idx)
      (not= (:state (nth entries idx))
            (:state (nth entries (dec idx))))))

(defn- per-instance-history-body [conv iid]
  (let [inst    (conv/instance conv iid)
        entries (instance-history-data conv iid)]
    [:div
     [:div {:style "color:#9333ea;font-weight:bold;margin-bottom:6px"}
      (str (:instance/type inst) "  " iid)]
     (if (seq entries)
       [:ol {:reversed "1" :style "padding-left:1.2rem"}
        (for [[i {:keys [idx touched state]}] (map-indexed vector (reverse entries))
              :let [forward-idx (- (dec (count entries)) i)
                    changed     (changed? entries forward-idx)]]
          [:li {:style (when-not changed "opacity:0.45")}
           [:span {:style "color:#888"}
            (or (format-touched touched) "?")
            (str "  #" idx (when-not changed "  (no change)"))]
           [:pre {:style "margin:4px 0 0 0"} (pretty state)]])]
       [:p {:style "color:#888"} "No snapshots include this instance."])]))

(defn- overall-history-body [conv]
  (let [snaps (history conv)]
    (if (seq snaps)
      [:ol {:reversed "1" :style "padding-left:1.2rem"}
       (for [{:keys [idx touched top-type top-iid]} (reverse snaps)]
         [:li
          [:span {:style "color:#888"}
           (or (format-touched touched) "?") "  "]
          (if top-iid
            (nav-link top-iid (str top-type "  " top-iid))
            (str top-type))
          [:span {:style "color:#666"} (str "  #" idx)]])]
      [:p {:style "color:#888"} "No history snapshots yet."])))

(defn- history-body [conv iid]
  (if (and iid (conv/instance conv iid))
    (per-instance-history-body conv iid)
    (overall-history-body conv)))

(defn panel-hiccup
  "Return the hiccup for one panel fetch. `tab` is one of
  `:tree :instance :html :history` (defaults to `:tree`); `iid` is the
  currently-selected instance (defaults to the top of the stack)."
  [conv {:keys [iid tab]}]
  (let [tab      (or (#{:tree :instance :html :history} tab) :tree)
        iid      (or iid (conv/top-id conv))
        body     (case tab
                   :tree     (tree-body conv iid)
                   :instance (instance-body conv iid)
                   :html     (html-body conv iid)
                   :history  (history-body conv iid))]
    [:div
     (panel-header tab)
     [:div {:class "stube-halo-body"} body]]))

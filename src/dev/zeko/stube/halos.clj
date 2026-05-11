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
     content fetched by `halos.js` from `/stube/halos/<cid>/panel`.
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
  stack first, embedded children indented under their parent."
  [conv]
  (letfn [(node [iid]
            (let [inst (conv/instance conv iid)]
              {:iid    iid
               :type   (:instance/type inst)
               :resume (:instance/resume inst)
               :slots  (->> (:instance/children inst)
                            (sort-by key)
                            (mapv (fn [[s ciid]]
                                    {:slot s :child (node ciid)})))}))]
    (mapv node (:conv/stack conv))))

(defn- print-tree-node [node depth]
  (let [pad (apply str (repeat depth "  "))]
    (println (str pad (:type node) "  " (:iid node)
                  (when-let [r (:resume node)] (str "  :resume " r))))
    (doseq [{:keys [slot child]} (:slots node)]
      (println (str pad "  └ " slot))
      (print-tree-node child (+ depth 2)))))

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

(defn- render-tree-node [node depth]
  [:div {:style (str "padding-left:" (* depth 10) "px;")}
   (nav-link (:iid node)
             (str (:type node) "  " (:iid node)
                  (when-let [r (:resume node)] (str "  :resume " r))))
   (for [{:keys [slot child]} (:slots node)]
     [:div
      [:span {:style "color:#888"} (str "└ " slot)]
      (render-tree-node child (inc depth))])])

(defn- tree-body [conv _selected-iid]
  (let [data (tree-data conv)]
    (if (seq data)
      (for [n data] (render-tree-node n 0))
      [:p {:style "color:#888"} "No instances on the stack."])))

(defn- instance-body [conv iid]
  (if-let [inst (and iid (conv/instance conv iid))]
    (let [type (:instance/type inst)
          src  (source-of type)]
      [:div
       [:div {:style "margin-bottom:6px;color:#888"}
        (str type "  " iid)
        (when src
          (str "  " (:file src) ":" (:line src)))]
       [:pre (pretty inst)]])
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

(defn- history-body [conv]
  (let [snaps (history conv)]
    (if (seq snaps)
      [:ol {:reversed "1" :style "padding-left:1.2rem"}
       (for [{:keys [idx touched top-type top-iid]} (reverse snaps)]
         [:li
          [:span {:style "color:#888"}
           (or (format-touched touched) "?") "  "]
          (str top-type "  " top-iid)
          [:span {:style "color:#666"} (str "  #" idx)]])]
      [:p {:style "color:#888"} "No history snapshots yet."])))

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
                   :history  (history-body conv))]
    [:div
     (panel-header tab)
     [:div {:class "stube-halo-body"} body]]))

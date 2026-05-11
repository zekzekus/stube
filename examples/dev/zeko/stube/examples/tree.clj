(ns dev.zeko.stube.examples.tree
  "Expandable tree — Seaside's `WATree` / class-browser shape.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/tree>.

  One component owns an EDN tree and a set of expanded node ids.  The
  renderer is recursive; each disclosure button sends a structured
  `[:toggle node-id]` event back to the same handler."
  (:require [dev.zeko.stube.core :as s]))

;; ---------------------------------------------------------------------------
;; Demo tree
;; ---------------------------------------------------------------------------

(def ^:private project-tree
  {:id       :node/project
   :label    "stube"
   :kind     "project"
   :children [{:id       :node/src
               :label    "src/dev/zeko/stube"
               :kind     "namespace root"
               :children [{:id :node/kernel       :label "kernel.clj"       :kind "runtime"}
                          {:id :node/conversation :label "conversation.clj" :kind "data model"}
                          {:id :node/render       :label "render.clj"       :kind "hiccup bridge"}
                          {:id :node/server       :label "server.clj"       :kind "http lifecycle"}]}
              {:id       :node/examples
               :label    "examples/dev/zeko/stube/examples"
               :kind     "demo catalogue"
               :children [{:id :node/tier1 :label "tier 1" :kind "surface expansion"
                           :children [{:id :node/calc     :label "calc.clj"     :kind "events"}
                                      {:id :node/dialogs  :label "dialogs.clj"  :kind "call/answer"}
                                      {:id :node/calendar :label "calendar.clj" :kind "payloads"}]}
                          {:id :node/tier2 :label "tier 2" :kind "current sweep"
                           :children [{:id :node/paged      :label "paginated_list.clj" :kind "pagination"}
                                      {:id :node/report     :label "table_report.clj"   :kind "sorting"}
                                      {:id :node/tree-demo  :label "tree.clj"           :kind "recursive render"}
                                      {:id :node/breadcrumb :label "breadcrumb.clj"     :kind "decoration"}]}]}
              {:id       :node/docs
               :label    "docs"
               :kind     "design notes"
               :children [{:id :node/seaside-doc :label "seaside-examples.md" :kind "catalogue"}
                          {:id :node/v21         :label "v2_1.md"             :kind "current design"}]}]})

;; ---------------------------------------------------------------------------
;; Pure helpers
;; ---------------------------------------------------------------------------

(defn- branch? [node]
  (seq (:children node)))

(defn- branch-ids [node]
  (into #{}
        (comp (filter branch?) (map :id))
        (tree-seq branch? :children node)))

(defn- toggle [s x]
  (if (contains? s x) (disj s x) (conj s x)))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(declare render-node)

(defn- disclosure [self node expanded?]
  (if (branch? node)
    [:button (merge {:type  "button"
                     :class "stube-button"
                     :style "width:2rem; padding:0.1rem 0.25rem;"}
                    (s/on self :click :as [:toggle (:id node)]))
     (if expanded? "▾" "▸")]
    [:span {:style "display:inline-block; width:2rem; color:#aaa; text-align:center;"}
     "•"]))

(defn- render-node [self node depth]
  (let [expanded? (contains? (:expanded self) (:id node))]
    [:li {:key   (:id node)
          :style "list-style:none; margin:0;"}
     [:div {:style (str "display:flex; align-items:center; gap:0.35rem; "
                        "padding:0.2rem 0; padding-left:" (* depth 1.2) "rem;")}
      (disclosure self node expanded?)
      [:strong (:label node)]
      [:code {:style "color:#777; font-size:0.8rem;"} (:kind node)]]
     (when (and (branch? node) expanded?)
       [:ul {:style "margin:0; padding:0;"}
        (for [child (:children node)]
          (render-node self child (inc depth)))])]))

;; ---------------------------------------------------------------------------
;; Component
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/tree
  :doc "WATree port: recursive rendering over an EDN tree with per-node expansion state."

  :init
  (constantly {:tree     project-tree
               :expanded #{:node/project :node/src :node/examples :node/tier2}})

  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :class "stube-card"
               :style "max-width:48rem; margin:1rem; font-family:system-ui, sans-serif;"}
     [:header {:style "display:flex; align-items:baseline; gap:0.75rem;"}
      [:h2 {:style "margin-top:0;"} "Tree"]
      [:span {:style "color:#666; font-size:0.9rem;"}
       (count (:expanded self)) " open branches"]]
     [:p {:style "color:#555;"}
      "A class-browser-style tree: one recursive renderer, one expansion "
      "set, and structured toggle events carrying the node id."]
     [:div {:class "stube-actions"}
      [:button (merge {:type "button" :class "stube-button"}
                      (s/on self :click :as :expand-all))
       "Expand all"]
      [:button (merge {:type "button" :class "stube-button"}
                      (s/on self :click :as :collapse-all))
       "Collapse all"]]
     [:ul {:style "margin:1rem 0 0; padding:0;"}
      (render-node self (:tree self) 0)]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :toggle       [(update self :expanded toggle payload) []]
      :expand-all   [(assoc self :expanded (branch-ids (:tree self))) []]
      :collapse-all [(assoc self :expanded #{}) []]
      [self []])))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/tree" :demo/tree)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

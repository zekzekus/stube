(ns dev.zeko.stube.examples.breadcrumb
  "Breadcrumb decoration — Seaside's `WAPath` / `WATrail`.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/breadcrumb>.

  The base page component owns navigation state and content.  The mounted
  demo is a decorated component definition that wraps the base renderer
  with a breadcrumb trail, exercising `s/decorate` end to end."
  (:require [dev.zeko.stube.core :as s]))

;; ---------------------------------------------------------------------------
;; Page model
;; ---------------------------------------------------------------------------

(def ^:private pages
  {:examples       {:label "Examples"
                    :summary "The root of the stube demo catalogue."
                    :children [:tier-1 :tier-2 :book]}
   :tier-1         {:label "Tier 1"
                    :summary "Demos that expanded the public surface: events, dialogs, tabs, calendar, and todo editing."
                    :children [:calc :dialogs :tabs :calendar :todo]}
   :tier-2         {:label "Tier 2"
                    :summary "Demos that need no new primitives but exercise more Seaside catalogue patterns."
                    :children [:paginated-list :table-report :tree :breadcrumb :example-browser]}
   :book           {:label "Book app"
                    :summary "The HPI tutorial ToDo port and its framework findings."
                    :children [:seaside-todo]}
   :calc           {:label "Calculator"       :summary "Dense button routing in one component."}
   :dialogs        {:label "Dialogs"          :summary "Reusable call/answer dialogs."}
   :tabs           {:label "Tabs"             :summary "Inactive embedded children survive tab switches."}
   :calendar       {:label "Calendar"         :summary "A non-trivial date grid with structured payloads."}
   :todo           {:label "Todo"             :summary "Slot-local call/answer for in-place editing."}
   :paginated-list {:label "Paginated list"   :summary "WABatchedList: page state around a row-render callback."}
   :table-report   {:label "Table report"     :summary "WATableReport: EDN column maps and click-to-sort headers."}
   :tree           {:label "Tree"             :summary "WATree: recursive rendering plus an expansion set."}
   :breadcrumb     {:label "Breadcrumb"       :summary "WAPath/WATrail: this page, implemented as a decoration."}
   :example-browser {:label "Example browser" :summary "WAExampleBrowser: dynamic registry lookup and detail child swapping."}
   :seaside-todo   {:label "Seaside ToDo"     :summary "The HPI tutorial application in stube."}})

(defn- page [id]
  (get pages id {:label (name id) :summary "Unknown page."}))

(defn- without-root-id [hiccup]
  (if (and (vector? hiccup) (map? (second hiccup)))
    (let [[tag attrs & body] hiccup]
      (into [tag (dissoc attrs :id)] body))
    hiccup))

;; ---------------------------------------------------------------------------
;; Base component behaviour
;; ---------------------------------------------------------------------------

(defn- render-child-link [self child-id]
  (let [{:keys [label summary]} (page child-id)]
    [:button (merge {:type  "button"
                     :class "stube-button stube-button--block"
                     :style "margin-bottom:0.35rem;"}
                    (s/on self :click :as [:open child-id]))
     [:strong label]
     [:span {:style "display:block; color:#555; font-size:0.85rem; margin-top:0.15rem;"}
      summary]]))

(defn- render-page-body [self]
  (let [current             (peek (:path self))
        {:keys [label summary children]} (page current)]
    [:article (s/root-attrs self {:class "stube-card"
                                  :style "max-width:42rem;"})
     [:h2 {:style "margin-top:0;"} label]
     [:p summary]
     (if (seq children)
       [:div {:class "stube-stack"}
        (for [child children]
          (with-meta (render-child-link self child) {:key child}))]
       [:p {:style "color:#666;"}
        "Leaf page.  Use the breadcrumb trail above to jump back to a parent."])
     [:p {:style "color:#777; font-size:0.85rem; margin-bottom:0;"}
      "The content above comes from the base component.  The trail is added "
      "by a decorated component definition."]]))

(defn- handle-navigation [self {:keys [event payload]}]
  (case event
    :jump (update self :path #(subvec (vec %) 0 (inc payload)))
    :open (let [current  (peek (:path self))
                children (set (:children (page current)))]
            (when (contains? children payload)
              (update self :path conj payload)))
    nil))

(s/defcomponent :demo/breadcrumb-page
  :doc "Undecorated breadcrumb demo page: owns path state and content navigation."
  :init   (constantly {:path [:examples :tier-2]})
  :render render-page-body
  :handle handle-navigation)

;; ---------------------------------------------------------------------------
;; Decoration
;; ---------------------------------------------------------------------------

(defn- render-trail [self]
  [:nav {:aria-label "Breadcrumb"
         :style      "display:flex; align-items:center; flex-wrap:wrap; gap:0.35rem;
                      margin-bottom:0.75rem;"}
   (for [[idx id] (map-indexed vector (:path self))]
     (let [last? (= idx (dec (count (:path self))))]
       [:span {:key   id
               :style "display:inline-flex; align-items:center; gap:0.35rem;"}
        [:button (merge {:type     "button"
                         :class    (str "stube-button"
                                        (when last? " stube-button--primary"))
                         :disabled (boolean last?)}
                        (s/on self :click :as [:jump idx]))
         (:label (page id))]
        (when-not last? [:span {:style "color:#999;"} "›"])]))])

(def ^:private breadcrumb-component
  (s/decorate! (s/registry-lookup :demo/breadcrumb-page)
    (fn [base]
      {:component/id  :demo/breadcrumb
       :component/doc "WAPath/WATrail port: a base page decorated with a breadcrumb trail via s/decorate."
       :component/render
       (fn [self]
         [:section (s/root-attrs self {:style "padding:1rem; font-family:system-ui, sans-serif;"})
          [:h2 "Breadcrumb decoration"]
          [:p {:style "max-width:42rem; color:#555;"}
           "The mounted component is not the base page.  It is a decorated "
           "component definition that reuses the base init/handler and wraps "
           "the base render output with this breadcrumb trail."]
          (render-trail self)
          (without-root-id ((:component/render base) self))])})))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/breadcrumb" :demo/breadcrumb)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

(ns dev.zeko.stube.examples.example-browser
  "Interactive example browser — Seaside's `WAExampleBrowser`.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/> or
  <http://localhost:8080/example-browser>.

  The browser requires every shipped demo, introspects the mounted flow
  and registered component at render time, and swaps a selected detail
  child into view.  It is also the examples server landing page."
  (:require [clojure.string :as str]
            [dev.zeko.stube.core :as s]
            [dev.zeko.stube.examples.breadcrumb]
            [dev.zeko.stube.examples.calc]
            [dev.zeko.stube.examples.calendar]
            [dev.zeko.stube.examples.chat]
            [dev.zeko.stube.examples.clock]
            [dev.zeko.stube.examples.dialogs]
            [dev.zeko.stube.examples.file-upload]
            [dev.zeko.stube.examples.guess]
            [dev.zeko.stube.examples.kasten.desk]
            [dev.zeko.stube.examples.multicounter]
            [dev.zeko.stube.examples.paginated-list]
            [dev.zeko.stube.examples.protected-counter]
            [dev.zeko.stube.examples.seaside-todo]
            [dev.zeko.stube.examples.shared-counter]
            [dev.zeko.stube.examples.table-report]
            [dev.zeko.stube.examples.tabs]
            [dev.zeko.stube.examples.todo]
            [dev.zeko.stube.examples.tree]
            [dev.zeko.stube.examples.wizard]))

;; ---------------------------------------------------------------------------
;; Catalogue
;; ---------------------------------------------------------------------------

(def demo-catalog
  [{:path "/guess"          :title "Guess the number"          :group "Foundation"
    :blurb "Linear flow with `defflow` and `s/await`."}
   {:path "/multicounter"   :title "Multicounter"              :group "Foundation"
    :blurb "Three independent embedded counters; morph-by-id keeps siblings intact."}
   {:path "/wizard"         :title "Wizard with Back"          :group "Foundation"
    :blurb "Multi-step form whose Back button rewinds the conversation."}
   {:path "/calc"           :title "Calculator"                :group "Tier 1"
    :blurb "Single component with dense button-event routing."}
   {:path "/dialogs"        :title "Confirm / Prompt / Choose" :group "Tier 1"
    :blurb "Reusable modal dialogs via call/answer convenience helpers."}
   {:path "/tabs"           :title "Tabbed navigation"         :group "Tier 1"
    :blurb "Inactive embedded children stay alive while off-screen."}
   {:path "/calendar"       :title "Mini calendar picker"      :group "Tier 1"
    :blurb "A date-grid widget using structured per-cell click payloads."}
   {:path "/todo"           :title "Todo list"                 :group "Tier 1"
    :blurb "Dynamic list with slot-local in-place edit via `:call-in-slot`."}
   {:path "/paginated-list" :title "Paginated list"            :group "Tier 2"
    :blurb "WABatchedList: pagination state plus an EDN-safe row-render callback."}
   {:path "/table-report"   :title "Table report"              :group "Tier 2"
    :blurb "WATableReport: column config maps and click-to-sort headers."}
   {:path "/tree"           :title "Tree"                      :group "Tier 2"
    :blurb "WATree: recursive rendering and per-node expansion state."}
   {:path "/breadcrumb"     :title "Breadcrumb decoration"     :group "Tier 2"
    :blurb "WAPath/WATrail: a base page wrapped with `s/decorate`."}
   {:path "/example-browser" :title "Example browser"          :group "Tier 2"
    :blurb "WAExampleBrowser: dynamic mount/registry lookup plus detail child swapping."}
   {:path "/file-upload"    :title "File upload"               :group "Tier 3"
    :blurb "WAFileUploadExample: multipart upload route dispatching `:upload-received`."}
   {:path "/clock"          :title "Clock / turbo counter"     :group "Tier 3"
    :blurb "WAClock and WATurboCounter: cid-scoped scheduled events via `s/after`."}
   {:path "/shared-counter" :title "Shared counter / report"   :group "Tier 3"
    :blurb "CTCounter and CTReport: shared app state plus topic subscriptions."}
   {:path "/chat"           :title "Chat"                      :group "Tier 3"
    :blurb "CTChat: multi-user publish/subscribe over live conversations."}
   {:path "/protected-counter" :title "Protected counter"      :group "Tier 3"
    :blurb "WASessionProtectedCounter: app login composed with cid owner cookies."}
   {:path "/seaside-todo"   :title "Seaside book ToDo"         :group "Book app"
    :blurb "The HPI tutorial app: login/register, filters, task editor, report, and notes."}
   {:path "/kasten"         :title "Kasten notes desk"         :group "Book app"
    :blurb "Port of the kasten notes UI: horizontal stack of open note columns, wiki-links, embedded children via :call-in-slot."}])

(def ^:private group-order
  ["Foundation" "Tier 1" "Tier 2" "Tier 3" "Book app"])

(defn- slot-key [path]
  (keyword "slot" (-> path
                       (str/replace #"^/" "")
                       (str/replace #"/" "-"))))

(defn- entry-for [path]
  (some #(when (= (:path %) path) %) demo-catalog))

(defn- first-paragraph [doc]
  (when (seq doc)
    (->> (str/split-lines doc)
         (map str/trim)
         (take-while seq)
         (str/join " "))))

;; ---------------------------------------------------------------------------
;; Detail child
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/example-detail
  :doc "Detail panel used by the WAExampleBrowser-style demo."

  :init (fn [{:keys [entry]}]
          {:entry entry})

  :render
  (fn [self]
    (let [{:keys [path title group blurb]} (:entry self)
          flow-id (get (s/mounts) path)
          doc     (some-> flow-id s/help first-paragraph)]
      [:article {:id    (:instance/id self)
                 :class "stube-card"
                 :style "min-height:20rem;"}
       [:div {:style "display:flex; align-items:baseline; gap:0.75rem;
                      flex-wrap:wrap;"}
        [:h2 {:style "margin-top:0;"} title]
        [:span {:style "color:#777; font-size:0.85rem;"} group]]
       [:p blurb]
       [:dl {:style "display:grid; grid-template-columns:7rem 1fr; gap:0.4rem 0.75rem;"}
        [:dt {:style "font-weight:600;"} "Path"]
        [:dd {:style "margin:0;"} [:code path]]
        [:dt {:style "font-weight:600;"} "Mounted flow"]
        [:dd {:style "margin:0;"}
         (if flow-id [:code (str flow-id)] [:em "not mounted"])]
        [:dt {:style "font-weight:600;"} "Registry"]
        [:dd {:style "margin:0;"}
         (if (and flow-id (s/registry-lookup flow-id))
           "component registered"
           "component not currently registered")]]
       (when doc
         [:details {:style "margin-top:1rem;"}
          [:summary "Component docstring"]
          [:p {:style "color:#555; line-height:1.45;"} doc]])
       [:p {:style "margin-top:1.25rem;"}
        [:a {:href  path
             :class "stube-button stube-button--primary"
             :style "display:inline-block; text-decoration:none;"}
         "Open standalone"]]])))

;; ---------------------------------------------------------------------------
;; Browser parent
;; ---------------------------------------------------------------------------

(defn- render-entry-button [self selected entry]
  (let [active? (= selected (:path entry))]
    [:button (merge {:type  "button"
                     :class (str "stube-button stube-button--block"
                                 (when active? " stube-button--primary"))
                     :style "margin-bottom:0.35rem;"}
                    (s/on self :click :as [:select (:path entry)]))
     [:strong (:title entry)]
     [:span {:style "display:block; font-size:0.78rem; opacity:0.85; margin-top:0.15rem;"}
      (:path entry)]]))

(s/defcomponent :demo/example-browser
  :doc "WAExampleBrowser port: lists mounted demos, looks up their registered components, and swaps detail children."

  :init (constantly {:selected "/paginated-list"})

  :children
  (fn [_]
    (into {}
          (map (fn [entry]
                 [(slot-key (:path entry))
                  (s/embed :demo/example-detail {:entry entry})]))
          demo-catalog))

  :render
  (fn [self]
    (let [selected (if (entry-for (:selected self))
                     (:selected self)
                     (:path (first demo-catalog)))
          mounts   (s/mounts)]
      [:section {:id    (:instance/id self)
                 :style "max-width:72rem; margin:1rem auto; padding:1rem;
                         font-family:system-ui, sans-serif; color:#222;"}
       [:header {:style "margin-bottom:1rem;"}
        [:h1 {:style "margin:0 0 0.35rem;"} "stube examples"]
        [:p {:style "color:#555; max-width:56rem;"}
         "A WAExampleBrowser-style landing page.  The menu is static catalogue "
         "data, but each detail panel reads " [:code "(s/mounts)"]
         " and " [:code "(s/help flow-id)"]
         " at render time, then swaps the selected child slot into view."]
        [:p {:style "color:#777; font-size:0.85rem;"}
         (count mounts) " root mounts currently registered."]]
       [:div {:style "display:grid; grid-template-columns:minmax(14rem, 18rem) 1fr;
                       gap:1rem; align-items:start;"}
        [:nav {:class "stube-card"
               :style "position:sticky; top:1rem;"}
         (for [group group-order
               :let [entries (filter #(= group (:group %)) demo-catalog)]
               :when (seq entries)]
           [:section {:key group :style "margin-bottom:1rem;"}
            [:h3 {:style "margin:0 0 0.4rem; font-size:0.95rem; color:#555;"}
             group]
            (for [entry entries]
              (with-meta (render-entry-button self selected entry)
                {:key (:path entry)}))])]
        (s/render-slot self (slot-key selected))]]))

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :select [(assoc self :selected payload) []]
      [self []])))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/" :demo/example-browser)
(s/mount! "/example-browser" :demo/example-browser)

(defn -main [& _args]
  (s/start! {:port 8080})
  (println "stube examples up — visit http://localhost:8080/ for the example browser")
  @(promise))

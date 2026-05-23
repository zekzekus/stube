(ns dev.zeko.stube.examples.kasten.column
  "Single note column. Edit / cancel / save / delete are column-local
  events handled here; close emits `[:answer [:close note-id]]` (which
  disposes this column — desired). Wiki-link clicks must NOT dispose
  the source column, so they use `s/on-target` to POST directly to the
  desk instance id passed in via embed args."
  (:require [dev.zeko.stube.core :as s]
            [dev.zeko.stube.examples.kasten.markdown :as md]
            [dev.zeko.stube.examples.kasten.mock :as mock]))

(defn- zoom-icon []
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 14 14"
         :width "13" :height "13"
         :fill "none" :stroke "currentColor"
         :stroke-width "1.5"
         :stroke-linecap "round"
         :aria-hidden "true"}
   [:path {:d "M5 1H1v4"}]
   [:path {:d "M9 1h4v4"}]
   [:path {:d "M5 13H1v-4"}]
   [:path {:d "M9 13h4v-4"}]])

(defn- header-action
  ([self event label]
   (header-action self event label nil))
  ([self event label modifier]
   [:button (merge {:type "button"
                    :class (cond-> "note-column__header-action"
                             modifier (str " note-column__header-action--" (name modifier)))}
                   (s/on self :click :as event))
    [:span label]]))

(defn- header
  [self note editing?]
  [:header.note-column__header
   [:div.note-column__identity
    [:h1.note-column__title {:data-full-title (:note/title note)}
     [:span.note-column__title-text (:note/title note)]
     [:span.note-column__title-tip {:aria-hidden "true"} (:note/title note)]]
    (into [:p.note-column__byline
           [:span.note-column__byline-item (:note/date note)]
           [:span.note-column__byline-sep "·"]
           [:span.note-column__byline-item (str (:note/word-count note) " words")]]
          (when-let [tag (:note/tag note)]
            [[:span.note-column__byline-sep "·"]
             [:span.note-column__byline-item.note-column__byline-tag tag]]))]
    [:div.note-column__header-actions
    (if editing?
     (list (header-action self :save "Save" :primary)
           (header-action self :cancel-edit "Cancel"))
     (header-action self :edit "Edit"))
    [:button (merge {:type "button"
                     :class "note-column__header-action note-column__header-action--danger"}
                    (s/on self :click :as :delete))
     [:span "Del"]]
    [:button {:type "button"
              :class "note-column__zoom"
              :aria-label (str "Zoom " (:note/title note))}
     (zoom-icon)]
    [:button (merge {:type "button"
                     :class "note-column__header-action note-column__close"
                     :aria-label (str "Close " (:note/title note))}
                    (s/on self :click :as :close))
     [:span "×"]]]])

(defn- folgezettel
  [self note]
  (let [back (:note/backlinks note)
        fwd (:note/forward-links note)
        parent-id (:parent-id self)
        chip (fn [linked]
               [:button.note-folgezettel__chip
                (merge {:type "button"
                        :title (:note/title linked)}
                       (s/on-target parent-id :click :as [:open (:xt/id linked)]))
                (:note/title linked)])
        panel (fn [direction label items]
                [:div {:class (str "note-folgezettel__panel "
                                   "note-folgezettel__panel--" (name direction))}
                 [:div.note-folgezettel__panel-head
                  [:span.note-folgezettel__panel-label label]
                  [:span.note-folgezettel__panel-count (count items)]]
                 (into [:div.note-folgezettel__panel-list]
                       (map chip items))])]
    (when (or (seq back) (seq fwd))
      [:section.note-folgezettel
       (when (seq back) (panel :in "↙ Cited by" back))
       (when (seq fwd) (panel :out "Cites ↗" fwd))])))

(defn- wiki-link-fn
  "Build the `(fn [raw-slug] hiccup)` that markdown.clj uses to render
  `[[slug]]` references. Resolved links POST directly to the desk's
  `:open` event so the source column stays mounted."
  [self]
  (let [parent-id (:parent-id self)]
    (fn [raw-slug]
      (if-let [note (get-in mock/catalog [:notes-by-slug raw-slug])]
        [:button.note-link.note-link--internal
         (merge {:type "button"
                 :aria-label (str "Open note " (:note/title note))}
                (s/on-target parent-id :click :as [:open (:xt/id note)]))
         raw-slug]
        [:span.note-link-cluster.note-link-cluster--unresolved
         [:span.note-link.note-link--unresolved
          {:title (str "No note found for slug " raw-slug)}
          raw-slug]]))))

(defn- edit-form
  [self note]
  [:section.note-edit
   [:div.notes-compose__eyebrow "Edit Note"]
   [:form.notes-compose__form
    (merge {:id (str "edit-form-" (:xt/id note))}
           (s/on self :submit :as :save))
    [:div.notes-compose__fields.notes-compose__fields--stacked
     [:label.notes-compose__field
      [:span.notes-compose__label "Title"]
      [:input.notes-compose__input
       (merge {:type "text"
               :name "title"
               :placeholder "Untitled note"
               :autocomplete "off"
               :value (or (:draft-title self) "")}
              (s/local-bind self :draft-title))]]
     [:div.notes-compose__field
      [:span.notes-compose__label "Markdown"]
      [:textarea.notes-compose__input
       (merge {:name "markdown"
               :rows 10
               :style "width:100%; font-family:var(--notes-mono); resize:vertical;"}
              (s/local-bind self :draft-markdown))
       (or (:draft-markdown self) "")]]]]])

(s/defcomponent :kasten/column
  :doc "Renders one open note in the desk's horizontal stack."

  :init
  (fn [{:keys [note-id stack-index parent-id]}]
    {:note-id note-id
     :parent-id parent-id
     :stack-index (or stack-index 0)
     :focused? false
     :editing? false
     :draft-title nil
     :draft-markdown nil})

  :keep #{:draft-title :draft-markdown}

  :render
  (fn [self]
    (let [note (get-in mock/catalog [:notes-by-id (:note-id self)])
          stack-index (:stack-index self)
          editing? (:editing? self)
          focused? (:focused? self)]
      [:article.note-column
       (s/root-attrs self
                     {:class (when focused? "is-focused")
                      :style (str "--stack-index:" stack-index ";"
                                  " left:" (* stack-index 44) "px;"
                                  " z-index:" (inc stack-index) ";")})
       [:div.note-column__spine
        [:span.note-column__spine-title (:note/title note)]
        [:span.note-column__spine-index (:note/tag note)]]
       [:div.note-column__panel
        (header self note editing?)
        [:div.note-column__body
         [:div.note-column__layout
          (into [:div.note-column__main]
                (if editing?
                  [(edit-form self note)]
                  (md/render-body (:note/markdown note) (wiki-link-fn self))))
          (when-let [aside (folgezettel self note)]
            [:aside.note-column__aside aside])]
         (when-not editing?
           [:p.note-column__footnote (:note/footnote note)])]]]))

  :handle
  (fn [self {:keys [event]}]
    (case event
      :edit
      (-> self
          (assoc :editing? true)
          (assoc :draft-title (get-in mock/catalog [:notes-by-id (:note-id self) :note/title]))
          (assoc :draft-markdown (get-in mock/catalog [:notes-by-id (:note-id self) :note/markdown])))

      :cancel-edit
      (assoc self :editing? false :draft-title nil :draft-markdown nil)

      :save
      ;; Mock save: just leave edit mode; real app would update the catalog.
      (assoc self :editing? false :draft-title nil :draft-markdown nil)

      :close
      [(s/answer [:close (:note-id self)])]

      :delete
      [(s/answer [:close (:note-id self)])]

      nil)))

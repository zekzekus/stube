(ns dev.zeko.stube.examples.columns
  "S-7 keyed-children demo: an open-note column ledger.

  Mirrors kasten's behaviour — adding a 5th column appends ONE child
  fragment and a tiny scroll-to-end script.  Existing columns are not
  re-rendered; their DOM state (scroll position, focus, inputs) is
  untouched."
  (:require [dev.zeko.stube.core :as s]))

(s/defcomponent :demo/column
  :doc "One column in the columns demo."

  :init   (fn [{:keys [title]}]
            {:title (or title "Untitled")
             :body  ""})

  :render (fn [self]
            [:article (s/root-attrs self
                        {:class "stube-card"
                         :style "flex:0 0 18rem; margin:0 0.5rem;
                                 max-height:24rem; overflow:auto;"})
             [:header {:style "display:flex; justify-content:space-between; gap:0.5rem;"}
              [:h3 {:style "margin:0;"} (:title self)]
              [:button (merge {:type "button"
                               :class "stube-button"
                               :style "font-size:0.8rem;"}
                              (s/on self :click :as :close))
               "×"]]
             [:textarea (merge {:rows 10 :placeholder "Notes…"
                                :style "width:100%; box-sizing:border-box;
                                        margin-top:0.5rem;"}
                               (s/bind :body))]])

  :handle (fn [self {:keys [event]}]
            (case event
              :close [(s/answer (:title self))]
              self)))

(defn- columns->pairs [titles]
  (mapv (fn [t] [t (s/embed :demo/column {:title t})]) titles))

(s/defcomponent :demo/columns
  :doc "Open-note column ledger.  The footer adds/removes columns; the
        kernel emits one fragment per change."

  :init  (fn [_] {:titles ["A" "B" "C"]
                  :next-i 4})

  :start (fn [self]
           [self [(s/set-keyed-children :slot/cols (columns->pairs (:titles self)))]])

  :render
  (fn [self]
    [:main (s/root-attrs self
             {:style "font-family:system-ui, sans-serif; padding:1rem;"})
     [:header
      [:h1 "Columns"]
      [:p {:style "color:#555;"}
       "Add or remove columns: only the affected child fragment is "
       "patched, never the whole page."]
      [:div {:class "stube-actions"}
       [:button (merge {:type "button" :class "stube-button stube-button--primary"}
                       (s/on self :click :as :add))
        "Add column"]
       [:button (merge {:type "button" :class "stube-button"}
                       (s/on self :click :as :remove-last))
        "Remove last"]]]
     ;; The horizontal scroller is the keyed-children container — adding
     ;; a column appends only its fragment, and the :scroll-to-end script
     ;; nudges the viewport without touching any sibling.
     [:div {:style "display:flex; overflow-x:auto; padding:1rem 0;"}
      (s/keyed-children self :slot/cols)]])

  :handle
  (fn [self {:keys [event]}]
    (case event
      :add
      (let [n        (:next-i self)
            new-title (str (char (+ 64 n)))
            titles   (conj (:titles self) new-title)
            self'    (-> self
                         (assoc :titles titles)
                         (update :next-i inc))]
        [self' [(s/set-keyed-children :slot/cols (columns->pairs titles))
                (s/execute-script
                  ;; scroll the parent's flex row to the end so the
                  ;; newly-appended column comes into view
                  (str "document.querySelector('#" (:instance/id self)
                       " > div:last-child').scrollLeft = 1e9"))]])

      :remove-last
      (let [titles (vec (butlast (:titles self)))
            self'  (assoc self :titles titles)]
        [self' [(s/set-keyed-children :slot/cols (columns->pairs titles))]])

      self)))

(s/mount! "/columns" :demo/columns)

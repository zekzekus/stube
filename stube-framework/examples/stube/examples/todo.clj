(ns stube.examples.todo
  "Todo list with slot-local in-place editing — Seaside's `WATodo` /
  `WATodoItem` / `WATodoItemEditor` using stube's `:call-in-slot`.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/todo>.

  ──────────────────────────────────────────────────────────────────────
  Why this example matters
  ──────────────────────────────────────────────────────────────────────

  The Seaside version splits responsibility three ways:

      WATodo            owns the list, renders rows
      WATodoItem        renders one row; clicking the label fires
                          `self call: (WATodoItemEditor on: self)`
      WATodoItemEditor  renders a text field; on submit, `answer:`s
                          the new label back to the WATodoItem,
                          which then `replace:`s itself in place

  That `call:`/`answer:` is **scoped to the row's slot**: only the row
  flips into edit mode, the rest of the list stays put.  The stube port
  uses `[:call-in-slot :slot/editor ... :resume :on-edit]` for the same
  shape: the parent chooses which row hosts the temporary editor, the
  editor answers, and the parent restores the display row.

  ──────────────────────────────────────────────────────────────────────
  State shape
  ──────────────────────────────────────────────────────────────────────

      {:next-id    7
       :draft      \"\"            ; bound to the new-item input
       :editing-id nil            ; iff non-nil, that row renders editor slot
       :items      [{:id 1 :text \"buy milk\"  :done? false}
                    {:id 2 :text \"write demo\" :done? true}]}"
  (:require [stube.core :as s]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Helpers (pure)
;; ---------------------------------------------------------------------------

(defn- update-item [self id f & args]
  (update self :items
          (fn [xs] (mapv #(if (= (:id %) id) (apply f % args) %) xs))))

(defn- delete-item [self id]
  (update self :items #(into [] (remove (fn [x] (= (:id x) id))) %)))

(defn- add-item [self text]
  (let [t (str/trim (str text))]
    (if (str/blank? t)
      self
      (-> self
          (update :items conj {:id    (:next-id self)
                               :text  t
                               :done? false})
          (update :next-id inc)
          (assoc :draft "")))))

;; ---------------------------------------------------------------------------
;; Render helpers
;; ---------------------------------------------------------------------------

(defn- render-row [self {:keys [id text done?]}]
  (let [editing? (= id (:editing-id self))]
    [:li {:key id
          :style (str "display:flex; align-items:center; gap:0.5rem; "
                      "padding:0.4rem 0.5rem; border-bottom:1px solid #eee;"
                      (when done? "background:#f4f4f4;"))}
     [:input (merge {:type "checkbox"
                     :checked (boolean done?)}
                    (s/on self :change :as [:toggle id]))]
     (if editing?
       ;; The temporary editor is a real child component overlaid into
       ;; this row.  When it answers, `:on-edit` below restores the row.
       [:div {:style "flex:1;"}
        (s/render-slot self :slot/editor)]
       ;; Display row: clicking the label opens the editor.
       [:span (merge {:style (str "flex:1; cursor:text; "
                                  (when done? "text-decoration:line-through;
                                                color:#888;"))}
                     (s/on self :click :as [:edit id]))
        text])
     [:button (merge {:type "button"
                      :style "padding:0.2rem 0.5rem; color:#a33;
                              border:1px solid #ccc; background:#fee;
                              border-radius:0.2rem;"}
                     (s/on self :click :as [:delete id]))
      "✕"]]))

;; ---------------------------------------------------------------------------
;; The component
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/todo-editor
  :init (fn [{:keys [id text]}]
          {:id id :text text})

  :keep #{:text}

  :render
  (fn [self]
    [:form (merge {:id    (:instance/id self)
                   :style "display:flex; gap:0.25rem;"}
                  (s/on self :submit))
     [:input (merge {:name      "text"
                     :value     (:text self)
                     :autofocus true
                     :style     "flex:1; padding:0.2rem 0.4rem; font-size:1rem;"}
                    (s/local-bind self :text))]
     [:button {:type "submit" :style "padding:0.2rem 0.6rem;"} "Save"]
     [:button (merge {:type "button" :style "padding:0.2rem 0.6rem;"}
                     (s/on self :click :as :cancel))
      "Cancel"]])

  :handle
  (fn [self {:keys [event]}]
    [self [[:answer (if (= event :cancel)
                      s/cancel
                      {:id   (:id self)
                       :text (:text self)})]]]))

(s/defcomponent :demo/todo
  :init (constantly
          {:next-id    3
           :draft      ""
           :editing-id nil
           :items      [{:id 1 :text "click a label to edit it" :done? false}
                        {:id 2 :text "tick the box when done"    :done? false}]})

  ;; `:draft` is read on parent events; the temporary editor scopes its
  ;; own `:text` signal with `s/local-bind`.
  :keep #{:draft}

  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :style "max-width:32rem; margin:1rem; padding:1rem;
                       border:1px solid #ccc; border-radius:0.4rem;
                       font-family:system-ui, sans-serif;"}
     [:h2 {:style "margin-top:0;"} "Todo"]
     [:form (merge {:style "display:flex; gap:0.4rem; margin-bottom:0.5rem;"}
                   (s/on self :submit :as :add))
      [:input (merge {:name        "draft"
                      :placeholder "What needs doing?"
                      :value       (:draft self)
                      :autofocus   true
                      :style       "flex:1; padding:0.4rem; font-size:1rem;"}
                     (s/local-bind self :draft))]
      [:button {:type "submit"
                :style "padding:0.4rem 1rem; background:#36c; color:white;
                        border:1px solid #25b; border-radius:0.25rem;"}
       "Add"]]
     [:ul {:style "list-style:none; padding:0; margin:0;
                    border-top:1px solid #eee;"}
      (for [item (:items self)] (render-row self item))]
     [:p {:style "color:#666; font-size:0.85rem; margin-top:0.75rem;"}
      (let [done    (count (filter :done? (:items self)))
            pending (- (count (:items self)) done)]
        (str pending " open · " done " done"))]])

  :handle
  (fn [self {:keys [event payload]}]
    (cond
      (= event :add)
      [(add-item self (:draft self)) []]

      (= event :edit)
      (if-let [item (some #(when (= (:id %) payload) %) (:items self))]
        [(assoc self :editing-id payload)
         [[:call-in-slot :slot/editor
           (s/embed :demo/todo-editor {:id payload :text (:text item)})
           :resume :on-edit]]]
        [self []])

      (= event :delete)
      [(-> self (delete-item payload)
                (cond-> (= (:editing-id self) payload)
                  (assoc :editing-id nil)))
       []]

      (= event :toggle)
      [(update-item self payload update :done? not)
       []]

      :else [self []]))

  :on-edit
  (fn [self answer]
    (if (= answer s/cancel)
      [(assoc self :editing-id nil) []]
      (let [{:keys [id text]} answer
            t (str/trim (str text))]
        [(-> self
             (cond-> (not (str/blank? t))
               (update-item id assoc :text t))
             (assoc :editing-id nil))
         []]))))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/todo" :demo/todo)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

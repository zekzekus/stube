(ns stube.examples.todo
  "Todo list with in-place editing — Seaside's `WATodo` /
  `WATodoItem` / `WATodoItemEditor` collapsed into a single component.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/todo>.

  ──────────────────────────────────────────────────────────────────────
  Why this example matters — and what it reveals
  ──────────────────────────────────────────────────────────────────────

  The Seaside version splits responsibility three ways:

      WATodo            owns the list, renders rows
      WATodoItem        renders one row; clicking the label fires
                          `self call: (WATodoItemEditor on: self)`
      WATodoItemEditor  renders a text field; on submit, `answer:`s
                          the new label back to the WATodoItem,
                          which then `replace:`s itself in place

  That `call:`/`answer:` is **scoped to the row's slot**: only the row
  flips into edit mode, the rest of the list stays put.  In stube,
  `:call` and `:answer` operate on the **conversation stack** — the
  child takes over the page (the kernel's first-render path patches
  `#root` `inner`).  A faithful port would hide the entire list while
  one item was being edited.

  We have two ways forward:

  * **Add a new effect** — `[:call-in-slot slot embed-spec :resume k]`
    that swaps a single child slot rather than the top frame.  This
    is filed in `seaside-examples.md` Tier 3 as the highest-leverage
    next-step driver this catalogue surfaces.

  * **Inline the editor as state on the parent** — keep an
    `:editing-id` field; render the editing row as an `<input>`,
    every other row as a label.

  This file ships the second option.  It exercises everything stube
  already has (per-instance signals, kept signals, structured event
  routes via `:click :as :foo-<id>`) without inventing new
  primitives.  The top-of-file docstring on the `:demo/todo`
  component spells out the workaround so the next reader can find it.

  ──────────────────────────────────────────────────────────────────────
  State shape
  ──────────────────────────────────────────────────────────────────────

      {:next-id    7
       :draft      \"\"            ; bound to the new-item input
       :editing-id nil            ; iff non-nil, that row renders as <input>
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

;; Per-row signal name so the `<input>` for one editing row doesn't
;; collide with the `:draft` signal or with an editor for a different
;; row.
(defn- edit-signal [self id]
  (keyword (str "edit-" (:instance/id self) "-" id)))

;; Convenience: encode "this event refers to item N" in the route
;; name.  Same workaround as in `dialogs.clj` and `calendar.clj`,
;; flagged in the catalogue.
(defn- evt [tag id] (keyword (str (name tag) "-" id)))

(defn- parse-evt [event prefix]
  (let [n (name event)]
    (when (str/starts-with? n prefix)
      (parse-long (subs n (count prefix))))))

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
                    (s/on self :change :as (evt :toggle id)))]
     (if editing?
       ;; In-place edit row: a tiny form.  Submit answers the new
       ;; label, Esc/click-away cancels (we route :blur to commit too,
       ;; matching the WATodoItem feel).
       [:form (merge {:style "flex:1; display:flex; gap:0.25rem;"}
                     (s/on self :submit :as (evt :commit id)))
        [:input (merge {:name      "text"
                        :value     text
                        :autofocus true
                        :style     "flex:1; padding:0.2rem 0.4rem; font-size:1rem;"}
                       (s/bind (edit-signal self id)))]
        [:button {:type "submit" :style "padding:0.2rem 0.6rem;"} "Save"]
        [:button (merge {:type "button" :style "padding:0.2rem 0.6rem;"}
                        (s/on self :click :as :cancel-edit))
         "Cancel"]]
       ;; Display row: clicking the label opens the editor.
       [:span (merge {:style (str "flex:1; cursor:text; "
                                  (when done? "text-decoration:line-through;
                                                color:#888;"))}
                     (s/on self :click :as (evt :edit id)))
        text])
     [:button (merge {:type "button"
                      :style "padding:0.2rem 0.5rem; color:#a33;
                              border:1px solid #ccc; background:#fee;
                              border-radius:0.2rem;"}
                     (s/on self :click :as (evt :delete id)))
      "✕"]]))

;; ---------------------------------------------------------------------------
;; The component
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/todo
  :init (constantly
          {:next-id    3
           :draft      ""
           :editing-id nil
           :items      [{:id 1 :text "click a label to edit it" :done? false}
                        {:id 2 :text "tick the box when done"    :done? false}]})

  ;; `:draft` is read on every event; per-row edit signals are read
  ;; only when their row submits, so we don't list them here — they
  ;; are pulled by name out of the `signals` map in `:handle`.
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
                     (s/bind :draft))]
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
  (fn [self {:keys [event signals]}]
    (cond
      (= event :add)
      [(add-item self (:draft self)) []]

      (= event :cancel-edit)
      [(assoc self :editing-id nil) []]

      (parse-evt event "edit-")
      [(assoc self :editing-id (parse-evt event "edit-")) []]

      (parse-evt event "delete-")
      [(-> self (delete-item (parse-evt event "delete-"))
                (cond-> (= (:editing-id self)
                           (parse-evt event "delete-"))
                  (assoc :editing-id nil)))
       []]

      (parse-evt event "toggle-")
      [(update-item self (parse-evt event "toggle-")
                    update :done? not)
       []]

      (parse-evt event "commit-")
      (let [id  (parse-evt event "commit-")
            v   (get signals (edit-signal self id))
            t   (str/trim (str v))]
        [(-> self
             (cond-> (not (str/blank? t))
               (update-item id assoc :text t))
             (assoc :editing-id nil))
         []])

      :else [self []])))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/todo" :demo/todo)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

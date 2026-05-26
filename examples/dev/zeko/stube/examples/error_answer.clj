(ns dev.zeko.stube.examples.error-answer
  "S-14: a child that fails its save flow surfaces the error to the
  parent through `:on-error-saved` instead of encoding `[:error ex]`
  inside the answered value.

  The child is a tiny edit form; the parent is a column that owns the
  banner area.  The child's `:save` handler simulates a 409 conflict
  half the time.  On success the parent clears the banner and closes
  the form; on failure the parent shows the message and keeps the
  form mounted (the child's draft state survives because the column
  re-calls it with the same args)."
  (:require [dev.zeko.stube.core :as s]))

(defonce ^:private !save-attempts (atom 0))

(defn- maybe-conflict! []
  ;; Deterministic toggle in dev — every other save fails so the user
  ;; can see both branches without restarting.
  (when (odd? (swap! !save-attempts inc))
    (throw (ex-info "Slug already taken (mock 409)" {:status 409}))))

(s/defcomponent :err-answer/edit-form
  :init   (fn [{:keys [draft]}] {:draft (or draft "")})
  :keep   #{:draft}

  :render
  (fn [self]
    [:form (s/root-attrs self {:style "padding:1rem; border:1px dashed #aaa;"}
                        (s/on self :submit :as :save))
     [:label {:style "display:block;"} "Title:"]
     [:input (merge {:type "text" :name "draft"} (s/bind :draft))]
     [:div {:style "margin-top:0.5rem;"}
      [:button {:type "submit"} "Save"]
      " "
      [:button (merge {:type "button"} (s/on self :click :as :cancel))
       "Cancel"]]])

  :handle
  (fn [self {:keys [event]}]
    (case event
      :save   (try
                (maybe-conflict!)
                [(s/answer {:saved-title (:draft self)})]
                (catch Exception ex
                  ;; Tuck the in-flight draft into ex-data so the parent
                  ;; can re-mount the form with the user's typing intact.
                  ;; (answer-error pops this frame; the column owns
                  ;; restoring it.)
                  [(s/answer-error
                     (ex-info (ex-message ex)
                              (assoc (or (ex-data ex) {})
                                     :draft (:draft self))
                              ex))]))
      :cancel [(s/answer :cancelled)]
      self)))

(s/defcomponent :err-answer/column
  :init (fn [_] {:banner nil :open? false :saved nil})

  :render
  (fn [self]
    [:section (s/root-attrs self {:class "stube-card"
                                  :style "max-width:32rem; margin:2rem auto;"})
     [:h2 {:style "margin-top:0;"} "answer-error demo"]
     (when (:banner self)
       [:div {:class "stube-error" :role "alert"
              :style "background:#fee; padding:0.5rem; margin-bottom:0.75rem;"}
        (:banner self)])
     (when (:saved self)
       [:p {:style "color:#272;"} "Saved: " [:code (:saved self)]])
     (if (:open? self)
       [:div
        [:p {:style "color:#555; font-size:0.9rem;"}
         "Submit to test — every other save throws a mock 409."]
        (s/render-slot self :slot/form)]
       [:button (merge {:type "button"} (s/on self :click :as :open))
        "Open edit form"])])

  :handle
  (fn [self {:keys [event]}]
    (case event
      :open
      [(assoc self :open? true :banner nil)
       [(s/call-in-slot :slot/form :err-answer/edit-form
                        {:draft (or (:saved self) "")} :on-saved)]]
      self))

  :on-saved
  (fn [self {:keys [saved-title]}]
    (assoc self :open? false :banner nil :saved saved-title))

  :on-error-saved
  (fn [self ex]
    ;; The form has already been popped by answer-error; restore it
    ;; with the user's draft so they can fix the conflict and retry.
    [(assoc self :open? true
                 :banner (str "Save failed: " (ex-message ex)))
     [(s/call-in-slot :slot/form :err-answer/edit-form
                      {:draft (:draft (ex-data ex))} :on-saved)]]))

(s/mount! "/error-answer" :err-answer/column)

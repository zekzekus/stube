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

(defn- maybe-conflict! []
  ;; deterministic toggle in dev — every other save fails
  (let [!n (or (resolve 'error-answer/!n)
               (intern *ns* '!n (atom 0)))]
    (when (odd? (swap! @!n inc))
      (throw (ex-info "Slug already taken (mock 409)" {:status 409})))))

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
                  [(s/answer-error ex)]))
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
       [:p "Form is open below; submit to test."]
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
    ;; Keep :open? true so the form is re-presented for retry.
    (assoc self
           :open? true
           :banner (str "Save failed: " (ex-message ex)))))

(s/mount! "/error-answer" :err-answer/column)

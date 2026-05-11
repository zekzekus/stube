(ns dev.zeko.stube.ui
  "Small canonical UI components used by the convenience helpers in
  [[dev.zeko.stube.core]].

  This namespace deliberately does not require `dev.zeko.stube.core`: it registers
  component maps directly so `dev.zeko.stube.core` can require us without a cycle."
  (:require [dev.zeko.stube.registry :as registry]
            [dev.zeko.stube.render :as render]))

(def cancel
  "Sentinel returned by cancellable stock UI components."
  ::cancel)

(def confirm-component
  {:component/id :ui/confirm
   :component/init
   (fn [{:keys [question]}]
     {:question (or question "Are you sure?")})

   :component/render
   (fn [self]
     [:section {:id (:instance/id self) :class "stube-card stube-modal"}
      [:p {:class "stube-card__body"} (:question self)]
      [:div {:class "stube-actions stube-actions--end"}
       [:button (merge {:type "button" :class "stube-button"}
                       (render/on self :click :as :no))
        "No"]
       [:button (merge {:type "button" :class "stube-button stube-button--primary"}
                       (render/on self :click :as :yes))
        "Yes"]]])

   :component/handle
   (fn [self {:keys [event]}]
     (case event
       :yes [self [[:answer true]]]
       :no  [self [[:answer false]]]
       [self []]))})

(def prompt-component
  {:component/id :ui/prompt
   :component/init
   (fn [{:keys [label default]}]
     {:label   (or label "Please enter a value:")
      :default (or default "")})

   :component/keep #{:value}

   :component/render
   (fn [self]
     [:form (merge {:id (:instance/id self) :class "stube-card stube-modal"}
                   (render/on self :submit))
      [:label {:class "stube-label"} (:label self)]
      [:input (merge {:class     "stube-input"
                      :name      "value"
                      :value     (:default self)
                      :autofocus true}
                     (render/local-bind self :value))]
      [:div {:class "stube-actions stube-actions--end"}
       [:button (merge {:type "button" :class "stube-button"}
                       (render/on self :click :as :cancel))
        "Cancel"]
       [:button {:type "submit" :class "stube-button stube-button--primary"}
        "OK"]]])

   :component/handle
   (fn [self {:keys [event]}]
     (case event
       :submit [self [[:answer (get self :value (:default self))]]]
       :cancel [self [[:answer cancel]]]
       [self []]))})

(def choose-component
  {:component/id :ui/choose
   :component/init
   (fn [{:keys [caption options]}]
     {:caption (or caption "Pick one:")
      :options (vec options)})

   :component/render
   (fn [self]
     [:section {:id (:instance/id self) :class "stube-card stube-modal"}
      [:p {:class "stube-card__body"} (:caption self)]
      [:div {:class "stube-stack"}
       (for [[i opt] (map-indexed vector (:options self))]
         [:button (merge {:type  "button"
                          :key   i
                          :class "stube-button stube-button--block"}
                         (render/on self :click :as [:pick i]))
          (str opt)])]
      [:div {:class "stube-actions stube-actions--end"}
       [:button (merge {:type "button" :class "stube-button"}
                       (render/on self :click :as :cancel))
        "Cancel"]]])

   :component/handle
   (fn [self {:keys [event payload]}]
     (case event
       :pick   [self [[:answer (get-in self [:options payload])]]]
       :cancel [self [[:answer cancel]]]
       [self []]))})

(def info-component
  {:component/id :ui/info
   :component/init
   (fn [{:keys [text]}]
     {:text text})

   :component/render
   (fn [self]
     [:section {:id (:instance/id self) :class "stube-card stube-modal"}
      [:p {:class "stube-card__body"} (:text self)]
      [:div {:class "stube-actions stube-actions--end"}
       [:button (merge {:type "button" :class "stube-button stube-button--primary"}
                       (render/on self :click :as :ok))
        "OK"]]])

   :component/handle
   (fn [self _]
     [self [[:answer :ok]]])})

(def stock-components
  [confirm-component prompt-component choose-component info-component])

(defn register!
  "Register or refresh the stock UI components.  Safe to call repeatedly;
  useful after tests or REPL work clear the component registry."
  []
  (doseq [cdef stock-components]
    (registry/register! cdef))
  nil)

(register!)

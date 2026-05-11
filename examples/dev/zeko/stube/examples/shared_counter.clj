(ns dev.zeko.stube.examples.shared-counter
  "Shared counter/report — Seaside's `CTCounter` and `CTReport`.

  Run from the project root:

      clojure -M:examples

  Then open <http://localhost:8080/shared-counter> in two browsers.

  The counter value lives in an example-level atom to model shared
  application state.  Components subscribe to a topic; button clicks
  update the atom and publish the new value to every live conversation."
  (:require [dev.zeko.stube.core :as s])
  (:import (java.time Instant)))

(defonce ^:private !value (atom 0))
(def ^:private topic :demo.shared-counter/value)

(defn- subscription-effects []
  [(s/subscribe topic :shared-update)])

(defn- publish-value! [value source]
  (s/publish! topic {:value  value
                     :source source
                     :at     (str (Instant/now))}))

(s/defcomponent :demo/shared-counter-widget
  :doc "CTCounter port: local component state follows a shared atom through topic updates."

  :init (constantly {:value @!value :last-source "initial"})
  :start  (fn [self] [self (subscription-effects)])
  :wakeup (fn [self] [(assoc self :value @!value) (subscription-effects)])
  :stop   (fn [self] [self [(s/unsubscribe topic)]])

  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :class "stube-card"
               :style "min-width:18rem;"}
     [:h3 {:style "margin-top:0;"} "Shared counter"]
     [:div {:style "font-size:2rem; font-variant-numeric:tabular-nums;"}
      (:value self)]
     [:p {:style "color:#666;"}
      "Last update: " (:last-source self)]
     [:div {:class "stube-actions"}
      [:button (merge {:type "button" :class "stube-button"}
                      (s/on self :click :as :dec))
       "−"]
      [:button (merge {:type "button" :class "stube-button stube-button--primary"}
                      (s/on self :click :as :inc))
       "+"]]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :inc (let [v (swap! !value inc)]
             [(assoc self :value v :last-source "this tab")
              [[:io #(publish-value! v (:instance/id self))]]])
      :dec (let [v (swap! !value dec)]
             [(assoc self :value v :last-source "this tab")
              [[:io #(publish-value! v (:instance/id self))]]])
      :shared-update
      [(assoc self
              :value (:value payload)
              :last-source (if (= (:source payload) (:instance/id self))
                             "this tab"
                             "another tab"))
       []]
      [self []])))

(s/defcomponent :demo/shared-report
  :doc "CTReport port: subscribes to the shared counter topic and keeps a short event log."

  :init (constantly {:events []})
  :start  (fn [self] [self (subscription-effects)])
  :wakeup (fn [self] [self (subscription-effects)])
  :stop   (fn [self] [self [(s/unsubscribe topic)]])

  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :class "stube-card"
               :style "min-width:24rem;"}
     [:h3 {:style "margin-top:0;"} "Report"]
     (if (seq (:events self))
       [:ol {:style "padding-left:1.25rem; margin-bottom:0;"}
        (for [{:keys [value source at]} (:events self)]
          [:li {:key (str at source value)}
           [:code value] " from " [:code source]
           [:br]
           [:small {:style "color:#777;"} at]])]
       [:p {:style "color:#666;"} "No broadcasts seen yet."])])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :shared-update
      [(update self :events #(->> (cons payload %)
                                  (take 8)
                                  vec))
       []]
      [self []])))

(s/defcomponent :demo/shared-counter
  :doc "Tier-3 shared-state demo using per-conversation topic subscriptions."

  :children {:slot/counter (s/embed :demo/shared-counter-widget)
             :slot/report  (s/embed :demo/shared-report)}

  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :style "padding:1rem; font-family:system-ui, sans-serif;"}
     [:h2 "Shared counter"]
     [:p {:style "max-width:48rem; color:#555;"}
      "Open the standalone " [:code "/shared-counter"] " URL twice.  Each "
      "visit gets its own conversation id; topic delivery is cid/iid-scoped "
      "and re-renders every subscribed component over SSE."]
     [:div {:style "display:flex; gap:1rem; flex-wrap:wrap;"}
      (s/render-slot self :slot/counter)
      (s/render-slot self :slot/report)]]))

(s/mount! "/shared-counter" :demo/shared-counter)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

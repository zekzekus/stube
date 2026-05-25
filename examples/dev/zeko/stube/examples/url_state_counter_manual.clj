(ns dev.zeko.stube.examples.url-state-counter-manual
  "Hand-rolled URL-sync counter, kept as a sibling of `url_state_counter`
  so readers can compare the ceremony.

  This component emits `(s/history :push …)` from every state-changing
  handler.  It does the same thing the `:url` form does declaratively,
  but every new handler has to remember to restate the URL.  For
  multi-handler apps (kasten's desk has eight) this scales poorly.

  Not wired into the example browser — read-only reference."
  (:require [dev.zeko.stube.core :as s]))

(defn- counter-url [n]
  (str "/url-counter-manual?n=" n))

(s/defcomponent :demo/url-counter-manual
  :doc "Hand-rolled equivalent of `:demo/url-counter` for comparison."

  :init
  (fn [{:keys [n]}]
    {:n (or (some-> n str parse-long) 0)})

  :render
  (fn [self]
    [:section (s/root-attrs self)
     [:h2 "URL-state counter (manual)"]
     [:div (str (:n self))]
     [:button (merge {:type "button"} (s/on self :click :as :dec)) "−"]
     [:button (merge {:type "button"} (s/on self :click :as :inc)) "+"]])

  :handle
  (fn [self {:keys [event]}]
    (let [n' (case event
               :inc (inc (:n self))
               :dec (dec (:n self))
               (:n self))]
      [(assoc self :n n')
       [(s/history :push (counter-url n'))]])))

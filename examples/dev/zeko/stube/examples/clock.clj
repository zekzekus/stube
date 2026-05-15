(ns dev.zeko.stube.examples.clock
  "Live clock / turbo counter — Seaside's `WAClock` and `WATurboCounter`.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/clock>.

  Both widgets use the Tier-3 `s/after` effect.  The effect is scoped to
  the current cid/iid; if the conversation ends before the timer fires,
  the scheduled event is dropped."
  (:require [dev.zeko.stube.core :as s])
  (:import (java.time LocalTime)
           (java.time.format DateTimeFormatter)))

(def ^:private clock-format
  (DateTimeFormatter/ofPattern "HH:mm:ss"))

(defn- now-string []
  (.format (LocalTime/now) clock-format))

(defn- schedule-clock [self]
  (s/after 1000 [:tick (:generation self)]))

(defn- schedule-turbo [self]
  (s/after 250 [:tick (:generation self)]))

(defn- restart [self schedule]
  (let [self' (update self :generation (fnil inc 0))]
    [self' [(schedule self')]]))

(s/defcomponent :demo/live-clock
  :doc "WAClock port: a server-side timer dispatches :tick back into this instance."

  :init (constantly {:now        (now-string)
                     :ticks      0
                     :running?   true
                     :generation 0})

  :start  (fn [self] (restart self schedule-clock))
  :wakeup (fn [self] (when (:running? self) (restart self schedule-clock)))

  :render
  (fn [self]
    [:section (s/root-attrs self {:class "stube-card"
                                  :style "min-width:18rem;"})
     [:h3 {:style "margin-top:0;"} "Live clock"]
     [:div {:style "font-size:2rem; font-variant-numeric:tabular-nums;"}
      (:now self)]
     [:p {:style "color:#666;"}
      (:ticks self) " ticks delivered by " [:code "s/after"] "."]
     [:button (merge {:type  "button"
                      :class "stube-button"}
                     (s/on self :click :as :toggle))
      (if (:running? self) "Pause" "Resume")]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :toggle
      (if (:running? self)
        (assoc self :running? false)
        (restart (assoc self :running? true) schedule-clock))

      :tick
      (when (and (:running? self) (= payload (:generation self)))
        [(-> self
             (assoc :now (now-string))
             (update :ticks inc))
         [(schedule-clock self)]])

      nil)))

(s/defcomponent :demo/turbo-counter
  :doc "WATurboCounter-style auto-incrementing counter driven by s/after."

  :init (constantly {:n 0 :running? true :generation 0})

  :start  (fn [self] (restart self schedule-turbo))
  :wakeup (fn [self] (when (:running? self) (restart self schedule-turbo)))

  :render
  (fn [self]
    [:section (s/root-attrs self {:class "stube-card"
                                  :style "min-width:18rem;"})
     [:h3 {:style "margin-top:0;"} "Turbo counter"]
     [:div {:style "font-size:2rem; font-variant-numeric:tabular-nums;"}
      (:n self)]
     [:p {:style "color:#666;"}
      "A 250ms timer keeps posting " [:code ":tick"] " while running."]
     [:div {:class "stube-actions"}
      [:button (merge {:type "button" :class "stube-button"}
                      (s/on self :click :as :toggle))
       (if (:running? self) "Stop" "Start")]
      [:button (merge {:type "button" :class "stube-button"}
                      (s/on self :click :as :reset))
       "Reset"]]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :toggle
      (if (:running? self)
        (assoc self :running? false)
        (restart (assoc self :running? true) schedule-turbo))

      :reset
      (assoc self :n 0)

      :tick
      (when (and (:running? self) (= payload (:generation self)))
        [(update self :n inc) [(schedule-turbo self)]])

      nil)))

(s/defcomponent :demo/clock
  :doc "Tier-3 timer demo containing WAClock and WATurboCounter ports."

  :children {:slot/clock (s/embed :demo/live-clock)
             :slot/turbo (s/embed :demo/turbo-counter)}

  :render
  (fn [self]
    [:section (s/root-attrs self {:style "padding:1rem; font-family:system-ui, sans-serif;"})
     [:h2 "Timers"]
     [:p {:style "max-width:46rem; color:#555;"}
      "Both widgets are ordinary components.  Their " [:code ":start"]
      " hook emits " [:code "(s/after ms event)"] ", and every tick either "
      "schedules the next one or stops if the component is paused."]
     [:div {:style "display:flex; gap:1rem; flex-wrap:wrap;"}
      (s/render-slot self :slot/clock)
      (s/render-slot self :slot/turbo)]]))

(s/mount! "/clock" :demo/clock)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

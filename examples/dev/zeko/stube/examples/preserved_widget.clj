(ns dev.zeko.stube.examples.preserved-widget
  "A tiny third-party-widget integration demo.

  The canvas is drawn by browser-side JavaScript once.  Clicking the
  button re-renders the parent component on the server; the host div's
  attributes update, but the canvas node and its drawing state remain
  the same live DOM objects because the host is marked with
  `s/preserve`."
  (:require [dev.zeko.stube.core :as s]))

(def ^:private canvas-mount-js
  (str "(() => {"
       " const canvas = el.querySelector('canvas');"
       " const ctx = canvas.getContext('2d');"
       " ctx.fillStyle = '#f8fafc';"
       " ctx.fillRect(0, 0, canvas.width, canvas.height);"
       " ctx.strokeStyle = '#2563eb';"
       " ctx.lineWidth = 4;"
       " ctx.beginPath();"
       " ctx.moveTo(24, 110);"
       " ctx.bezierCurveTo(90, 20, 190, 150, 296, 44);"
       " ctx.stroke();"
       " ctx.fillStyle = '#dc2626';"
       " for (let i = 0; i < 16; i += 1) {"
       "   ctx.beginPath();"
       "   ctx.arc(24 + Math.random() * 272,"
       "           24 + Math.random() * 112,"
       "           3 + Math.random() * 5, 0, Math.PI * 2);"
       "   ctx.fill();"
       " }"
       " el.dataset.widgetMounted = String(Number(el.dataset.widgetMounted || '0') + 1);"
       "})()"))

(s/defcomponent :demo/preserved-widget
  :doc "Demonstrates `s/preserve` and `s/on-mount` with a canvas whose drawing state survives parent re-renders."

  :init (constantly {:renders 0})

  :render
  (fn [self]
    [:section (s/root-attrs self {:class "stube-card"
                                  :style "max-width:42rem; margin:1rem auto;
                                          font-family:system-ui, sans-serif;"})
     [:h1 {:style "margin-top:0;"} "Preserved widget"]
     [:p "The canvas below is initialized by browser-side JavaScript, not by stube. "
      "Re-render the parent: the label and host attributes change, while the "
      "drawn canvas stays intact."]
     [:p [:strong "Parent renders: "] (:renders self)]
     [:button (merge {:type  "button"
                      :class "stube-button stube-button--primary"}
                     (s/on self :click :as :rerender))
      "Re-render parent"]
     [:div (merge {:class "preserved-canvas-host"
                   :style "margin-top:1rem; padding:0.75rem; border:1px solid #cbd5e1;
                           border-radius:0.75rem; background:white;"
                   :data-parent-renders (:renders self)}
                  (s/preserve self :canvas)
                  (s/on-mount self :canvas canvas-mount-js))
      [:canvas {:width 320
                :height 160
                :style "display:block; max-width:100%; border:1px solid #94a3b8;
                        border-radius:0.5rem;"}]
      [:p {:style "margin-bottom:0; color:#475569;"}
       "This child subtree is opaque after mount; the parent render count is "
       "carried on the host's " [:code "data-parent-renders"] " attribute."]]])

  :handle
  (fn [self {:keys [event]}]
    (case event
      :rerender (update self :renders inc)
      self)))

(s/mount! "/preserved-widget" :demo/preserved-widget)

(ns dev.zeko.stube.examples.error-frame
  "Demonstrates S-5: a deliberately-thrown :handle becomes an in-page
  banner without dropping the SSE stream.

  After clicking Boom, the component's frame (this section, including
  the +1 button) is replaced in place by the stock `.stube-error`
  banner.  The SSE stream stays open and the conversation state
  survives — on a page that hosts multiple components, the others
  continue to work; here there is nothing else on the page, so reload
  to start a fresh frame."
  (:require [dev.zeko.stube.core :as s]))

(s/defcomponent :demo/error-frame
  :init   (fn [_] {:n 0})
  :render (fn [self]
            [:section (s/root-attrs self {:class "stube-card"
                                          :style "max-width:30rem; margin:2rem auto;"})
             [:h2 {:style "margin-top:0;"} "Error frame demo"]
             [:p "Counter: " [:strong (:n self)]]
             [:div {:class "stube-actions"}
              [:button (merge {:class "stube-button stube-button--primary"}
                              (s/on self :click :as :inc))
               "+1"]
              [:button (merge {:class "stube-button"}
                              (s/on self :click :as :boom))
               "Boom"]]
             [:p {:style "color:#555; font-size:0.9rem; margin-bottom:0;"}
              "Click Boom: the handler throws, the page shows an in-place "
              "error banner, and the SSE stream stays open.  Reload or "
              "re-mount to start a fresh frame."]])
  :handle (fn [self {:keys [event]}]
            (case event
              :inc  (update self :n inc)
              :boom (throw (ex-info "demonstration of an in-page error banner"
                                    {:demo true})))))

(s/mount! "/error-frame" :demo/error-frame)

(ns dev.zeko.stube.examples.embedded-ring
  "Plain Ring host embedding stube under `/widget`.

  Run with `(dev.zeko.stube.examples.embedded-ring/start!)` from a REPL.
  The host owns `/healthz` and `/api/foo`; stube owns only the generated
  `/widget/...` SSE/event/upload/assets endpoints."
  (:require [dev.onionpancakes.chassis.core :as chassis]
            [org.httpkit.server             :as http-kit]
            [reitit.ring                    :as ring]
            [dev.zeko.stube.adapter.ring    :as stube-ring]
            [dev.zeko.stube.core            :as s]
            [dev.zeko.stube.kernel          :as stube]
            [dev.zeko.stube.shell           :as shell]))

(s/defcomponent :embedded/counter
  :init   (fn [_] {:n 0})
  :render (fn [self]
            [:section (s/root-attrs self {:class "stube-card"})
             [:h2 "Embedded stube widget"]
             [:p "Host context says: " (:host-name (s/context self))]
             [:button (s/on self :click :as :dec) "−"]
             [:strong {:style "padding: 0 0.75rem"} (:n self)]
             [:button (s/on self :click :as :inc) "+"]])
  :handle (fn [self {:keys [event]}]
            (case event
              :inc (update self :n inc)
              :dec (update self :n dec)
              self)))

(defonce embedded-kernel
  (stube/make-kernel
    {:base-path "/widget"
     ;; A real host would read its Ring session here.  This example keeps
     ;; the focus on route embedding, so every local request shares one
     ;; development token.
     :session-id-fn (constantly "embedded-dev-session")
     :context-fn    (fn [_request] {:host-name "plain Ring"})}))

(defn- widget-page [req]
  (let [cid (stube/mint-conversation! embedded-kernel
                                      :embedded/counter
                                      {}
                                      req)]
    {:status  200
     :headers {"Content-Type" "text/html; charset=utf-8"
               "Cache-Control" "no-store"}
     :body    (chassis/html
                [chassis/doctype-html5
                 [:html {:lang "en"}
                  [:head
                   [:meta {:charset "utf-8"}]
                   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                   [:title "stube embedded in Ring"]
                   [:link {:rel "stylesheet" :href "/widget/stube/ui.css"}]
                   (vector :script {:type "module" :src shell/datastar-cdn})]
                  [:body
                   [:main {:style "max-width: 42rem; margin: 3rem auto;"}
                    [:h1 "Host Ring app"]
                    [:p "This page is rendered by the host. The widget below is stube."]
                    (stube/shell-for embedded-kernel cid)]]]])}))

(defn handler []
  (ring/ring-handler
    (ring/router
      (concat
        [["/healthz" {:get {:handler (fn [_] {:status 200 :body "ok"})}}]
         ["/api/foo" {:get {:handler (fn [_] {:status 200
                                               :headers {"Content-Type" "application/edn"}
                                               :body (pr-str {:foo true})})}}]
         ["/widget"  {:get {:handler widget-page}}]]
        (stube-ring/ring-routes embedded-kernel)))
    (ring/create-default-handler)))

(defonce !server (atom nil))

(defn start!
  ([] (start! 8080))
  ([port]
   (when-let [stop @!server]
     (stop))
   (let [stop (http-kit/run-server (handler) {:port port})]
     (reset! !server stop)
     (println (str "embedded Ring example on http://localhost:" port "/widget"))
     stop)))

(defn stop! []
  (when-let [stop @!server]
    (stop)
    (reset! !server nil)))

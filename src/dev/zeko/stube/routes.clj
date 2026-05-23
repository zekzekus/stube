(ns dev.zeko.stube.routes
  "The reitit router.

  Conversation-scoped routes are always present; user mounts are
  appended.  The router resolves the user-mount table lazily on each
  request so adding mounts after start works.

  Before this namespace existed, `dev.zeko.stube.server` built the
  router using `requiring-resolve` to dodge a circular require with
  `dev.zeko.stube.http`.  Pulling the build into its own ns lets us
  require both layers directly."
  (:require [reitit.ring             :as ring]
            [dev.zeko.stube.halos.http :as halos-http]
            [dev.zeko.stube.http     :as http]
            [dev.zeko.stube.server   :as server]))

(defn- build-router
  "Build the reitit router from the current mounts."
  []
  (ring/router
    (into [["/stube/ui.css"             {:get  {:handler http/ui-css-handler}}]
           ["/stube/halos.js"           {:get  {:handler halos-http/js-handler}}]
           ["/stube/halos/:cid/panel"   {:get  {:handler halos-http/panel-handler}}]
           ["/stube/halos/:cid/enable"  {:post {:handler halos-http/enable-handler}}]
           ["/conv/:cid/sse"            {:get  {:handler http/sse-handler}}]
           ["/conv/:cid/back"           {:post {:handler http/back-handler}}]
           ["/stube/upload/:cid/:iid"   {:post {:handler http/upload-handler}}]
           ;; The back route is listed *before* the generic
           ;; `:iid/:event` route so reitit picks the more specific one
           ;; first.  Uploads live under `/stube/upload` instead of
           ;; `/conv/:cid/:iid/upload`; otherwise Reitit sees a static
           ;; `upload` segment conflicting with the generic `:event`.
           ["/conv/:cid/:iid/:event"    {:post {:handler http/event-handler}}]]
          (for [[path {:keys [flow-id opts]}] (server/mounts)]
            [path {:get {:handler (http/shell-handler flow-id opts)}}]))))

(defn ring-handler
  "A ring handler that resolves the router on each request so newly
  added mounts are picked up without restarting the server."
  []
  (fn [req]
    (let [handler (ring/ring-handler (build-router)
                                     (ring/create-default-handler))]
      (handler req))))

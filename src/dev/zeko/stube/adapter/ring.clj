(ns dev.zeko.stube.adapter.ring
  "Ring/Reitit adapter for embeddable stube kernels."
  (:require [reitit.ring                 :as ring]
            [dev.zeko.stube.embed       :as embed]
            [dev.zeko.stube.halos.http  :as halos-http]
            [dev.zeko.stube.http        :as http]))

(defn- join-path [base suffix]
  (str (or base "") suffix))

(defn- h [f k]
  (fn [req] (f k req)))

(defn- core-routes [k]
  (let [base (embed/base-path k)]
    (case (embed/route-style k)
      :adapter
      [[(join-path base "/sse/:cid")              {:get  {:handler (h http/sse-handler k)}}]
       [(join-path base "/back/:cid")             {:post {:handler (h http/back-handler k)}}]
       [(join-path base "/upload/:cid/:iid")      {:post {:handler (h http/upload-handler k)}}]
       [(join-path base "/event/:cid/:iid/:event") {:post {:handler (h http/event-handler k)}}]]

      :legacy
      [[(join-path base "/conv/:cid/sse")          {:get  {:handler (h http/sse-handler k)}}]
       [(join-path base "/conv/:cid/back")         {:post {:handler (h http/back-handler k)}}]
       [(join-path base "/stube/upload/:cid/:iid") {:post {:handler (h http/upload-handler k)}}]
       [(join-path base "/conv/:cid/:iid/:event")  {:post {:handler (h http/event-handler k)}}]])))

(defn ring-routes
  "Return a Reitit route data vector for kernel `k`.

  Optional `:mounts` is a map of host paths to root component ids; these
  shell routes are left exactly as supplied so a host can decide where a
  stube widget belongs in its own route tree.  Kernel `:base-path` only
  prefixes generated stube asset/SSE/event URLs."
  ([k]
   (ring-routes k {}))
  ([k {:keys [mounts] :or {mounts {}}}]
   (let [base (embed/base-path k)]
     (into [[(join-path base "/stube/ui.css")            {:get  {:handler http/ui-css-handler}}]
            [(join-path base "/stube/preserve.js")       {:get  {:handler http/preserve-js-handler}}]
            [(join-path base "/stube/halos.js")          {:get  {:handler (h halos-http/js-handler k)}}]
            [(join-path base "/stube/halos/:cid/panel")  {:get  {:handler (h halos-http/panel-handler k)}}]
            [(join-path base "/stube/halos/:cid/enable") {:post {:handler (h halos-http/enable-handler k)}}]]
           (concat (core-routes k)
                   (for [[path mount-val] mounts
                         :let [{:keys [flow-id opts]
                                :or {opts {}}}
                               (if (map? mount-val)
                                 mount-val
                                 {:flow-id mount-val})]
                         :when flow-id]
                     [path {:get {:handler (http/shell-handler k flow-id opts)}}]))))))

(defn ring-handler
  "Return a Ring handler for kernel `k`.

  `:mounts-fn`, when supplied, is called on each request so standalone
  `mount!` calls can be picked up without restarting.  Otherwise `:mounts`
  is used as a static path→root map."
  ([k]
   (ring-handler k {}))
  ([k {:keys [mounts mounts-fn]
       :or {mounts {}}}]
   (fn [req]
     (let [mounts  (if mounts-fn (mounts-fn) mounts)
           handler (ring/ring-handler
                     (ring/router (ring-routes k {:mounts mounts}))
                     (ring/create-default-handler))]
       (handler req)))))

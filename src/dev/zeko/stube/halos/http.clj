(ns dev.zeko.stube.halos.http
  "Ring handlers for the dev halos overlay.  All endpoints 404 when the
  server was not started with `:halos? true`, so production builds
  never expose them.

  Three endpoints:

  | route                          | method | purpose                                |
  |--------------------------------|--------|----------------------------------------|
  | `<base>/halos.js`              | GET    | the overlay script                     |
  | `<base>/halos/:cid/enable`     | POST   | flip a conv into halos mode + redraw   |
  | `<base>/halos/:cid/panel`      | GET    | render the inspector side-panel HTML   |"
  (:require [clojure.java.io       :as io]
            [clojure.string        :as str]
            [dev.zeko.stube.halos   :as halos]
            [dev.zeko.stube.render  :as render]
            [dev.zeko.stube.runtime :as rt]
            [dev.zeko.stube.session :as session]))

(defn- default-kernel []
  ((requiring-resolve 'dev.zeko.stube.server/default-kernel)))

(defn- query-string-value
  "Tiny query-string parser for the one or two halo-owned params; the
  app intentionally has no params middleware."
  [{:keys [query-string]} k]
  (some (fn [part]
          (let [[raw-k raw-v] (str/split part #"=" 2)]
            (when (= k (java.net.URLDecoder/decode (str raw-k) "UTF-8"))
              (java.net.URLDecoder/decode (str (or raw-v "")) "UTF-8"))))
        (some-> query-string (str/split #"&"))))

(defn requested?
  "True when the request URL has `?halos=1` (or any value)."
  [req]
  (some? (query-string-value req "halos")))

(defn js-handler
  "Serve the dev halos overlay script.  404s when halos is disabled."
  ([req]
   (js-handler (default-kernel) req))
  ([k _req]
   (cond
     (not (rt/halos? k))
     {:status 404 :body "stube halos disabled"}

     :else
     (if-let [res (io/resource "dev/zeko/stube/halos.js")]
       {:status  200
        :headers {"Content-Type"  "application/javascript; charset=utf-8"
                  "Cache-Control" "no-store"}
        :body    (slurp res)}
       {:status 404 :body "stube halos.js not found"}))))

(defn- tab-keyword [s]
  (case (some-> s str/lower-case)
    "instance" :instance
    "html"     :html
    "history"  :history
    :tree))

(defn enable-handler
  "POST `<base>/halos/:cid/enable` — flip the conv into halos mode and
  push a freshly-decorated frame so the overlay sees data-stube attrs
  without a hard reload."
  ([req]
   (enable-handler (default-kernel) req))
  ([k {:keys [path-params] :as req}]
   (let [cid (:cid path-params)]
     (cond
       (not (rt/halos? k))
       {:status 404 :body "stube halos disabled"}

       (nil? (rt/conversation k cid))
       {:status 410 :body "no such conversation"}

       (not (rt/authorized? k req cid))
       (session/forbidden-response)

       :else
       (do (rt/enable-halos-and-redraw! k cid)
           {:status 204})))))

(defn panel-handler
  "Return the rendered side-panel HTML for the dev halos overlay.

  404s when the server has halos off; 410 when the conversation has
  halos off (the overlay shouldn't be polling); plain 200 HTML otherwise."
  ([req]
   (panel-handler (default-kernel) req))
  ([k {:keys [path-params] :as req}]
   (cond
     (not (rt/halos? k))
     {:status 404 :body "stube halos disabled"}

     :else
     (let [cid  (:cid path-params)
           conv (rt/conversation k cid)]
       (cond
         (or (nil? conv) (not (:conv/halos? conv)))
         {:status  410
          :headers {"Content-Type" "text/html; charset=utf-8"
                    "Cache-Control" "no-store"}
          :body    "<div class=\"stube-halo-body\">halos not active for this conversation</div>"}

         (not (rt/authorized? k req cid))
         (session/forbidden-response)

         :else
         (let [iid (query-string-value req "iid")
               tab (tab-keyword (query-string-value req "tab"))]
           {:status  200
            :headers {"Content-Type"  "text/html; charset=utf-8"
                      "Cache-Control" "no-store"}
            :body    (render/html (halos/panel-hiccup conv {:iid iid :tab tab}))}))))))

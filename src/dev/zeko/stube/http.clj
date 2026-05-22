(ns dev.zeko.stube.http
  "Ring handlers that bridge HTTP to the kernel.

  Five endpoints implement the client/server contract:

  | route                       | method | purpose                                                                       |
  |-----------------------------|--------|-------------------------------------------------------------------------------|
  | `/<mount-path>`             | GET    | mint a conversation, serve the shell HTML                                     |
  | `/conv/:cid/sse`            | GET    | open the long-lived SSE stream for the conversation                           |
  | `/conv/:cid/back`           | POST   | restore the previous conversation snapshot                                    |
  | `/stube/upload/:cid/:iid`   | POST   | parse multipart data and dispatch `:upload-received`                          |
  | `/conv/:cid/:iid/:event`    | POST   | dispatch one event; the iid and event live in the path, signals in the body  |

  The shell page is a trivial HTML document.  All real UI is delivered
  via SSE patches once the browser connects to `/conv/:cid/sse`."
  (:require [charred.api                                       :as json]
            [clojure.edn                                      :as edn]
            [clojure.java.io                                  :as io]
            [clojure.string                                   :as str]
            [ring.middleware.multipart-params                  :as multipart]
            [starfederation.datastar.clojure.api               :as d*]
            [starfederation.datastar.clojure.adapter.http-kit  :as hk]
            [dev.zeko.stube.conversation                       :as conv]
            [dev.zeko.stube.fragments                          :as f]
            [dev.zeko.stube.halos.http                         :as halos-http]
            [dev.zeko.stube.kernel                             :as kernel]
            [dev.zeko.stube.render                             :as render]
            [dev.zeko.stube.session                            :as session]
            [dev.zeko.stube.shell                              :as shell])
  (:import (java.net URLDecoder)))

(defn- default-kernel []
  ((requiring-resolve 'dev.zeko.stube.server/default-kernel)))

;; ---------------------------------------------------------------------------
;; JSON helpers
;; ---------------------------------------------------------------------------
;;
;; Charred lets us hoist parsing out of the hot path: the parser is built
;; once and reused.  `:key-fn keyword` so the kernel sees real keywords
;; throughout (which it relies on for things like `(:event ev)`).

(def ^:private parse-json
  (json/parse-json-fn {:async?  false
                       :bufsize 8192
                       :key-fn  keyword}))

(defn- read-signals
  "Pull the Datastar signals payload from a request and parse it.
  Returns `{}` if there are none."
  [req]
  (let [raw (d*/get-signals req)]
    (cond
      (nil? raw)        {}
      (string? raw)     (parse-json raw)
      :else             (with-open [s raw] (parse-json s)))))

(defn- url-decode [s]
  (URLDecoder/decode (str s) "UTF-8"))

(defn- query-value
  "Return decoded query-param `k` from Ring's raw `:query-string`.
  The app intentionally has no params middleware; parsing the one
  stube-owned key here keeps the handler stack tiny."
  [{:keys [query-string]} k]
  (some (fn [part]
          (let [[raw-k raw-v] (str/split part #"=" 2)]
            (when (= k (url-decode raw-k))
              (url-decode (or raw-v "")))))
        (some-> query-string (str/split #"&"))))

(defn- read-event-payload [req]
  (some-> (query-value req render/payload-query-param)
          (edn/read-string)))

;; ---------------------------------------------------------------------------
;; Optional slf4j MDC integration
;; ---------------------------------------------------------------------------

(defn- mdc-class []
  (try
    (Class/forName "org.slf4j.MDC")
    (catch Throwable _ nil)))

(defn- mdc-call! [method-name signature args]
  (when-let [cls (mdc-class)]
    (try
      (let [method (.getMethod cls method-name (into-array Class signature))]
        (.invoke method nil (object-array args)))
      (catch Throwable _ nil))))

(defn- with-mdc [pairs f]
  (doseq [[k v] pairs]
    (when v
      (mdc-call! "put" [String String] [(name k) (str v)])))
  (try
    (f)
    (finally
      (doseq [[k _] pairs]
        (mdc-call! "remove" [String] [(name k)])))))

;; ---------------------------------------------------------------------------
;; ui.css handler (the shell links to it; serving it lives in this layer)
;; ---------------------------------------------------------------------------

(defn ui-css-handler
  "Serve the opt-out stock stylesheet linked by the shell."
  [_req]
  (if-let [res (io/resource "dev/zeko/stube/ui.css")]
    {:status  200
     :headers {"Content-Type" "text/css; charset=utf-8"
               "Cache-Control" "public, max-age=3600"}
     :body    (slurp res)}
    {:status 404
     :body   "stube ui.css not found"}))

;; ---------------------------------------------------------------------------
;; Stale-page response
;; ---------------------------------------------------------------------------

(defn- stale-fragments []
  [(f/elements (render/html
                 [:section {:class "stube-card stube-modal"}
                  [:h2 "This page is stale"]
                  [:p "Please reload to start a fresh conversation."]])
               {:selector "#root" :patch-mode :inner})
   f/close])

(defn- stale-response!
  "Tell the browser its page no longer matches a live instance, then
  forget the conversation."
  [k cid]
  (let [frags (stale-fragments)]
    (when-let [sse-gen (kernel/sse k cid)]
      (f/push! sse-gen frags))
    (kernel/end-conversation! k cid)
    {:status  410
     :headers {"Content-Type" "text/plain; charset=utf-8"
               "Cache-Control" "no-store"}
     :body    "stube conversation is stale; please reload."}))

(defn- multipart-file? [v]
  (and (map? v) (contains? v :filename) (contains? v :tempfile)))

(defn- upload-file-summary [field {:keys [filename content-type tempfile size]}]
  (let [file (when tempfile (io/file tempfile))]
    (cond-> {:field        (name field)
             :filename     (or filename "")
             :content-type (or content-type "application/octet-stream")
             :size         (or size (when (and file (.exists file)) (.length file)) 0)}
      file (assoc :tempfile (.getAbsolutePath file)))))

(defn- upload-payload [req]
  (let [params (:multipart-params req)]
    {:fields (into {}
                   (keep (fn [[k v]]
                           (when-not (multipart-file? v)
                             [(keyword (name k)) v])))
                   params)
     :files  (mapv (fn [[k v]] (upload-file-summary k v))
                   (filter (fn [[_ v]] (multipart-file? v)) params))}))

(defn- upload-ok-response []
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"
             "Cache-Control" "no-store"}
   :body    "<!doctype html><title>uploaded</title>uploaded"})

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn shell-handler
  "Build the GET handler for a mount path.  Each request mints a fresh
  conversation pre-bound to `flow-id`; the cid is embedded in the shell
  so the browser's first SSE GET is to the right place.

  When the server is started with `:halos? true`, every shell injects
  the halos overlay (initially inactive) and a `data-stube-cid` hook so
  the user can enable the overlay from a floating pill.  Adding
  `?halos=1` on the URL is a shortcut that pre-enables the conv."
  ([flow-id]
   (shell-handler (default-kernel) flow-id))
  ([k flow-id]
   (fn [req]
     (let [[sid set-cookie] (kernel/ensure-session k req)
           cid       (kernel/create-conversation! k flow-id sid)
           dev?      (kernel/halos? k)
           pre-on?   (and dev? (halos-http/requested? req))]
       (when pre-on?
         (kernel/enable-halos! k cid))
       {:status  200
        :headers (cond-> {"Content-Type" "text/html; charset=utf-8"
                          "Cache-Control" "no-store"}
                   set-cookie (assoc "Set-Cookie" set-cookie))
        :body    (shell/html cid {:dev? dev?
                                  :ui-css? (kernel/ui-css? k)
                                  :base-path (kernel/base-path k)
                                  :route-style (kernel/route-style k)
                                  :root-selector (kernel/root-selector k)})}))))

(defn- resume-render
  "Render the current top frame of a conversation that already has
  state.  Used by the SSE handler on path 2 (re-attach to a restored
  conversation): run the top frame's `:wakeup` hook, clear its
  `:instance/rendered?` flag so `render-frame`'s \"first render\" branch
  fires, then render it.

  Returns `[conv' fragments]`.  If the stack is empty we leave the
  conversation alone and return no fragments."
  [conv]
  (kernel/resume-top conv))

(defn sse-handler
  "Open the long-lived SSE stream for a conversation.

  Three startup paths:

  1. **Fresh shell visit** — the cid was just minted by [[shell-handler]],
     so a pending flow id is on the baton.  We boot the flow.
  2. **Restored conversation** — the cid exists in memory (loaded from
     the persistence store at startup, or carried over a hot reload),
     but no pending flow is pending.  We re-render its current top
     frame so the freshly-attached browser sees the restored UI.
  3. **Unknown cid** — the conversation is gone (ended, expired).  The
     SSE channel just stays empty; the browser will see no patches.

  Thereafter the kernel pushes whenever an event handler produces
  fragments."
  ([req]
   (sse-handler (default-kernel) req))
  ([k {:keys [path-params] :as req}]
   (let [cid (:cid path-params)]
     (if-not (kernel/authorized? k req cid)
       (session/forbidden-response)
       (hk/->sse-response req
         {hk/on-open
          (fn [sse-gen]
            (with-mdc {:cid cid}
              (fn []
                (kernel/register-sse! k cid sse-gen)
                ;; `pending-root` is a one-shot: it pops the baton.
                ;; Read it exactly once into a local before branching,
                ;; or path 2 will fire after path 1 consumed the value.
                (let [pending (kernel/pending-root k cid)
                      live    (kernel/conversation k cid)]
                  (cond
                    ;; Path 1 — fresh shell visit; instantiate the root flow.
                    (some? pending)
                    (kernel/apply-conv! k cid
                      (fn [c] (kernel/run-effects c (kernel/boot pending))))

                    ;; Path 2 — re-attach to a conversation that survives in
                    ;; memory (loaded from the persistence store at startup,
                    ;; or carried across a hot reload of the http layer).
                    (some? live)
                    (kernel/apply-conv! k cid resume-render)

                    ;; Path 3 — unknown cid; the conversation is gone.
                    :else
                    nil)))))

          hk/on-close
          (fn [_sse-gen _status]
            (kernel/unregister-sse! k cid))})))))

(defn back-handler
  "POST `/conv/:cid/back` — emit the [[:back]] effect.  Mirrors
  [[event-handler]] but without an instance id or signals payload."
  ([req]
   (back-handler (default-kernel) req))
  ([k {:keys [path-params] :as req}]
   (let [cid (:cid path-params)
         live (kernel/conversation k cid)]
     (cond
       (nil? live)
       (stale-response! k cid)

       (not (kernel/authorized? k req cid))
       (session/forbidden-response)

       :else
       (with-mdc {:cid cid}
         (fn []
           (kernel/run-effects! k cid [[:back]])
           {:status 204}))))))

(defn upload-handler
  "POST `/stube/upload/:cid/:iid` — parse a multipart request and route it
  to the target instance as `:upload-received`.

  Upload responses are written into a hidden iframe by `s/upload-attrs`;
  the visible page updates over the normal SSE stream."
  ([req]
   (upload-handler (default-kernel) req))
  ([k {:keys [path-params] :as req}]
   (let [{:keys [cid iid]} path-params
         live (kernel/conversation k cid)]
     (cond
       (nil? live)
       (stale-response! k cid)

       (not (kernel/authorized? k req cid))
       (session/forbidden-response)

       (or (:conv/ended? live)
           (nil? (conv/instance live iid)))
       (stale-response! k cid)

       :else
       (with-mdc {:cid cid :iid iid}
         (fn []
           (let [req'    (if (:multipart-params req)
                           req
                           (multipart/multipart-params-request req))
                 payload (upload-payload req')]
             (kernel/dispatch! k cid {:instance-id iid
                                      :event       :upload-received
                                      :payload     payload
                                      :signals     {}})
             (upload-ok-response))))))))

(defn event-handler
  "Dispatch one client event into the conversation.  The instance id
  and event name are taken from the URL path; everything left in the
  request body / query is treated as the current Datastar signals."
  ([req]
   (event-handler (default-kernel) req))
  ([k {:keys [path-params] :as req}]
   (let [{:keys [cid iid event]} path-params
         live (kernel/conversation k cid)]
     (cond
       (nil? live)
       (stale-response! k cid)

       (not (kernel/authorized? k req cid))
       (session/forbidden-response)

       (or (:conv/ended? live)
           (nil? (conv/instance live iid)))
       (stale-response! k cid)

       :else
       (with-mdc {:cid cid :iid iid}
         (fn []
           (let [signals (read-signals req)
                 ev      {:instance-id iid
                          :event       (keyword event)
                          :payload     (read-event-payload req)
                          :signals     signals}]
             (kernel/dispatch! k cid ev)
             {:status 204})))))))

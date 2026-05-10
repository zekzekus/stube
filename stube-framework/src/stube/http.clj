(ns stube.http
  "Ring handlers that bridge HTTP to the kernel.

  Three endpoints implement the entire client/server contract:

  | route                       | method | purpose                                                                       |
  |-----------------------------|--------|-------------------------------------------------------------------------------|
  | `/<mount-path>`             | GET    | mint a conversation, serve the shell HTML                                     |
  | `/conv/:cid/sse`            | GET    | open the long-lived SSE stream for the conversation                           |
  | `/conv/:cid/:iid/:event`    | POST   | dispatch one event; the iid and event live in the path, signals in the body  |

  The shell page is a trivial HTML document.  All real UI is delivered
  via SSE patches once the browser connects to `/conv/:cid/sse`."
  (:require [charred.api                                       :as json]
            [clojure.edn                                      :as edn]
            [clojure.java.io                                  :as io]
            [clojure.string                                   :as str]
            [dev.onionpancakes.chassis.core                    :as chassis]
            [starfederation.datastar.clojure.api               :as d*]
            [starfederation.datastar.clojure.adapter.http-kit  :as hk]
            [stube.conversation                                :as conv]
            [stube.kernel                                      :as kernel]
            [stube.render                                      :as render]
            [stube.server                                      :as server])
  (:import (java.net URLDecoder)
           (java.util UUID)))

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

(def ^:private write-json
  (json/write-json-fn {}))

(defn- json-str ^String [m]
  (let [w (java.io.StringWriter.)]
    (write-json w m)
    (str w)))

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
;; Session ownership
;; ---------------------------------------------------------------------------

(def ^:private session-cookie "stube_sid")

(defn- cookie-map [{:keys [headers]}]
  (into {}
        (keep (fn [part]
                (let [[k v] (str/split (str/trim part) #"=" 2)]
                  (when (seq k) [k v]))))
        (some-> (or (get headers "cookie") (get headers "Cookie"))
                (str/split #";"))))

(defn- request-session [req]
  (get (cookie-map req) session-cookie))

(defn- new-session []
  (str (UUID/randomUUID)))

(defn- session-cookie-header [sid]
  (str session-cookie "=" sid "; Path=/; HttpOnly; SameSite=Lax"))

(defn- ensure-session [req]
  (if-let [sid (request-session req)]
    [sid nil]
    (let [sid (new-session)]
      [sid (session-cookie-header sid)])))

(defn- authorized? [req cid]
  (let [conv  (server/conversation cid)
        owner (:conv/owner-token conv)]
    (or (nil? owner)
        (= owner (request-session req)))))

(defn- forbidden-response []
  {:status  403
   :headers {"Content-Type" "text/plain; charset=utf-8"
             "Cache-Control" "no-store"}
   :body    "stube conversation belongs to a different session."})

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
;; The shell
;; ---------------------------------------------------------------------------
;;
;; The browser receives an effectively-empty page that hands control to
;; Datastar.  As soon as the body loads, Datastar opens the SSE stream
;; and the kernel pushes the first frame.

(def datastar-cdn d*/CDN-url)
(def ui-css-path "/stube/ui.css")

(defn- shell-html [cid]
  ;; `data-init` runs once when Datastar processes the element after the
  ;; page loads.  We open the long-lived SSE stream there; every patch
  ;; the kernel pushes thereafter morphs into the DOM by id, starting
  ;; with the `<div id="root">` placeholder below.
  (chassis/html
    [chassis/doctype-html5
     [:html {:lang "en"}
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
       [:title "stube"]
       (when (server/ui-css?)
         [:link {:rel "stylesheet" :href ui-css-path}])
       [:script {:type "module" :src datastar-cdn}]]
      [:body {:data-init (str "@get('/conv/" cid "/sse')")}
       [:div {:id "root"}]]]]))

(defn ui-css-handler
  "Serve the opt-out stock stylesheet linked by the shell."
  [_req]
  (if-let [res (io/resource "stube/ui.css")]
    {:status  200
     :headers {"Content-Type" "text/css; charset=utf-8"
               "Cache-Control" "public, max-age=3600"}
     :body    (slurp res)}
    {:status 404
     :body   "stube ui.css not found"}))

;; ---------------------------------------------------------------------------
;; Pushing fragments to the wire
;; ---------------------------------------------------------------------------

(def ^:private patch-modes
  "Translate kernel keyword patch modes to the Datastar string constants."
  {:outer   d*/pm-outer
   :inner   d*/pm-inner
   :remove  d*/pm-remove
   :prepend d*/pm-prepend
   :append  d*/pm-append
   :before  d*/pm-before
   :after   d*/pm-after
   :replace d*/pm-replace})

(defn- elements-opts
  "Translate the kernel's wire-agnostic options map into the Datastar
  SDK's namespaced-keyword option keys."
  [{:keys [selector patch-mode]}]
  (cond-> {}
    selector   (assoc d*/selector   selector)
    patch-mode (assoc d*/patch-mode (or (patch-modes patch-mode)
                                        (throw (ex-info "Unknown patch-mode"
                                                        {:patch-mode patch-mode}))))))

(defn- push-fragment!
  "Translate one kernel fragment into one Datastar SSE event."
  [sse-gen {:fragment/keys [kind html data script opts]}]
  (case kind
    :elements (d*/patch-elements! sse-gen html (elements-opts opts))
    :signals  (d*/patch-signals!  sse-gen (json-str data) (or opts {}))
    :script   (d*/execute-script! sse-gen script (or opts {}))
    :close    (d*/close-sse! sse-gen)))

(defn- push-fragments!
  "Push several fragments in order, holding the SSE lock so they are not
  interleaved with concurrent pushes."
  [sse-gen fragments]
  (when (seq fragments)
    (d*/lock-sse! sse-gen
      (doseq [f fragments]
        (push-fragment! sse-gen f)))))

(defn- stale-fragments []
  [{:fragment/kind :elements
    :fragment/html (render/html
                     [:section {:class "stube-card stube-modal"}
                      [:h2 "This page is stale"]
                      [:p "Please reload to start a fresh conversation."]])
    :fragment/opts {:selector "#root" :patch-mode :inner}}
   {:fragment/kind :close}])

(defn- stale-response!
  "Tell the browser its page no longer matches a live instance, then
  forget the conversation."
  [cid]
  (let [frags (stale-fragments)]
    (when-let [sse-gen (server/sse cid)]
      (push-fragments! sse-gen frags))
    (server/end-conversation! cid)
    {:status  410
     :headers {"Content-Type" "text/plain; charset=utf-8"
               "Cache-Control" "no-store"}
     :body    "stube conversation is stale; please reload."}))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn shell-handler
  "Build the GET handler for a mount path.  Each request mints a fresh
  conversation pre-bound to `flow-id`; the cid is embedded in the shell
  so the browser's first SSE GET is to the right place."
  [flow-id]
  (fn [req]
    (let [[sid set-cookie] (ensure-session req)
          cid (server/create-conversation! flow-id sid)]
      {:status  200
       :headers (cond-> {"Content-Type" "text/html; charset=utf-8"
                         "Cache-Control" "no-store"}
                  set-cookie (assoc "Set-Cookie" set-cookie))
       :body    (shell-html cid)})))

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
  [{:keys [path-params] :as req}]
  (let [cid (:cid path-params)]
    (if-not (authorized? req cid)
      (forbidden-response)
      (hk/->sse-response req
        {hk/on-open
        (fn [sse-gen]
          (with-mdc {:cid cid}
            (fn []
              (server/register-sse! cid sse-gen)
        ;; `server/pending-flow` is a one-shot: it pops the baton.
        ;; Read it exactly once into a local before branching, or path
        ;; 2 will fire after path 1 already consumed the value.
        (let [pending (server/pending-flow cid)
              live    (server/conversation cid)]
          (cond
            ;; Path 1 — fresh shell visit; instantiate the root flow.
            (some? pending)
            (let [[conv' frags]
                  (server/swap-conv!
                    cid
                    (fn [c]
                      (binding [render/*cid* cid]
                        (kernel/run-effects c (kernel/boot pending)))))]
              (binding [render/*cid* cid]
                (push-fragments! sse-gen frags))
              (when (:conv/ended? conv')
                (server/end-conversation! cid)))

            ;; Path 2 — re-attach to a conversation that survives in
            ;; memory (loaded from the persistence store at startup,
            ;; or carried across a hot reload of the http layer).
            (some? live)
            (let [[_conv frags]
                  (server/swap-conv!
                    cid
                    (fn [c]
                      (binding [render/*cid* cid]
                        (resume-render c))))]
              (binding [render/*cid* cid]
                (push-fragments! sse-gen frags)))

            ;; Path 3 — unknown cid; the conversation is gone.  Leave
            ;; the SSE channel empty; the browser will simply see no
            ;; patches.
            :else
            nil)))))

       hk/on-close
       (fn [_sse-gen _status]
           (server/unregister-sse! cid))}))))

(defn back-handler
  "POST `/conv/:cid/back` — emit the [[:back]] effect.  Mirrors
  [[event-handler]] but without an instance id or signals payload."
  [{:keys [path-params] :as req}]
  (let [cid (:cid path-params)
        live (server/conversation cid)]
    (cond
      (nil? live)
      (stale-response! cid)

      (not (authorized? req cid))
      (forbidden-response)

      :else
      (with-mdc {:cid cid}
        (fn []
          (let [[conv' frags] (binding [render/*cid* cid]
                                (server/swap-conv!
                                  cid
                                  (fn [c]
                                    (binding [render/*cid* cid]
                                      (kernel/run-effects c [[:back]])))))]
            (when-let [sse-gen (server/sse cid)]
              (binding [render/*cid* cid]
                (push-fragments! sse-gen frags)))
            (when (:conv/ended? conv')
              (server/end-conversation! cid))
            {:status 204}))))))

(defn event-handler
  "Dispatch one client event into the conversation.  The instance id
  and event name are taken from the URL path; everything left in the
  request body / query is treated as the current Datastar signals."
  [{:keys [path-params] :as req}]
  (let [{:keys [cid iid event]} path-params
        live (server/conversation cid)]
    (cond
      (nil? live)
      (stale-response! cid)

      (not (authorized? req cid))
      (forbidden-response)

      (or (:conv/ended? live)
          (nil? (conv/instance live iid)))
      (stale-response! cid)

      :else
      (with-mdc {:cid cid :iid iid}
        (fn []
          (let [signals (read-signals req)
                ev      {:instance-id iid
                         :event       (keyword event)
                         :payload     (read-event-payload req)
                         :signals     signals}
                [conv' frags] (binding [render/*cid* cid]
                                (server/swap-conv! cid
                                                   (fn [c] (kernel/dispatch c ev))))]
            (when-let [sse-gen (server/sse cid)]
              (binding [render/*cid* cid]
                (push-fragments! sse-gen frags)))
            (when (:conv/ended? conv')
              (server/end-conversation! cid))
            {:status 204}))))))

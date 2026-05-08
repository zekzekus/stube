(ns stube.server
  "All the side-effecting state — conversations, SSE generators, mounted
  flows — and the http-kit lifecycle.

  Three small atoms hold everything:

  | atom               | shape                | purpose                                    |
  |--------------------|----------------------|--------------------------------------------|
  | `!conversations`   | `{cid → conv}`       | the live conversation values               |
  | `!sse-sessions`    | `{cid → sse-gen}`    | the open browser connections               |
  | `!mounts`          | `{path → flow-id}`   | the routes registered with [[mount!]]      |

  Plus `!pending-flows` (`{cid → flow-id}`), used as a one-shot baton
  between the request that minted the cid and the first SSE connect that
  actually instantiates the flow.

  All the http handlers live in [[stube.http]]; this namespace only
  exposes the functions they need, so the http layer never reaches into
  raw atoms."
  (:require [org.httpkit.server :as http-kit]
            [reitit.ring        :as ring]
            [stube.conversation :as conv]))

;; ---------------------------------------------------------------------------
;; Storage
;; ---------------------------------------------------------------------------

(defonce ^:private !conversations (atom {}))
(defonce ^:private !sse-sessions  (atom {}))
(defonce ^:private !pending-flows (atom {}))
(defonce ^:private !mounts        (atom (sorted-map)))
(defonce ^:private !server        (atom nil))

;; ---------------------------------------------------------------------------
;; Conversation helpers
;; ---------------------------------------------------------------------------

(defn create-conversation!
  "Mint a fresh conversation, remember its pending root flow, and return
  the new cid.  The flow is only *instantiated* when the browser opens
  the SSE stream — at that point the kernel runs `boot` against this
  empty conversation."
  [flow-id]
  (let [c   (conv/new-conversation)
        cid (:conv/id c)]
    (swap! !conversations assoc cid c)
    (swap! !pending-flows assoc cid flow-id)
    cid))

(defn pending-flow
  "Pop and return the pending flow id for `cid`, if any.  After this
  call the cid no longer has a pending flow."
  [cid]
  (let [[old _] (swap-vals! !pending-flows dissoc cid)]
    (get old cid)))

(defn conversation
  "Snapshot of the named conversation, or nil."
  [cid]
  (get @!conversations cid))

(defn swap-conv!
  "Atomically apply `f` (which must return `[conv' fragments]`) to the
  conversation under `cid`, install `conv'`, and return the full
  `[conv' fragments]` pair so the caller can act on the fragments.

  `f` may be retried by `swap!` under CAS contention; its only side
  effect should be the data it returns."
  [cid f]
  (let [box (volatile! nil)]
    (swap! !conversations
           (fn [m]
             (let [c        (get m cid)
                   pair     (f c)
                   [c' _fr] pair]
               (vreset! box pair)
               (assoc m cid c'))))
    @box))

(defn end-conversation!
  "Drop a conversation and any SSE binding."
  [cid]
  (swap! !conversations dissoc cid)
  (swap! !sse-sessions  dissoc cid)
  (swap! !pending-flows dissoc cid)
  nil)

;; ---------------------------------------------------------------------------
;; SSE session helpers
;; ---------------------------------------------------------------------------

(defn register-sse!   [cid sse-gen] (swap! !sse-sessions assoc cid sse-gen))
(defn unregister-sse! [cid]         (swap! !sse-sessions dissoc cid))
(defn sse              [cid]         (get @!sse-sessions cid))

;; ---------------------------------------------------------------------------
;; Mounting flows
;; ---------------------------------------------------------------------------

(defn mount!
  "Register a flow at a URL path.  After [[start!]], `GET <path>` will
  serve the shell page and pre-bind the resulting conversation to the
  named flow."
  [path flow-id]
  (when-not (string? path)
    (throw (ex-info "mount! path must be a string" {:got path})))
  (when-not (qualified-keyword? flow-id)
    (throw (ex-info "mount! flow-id must be a namespaced keyword"
                    {:got flow-id})))
  (swap! !mounts assoc path flow-id)
  nil)

(defn unmount! [path] (swap! !mounts dissoc path) nil)

(defn mounts [] @!mounts)

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn- build-router
  "Build the reitit router from the current mounts.  The two
  conversation-scoped routes are always present; user mounts are
  appended.  Looked up lazily so adding mounts after start works."
  []
  (let [;; Resolved late to avoid a circular require with stube.http.
        h (requiring-resolve 'stube.http/shell-handler)
        sse-h (requiring-resolve 'stube.http/sse-handler)
        ev-h  (requiring-resolve 'stube.http/event-handler)]
    (ring/router
      (into [["/conv/:cid/sse"            {:get  {:handler @sse-h}}]
             ["/conv/:cid/:iid/:event"    {:post {:handler @ev-h}}]]
            (for [[path flow-id] @!mounts]
              [path {:get {:handler (@h flow-id)}}])))))

(defn- ring-handler
  "A ring handler that resolves the router on each request so newly
  added mounts are picked up without restarting the server.  Slice 0 is
  not perf-critical."
  []
  (fn [req]
    (let [handler (ring/ring-handler (build-router)
                                     (ring/create-default-handler))]
      (handler req))))

(defn start!
  "Start http-kit on `port` (default 8080).  Idempotent: a second call
  with the server already running stops the old one first."
  ([] (start! {}))
  ([{:keys [port] :or {port 8080}}]
   (when @!server
     (@!server))
   (let [stop-fn (http-kit/run-server (ring-handler)
                                      {:port                 port
                                       :legacy-return-value? false})]
     (reset! !server (fn [] (http-kit/server-stop! stop-fn)))
     (println (str "stube listening on http://localhost:" port))
     (doseq [[p f] (mounts)]
       (println "  mount" p "→" f))
     stop-fn)))

(defn stop!
  "Stop the running server, if any."
  []
  (when-let [stop-fn @!server]
    (stop-fn)
    (reset! !server nil)))

;; ---------------------------------------------------------------------------
;; Test / REPL helpers
;; ---------------------------------------------------------------------------

(defn reset-state!
  "Wipe all in-memory state.  Intended for tests and REPL iteration."
  []
  (stop!)
  (reset! !conversations {})
  (reset! !sse-sessions  {})
  (reset! !pending-flows {})
  (reset! !mounts        (sorted-map))
  nil)

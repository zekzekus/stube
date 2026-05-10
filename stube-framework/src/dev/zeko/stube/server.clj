(ns dev.zeko.stube.server
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

  All the http handlers live in [[dev.zeko.stube.http]]; this namespace only
  exposes the functions they need, so the http layer never reaches into
  raw atoms."
  (:require [clojure.pprint     :as pprint]
            [org.httpkit.server :as http-kit]
            [reitit.ring        :as ring]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.store        :as store])
  (:import (java.time Duration Instant)))

;; ---------------------------------------------------------------------------
;; Storage
;; ---------------------------------------------------------------------------

(defonce ^:private !conversations (atom {}))
(defonce ^:private !sse-sessions  (atom {}))
(defonce ^:private !pending-flows (atom {}))
(defonce ^:private !mounts        (atom (sorted-map)))
(defonce ^:private !server        (atom nil))
(defonce ^:private !ui-css?       (atom true))
(defonce ^:private !reaper-stop   (atom nil))

;; Slice 3 — the configured persistence backend.  Defaults to a no-op
;; in-memory store, so existing tests and demos keep their slice-0
;; behaviour exactly.  Replaced by `start!` if a `:store` is supplied.
(defonce ^:private !store         (atom (store/in-memory-store)))

(defn current-store
  "The store currently in use.  Exposed for tests and tooling; the
  http layer never needs to call it."
  []
  @!store)

;; ---------------------------------------------------------------------------
;; Conversation helpers
;; ---------------------------------------------------------------------------

(defn create-conversation!
  "Mint a fresh conversation, remember its pending root flow, and return
  the new cid.  The flow is only *instantiated* when the browser opens
  the SSE stream — at that point the kernel runs `boot` against this
  empty conversation."
  ([flow-id]
   (create-conversation! flow-id nil))
  ([flow-id owner-token]
   (let [c   (cond-> (conv/new-conversation)
               owner-token (assoc :conv/owner-token owner-token))
         cid (:conv/id c)]
     (swap! !conversations assoc cid c)
     (swap! !pending-flows assoc cid flow-id)
     cid)))

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
  conversation under `cid`, install `conv'`, persist it via the
  configured store, and return the full `[conv' fragments]` pair so
  the caller can act on the fragments.

  `f` may be retried by `swap!` under CAS contention; its only side
  effect should be the data it returns.  The store's `save!` runs
  *outside* the swap retry loop, so it sees only the final value."
  [cid f]
  (let [box (volatile! nil)]
    (swap! !conversations
           (fn [m]
             (let [c        (get m cid)
                   pair     (f c)
                   [c' _fr] pair]
               (vreset! box pair)
               (assoc m cid c'))))
    (let [[c' _] @box]
      (try
        (store/save! @!store c')
        (catch Throwable t
          ;; Persistence is best-effort: a failure here must not break
          ;; the live request.  The store itself is responsible for any
          ;; informative logging.
          (binding [*out* *err*]
            (println "dev.zeko.stube.server: store save! threw —" (ex-message t))))))
    @box))

(defn end-conversation!
  "Drop a conversation, any SSE binding, and the persisted copy."
  [cid]
  (swap! !conversations dissoc cid)
  (swap! !sse-sessions  dissoc cid)
  (swap! !pending-flows dissoc cid)
  (try
    (store/delete! @!store cid)
    (catch Throwable t
      (binding [*out* *err*]
        (println "dev.zeko.stube.server: store delete! threw —" (ex-message t)))))
  nil)

(defn active-conversations
  "Return a snapshot of all active conversations keyed by cid."
  []
  @!conversations)

(defn end!
  "Public admin wrapper around [[end-conversation!]]."
  [cid]
  (end-conversation! cid))

(defn- ->duration [x]
  (cond
    (nil? x) nil
    (instance? Duration x) x
    (integer? x) (Duration/ofMillis x)
    :else (throw (ex-info "Expected java.time.Duration or millisecond integer"
                          {:got x}))))

(defn reap!
  "End conversations whose `:conv/touched` is older than `ttl`.
  `ttl` is a `java.time.Duration` or millisecond integer.  Returns the
  vector of cids that were reaped."
  [ttl]
  (let [ttl       (->duration ttl)
        cutoff    (.minus (Instant/now) ttl)
        expired   (->> @!conversations
                       (keep (fn [[cid c]]
                               (when-let [^Instant touched (:conv/touched c)]
                                 (when (.isBefore touched cutoff)
                                   cid))))
                       vec)]
    (doseq [cid expired]
      (end-conversation! cid))
    expired))

(defn- stop-reaper! []
  (when-let [stop @!reaper-stop]
    (deliver stop true)
    (reset! !reaper-stop nil)))

(defn- start-reaper! [ttl interval]
  (stop-reaper!)
  (when-let [ttl (->duration ttl)]
    (let [interval-ms (max 1 (.toMillis (or (->duration interval)
                                            (Duration/ofMinutes 1))))
          stop        (promise)]
      (reset! !reaper-stop stop)
      (future
        (loop []
          (when-not (deref stop interval-ms false)
            (try
              (reap! ttl)
              (catch Throwable t
                (binding [*out* *err*]
                  (println "dev.zeko.stube.server: reaper threw —" (ex-message t)))))
            (recur)))))))

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

(defn- instance-summary [inst]
  {:id       (:instance/id inst)
   :type     (:instance/type inst)
   :parent   (:instance/parent inst)
   :resume   (:instance/resume inst)
   :children (:instance/children inst)
   :state    (apply dissoc inst conv/instance-meta-keys)})

(defn- conversation-summary [c]
  {:id            (:conv/id c)
   :created       (:conv/created c)
   :touched       (:conv/touched c)
   :ended?        (boolean (:conv/ended? c))
   :history-count (count (:conv/history c))
   :last-event    (:conv/last-event c)
   :stack         (mapv #(instance-summary (conv/instance c %))
                        (:conv/stack c))
   :instances     (into (sorted-map)
                        (map (fn [[iid inst]] [iid (instance-summary inst)]))
                        (:conv/instances c))})

(defn inspect
  "Pretty-print and return a compact summary of live conversation `cid`.
  Returns nil if the conversation is not active."
  [cid]
  (when-let [c (conversation cid)]
    (let [summary (conversation-summary c)]
      (pprint/pprint summary)
      summary)))

(defn ui-css?
  "True when the stock stube stylesheet should be linked from shells."
  []
  @!ui-css?)

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn- build-router
  "Build the reitit router from the current mounts.  The conversation-
  scoped routes are always present; user mounts are appended.  Looked
  up lazily so adding mounts after start works."
  []
  (let [;; Resolved late to avoid a circular require with dev.zeko.stube.http.
        h     (requiring-resolve 'dev.zeko.stube.http/shell-handler)
        sse-h (requiring-resolve 'dev.zeko.stube.http/sse-handler)
        ev-h  (requiring-resolve 'dev.zeko.stube.http/event-handler)
        bk-h  (requiring-resolve 'dev.zeko.stube.http/back-handler)
        css-h (requiring-resolve 'dev.zeko.stube.http/ui-css-handler)]
    (ring/router
      (into [["/stube/ui.css"             {:get  {:handler @css-h}}]
             ["/conv/:cid/sse"            {:get  {:handler @sse-h}}]
             ["/conv/:cid/back"           {:post {:handler @bk-h}}]
             ;; The back route is listed *before* the generic
             ;; `:iid/:event` route so reitit picks the more specific
             ;; one first.
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

(declare stop!)

(defn start!
  "Start http-kit on `port` (default 8080).  Idempotent: a second call
  with the server already running stops the old one first.

  Options:

  | key        | default                       | meaning                               |
  |------------|-------------------------------|---------------------------------------|
  | `:port`    | 8080                          | TCP port                              |
  | `:store`   | `(store/in-memory-store)`     | persistence backend (slice 3)         |
  | `:ui-css?` | true                          | link the stock `/stube/ui.css` file   |
  | `:conversation-ttl` | nil                  | reaper TTL (`Duration` or millis)     |
  | `:reaper-interval` | 60000                 | reaper interval (`Duration` or millis)|

  When a `:store` is supplied, [[load-all]] runs *before* the http
  listener accepts requests, so any persisted conversations are live
  in memory by the time the first browser reconnects."
  ([] (start! {}))
  ([{:keys [port store ui-css? conversation-ttl reaper-interval]
     :or {port 8080 ui-css? true}}]
   (when @!server
     (stop!))
   (reset! !ui-css? (boolean ui-css?))
   (when store
     (reset! !store store)
     (let [restored (store/load-all store)]
       (when (seq restored)
         (println (str "stube: restored " (count restored)
                       " conversations from store")))
       (swap! !conversations merge restored)))
   (let [stop-fn (http-kit/run-server (ring-handler)
                                      {:port                 port
                                       :legacy-return-value? false})]
     (reset! !server (fn [] (http-kit/server-stop! stop-fn)))
     (println (str "stube listening on http://localhost:" port))
     (doseq [[p f] (mounts)]
       (println "  mount" p "→" f))
     (start-reaper! conversation-ttl reaper-interval)
     stop-fn)))

(defn stop!
  "Stop the running server, if any."
  []
  (stop-reaper!)
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
  (reset! !store         (store/in-memory-store))
  (reset! !ui-css?       true)
  nil)

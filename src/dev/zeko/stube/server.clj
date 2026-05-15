(ns dev.zeko.stube.server
  "Live conversation atom + http-kit lifecycle.

  Three atoms live here:

  | atom               | shape                | purpose                                    |
  |--------------------|----------------------|--------------------------------------------|
  | `!conversations`   | `{cid → conv}`       | the live conversation values               |
  | `!sse-sessions`    | `{cid → sse-gen}`    | the open browser connections               |
  | `!mounts`          | `{path → flow-id}`   | the routes registered with [[mount!]]      |

  Timers, pub/sub, and the pending-flow baton moved to
  [[dev.zeko.stube.async]] — this namespace owns conversation storage and
  the http-kit server.  All the http handlers live in
  [[dev.zeko.stube.http]]; this namespace only exposes the functions
  they need, so the http layer never reaches into raw atoms."
  (:require [clojure.pprint                      :as pprint]
            [org.httpkit.server                  :as http-kit]
            [dev.zeko.stube.async                :as async]
            [dev.zeko.stube.conversation         :as conv]
            [dev.zeko.stube.fragments            :as f]
            [dev.zeko.stube.kernel               :as kernel]
            [dev.zeko.stube.render               :as render]
            [dev.zeko.stube.store                :as store])
  (:import (java.time Duration Instant)))

;; ---------------------------------------------------------------------------
;; Storage
;; ---------------------------------------------------------------------------

(defonce ^:private !conversations (atom {}))
(defonce ^:private !sse-sessions  (atom {}))
(defonce ^:private !mounts        (atom (sorted-map)))
(defonce ^:private !server        (atom nil))
(defonce ^:private !ui-css?       (atom true))
(defonce ^:private !halos?        (atom false))
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

(declare dispatch!)

(defn with-kernel-bindings
  "Run `f` with the render cid and async hooks the kernel needs while
  folding effects for `cid`."
  [cid f]
  (binding [render/*cid* cid
            kernel/*schedule-event!* async/schedule-event!
            kernel/*subscribe!* async/subscribe!
            kernel/*unsubscribe!* async/unsubscribe!]
    (f)))

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
     (async/put-pending-flow! cid flow-id)
     cid)))

(def ^{:doc "See [[dev.zeko.stube.async/pending-flow]]."}
  pending-flow async/pending-flow)

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
  (async/cancel-timers! cid)
  (async/remove-subscriptions-for-cid! cid)
  (async/forget-pending-flow! cid)
  (swap! !conversations dissoc cid)
  (swap! !sse-sessions  dissoc cid)
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
;; Pushing fragments and async dispatch
;; ---------------------------------------------------------------------------

(def ^{:doc "Push kernel fragments to an open Datastar SSE generator.
  Re-export of [[dev.zeko.stube.fragments/push!]]."}
  push-fragments! f/push!)

(defn run-effects!
  "Fold `effects` into conversation `cid`, push any fragments, and end
  the conversation if the kernel marks it ended."
  [cid effects]
  (when (conversation cid)
    (let [[conv' frags]
          (with-kernel-bindings
            cid
            #(swap-conv! cid (fn [c] (kernel/run-effects c effects))))]
      (when-let [sse-gen (sse cid)]
        (push-fragments! sse-gen frags))
      (when (:conv/ended? conv')
        (end-conversation! cid))
      [conv' frags])))

(defn dispatch!
  "Dispatch one event into conversation `cid` outside the request path.
  Used by timers, uploads, and publish/subscribe delivery.  Stale cid/iid
  pairs are ignored."
  [cid {:keys [instance-id] :as event}]
  (when-let [live (conversation cid)]
    (when (and (not (:conv/ended? live))
               (conv/instance live instance-id))
      (let [[conv' frags]
            (with-kernel-bindings
              cid
              #(swap-conv! cid (fn [c] (kernel/dispatch c event))))]
        (when-let [sse-gen (sse cid)]
          (push-fragments! sse-gen frags))
        (when (:conv/ended? conv')
          (end-conversation! cid))
        [conv' frags]))))

;; Install our dispatch! into the async layer so timer fires and
;; pub/sub deliveries can route events back into conversations.
(async/install-dispatch! dispatch!)

;; Re-exports of async functions the server's old API surface used to expose.
(def ^{:doc "See [[dev.zeko.stube.async/schedule-event!]]."}
  schedule-event! async/schedule-event!)
(def ^{:doc "See [[dev.zeko.stube.async/subscribe!]]."}
  subscribe! async/subscribe!)
(def ^{:doc "See [[dev.zeko.stube.async/unsubscribe!]]."}
  unsubscribe! async/unsubscribe!)
(def ^{:doc "See [[dev.zeko.stube.async/subscriptions]]."}
  subscriptions async/subscriptions)
(def ^{:doc "See [[dev.zeko.stube.async/publish!]]."}
  publish! async/publish!)

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

(defn halos?
  "True when the server is willing to serve the dev halos overlay.
  Off by default; opt in with `:halos? true` on [[start!]]. Per-conv
  activation also requires `?halos=1` on the shell URL."
  []
  @!halos?)

(defn enable-halos!
  "Set `:conv/halos? true` on the live conversation `cid`, emitting no
  fragments. The shell handler calls this when `?halos=1` is on the URL
  and the server is started with halos enabled."
  [cid]
  (when (and (halos?) (conversation cid))
    (swap-conv! cid (fn [c] [(assoc c :conv/halos? true) []]))
    nil))

(defn enable-halos-and-redraw!
  "Set `:conv/halos? true` on `cid` *and* push a freshly-decorated frame
  to the open SSE so the page picks up halo data-attrs without a hard
  reload.  Called from the http handler that backs the dev-mode pill."
  [cid]
  (when (and (halos?) (conversation cid))
    (let [[_conv' frags]
          (with-kernel-bindings
            cid
            #(swap-conv! cid (fn [c]
                               (kernel/redraw-top
                                 (assoc c :conv/halos? true)))))]
      (when-let [sse-gen (sse cid)]
        (push-fragments! sse-gen frags))
      :enabled)))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(declare stop!)

(defn- ring-handler
  "Resolve [[dev.zeko.stube.routes/ring-handler]] lazily.  The handler
  itself rebuilds the router on every request so mounts added after
  start! are picked up without a restart."
  []
  ((requiring-resolve 'dev.zeko.stube.routes/ring-handler)))

(defn start!
  "Start http-kit on `port` (default 8080).  Idempotent: a second call
  with the server already running stops the old one first.

  Options:

  | key        | default                       | meaning                               |
  |------------|-------------------------------|---------------------------------------|
  | `:port`    | 8080                          | TCP port                              |
  | `:store`   | `(store/in-memory-store)`     | persistence backend (slice 3)         |
  | `:ui-css?` | true                          | link the stock `/stube/ui.css` file   |
  | `:halos?`  | false                         | enable dev halos (per-conv via `?halos=1`) |
  | `:conversation-ttl` | nil                  | reaper TTL (`Duration` or millis)     |
  | `:reaper-interval` | 60000                 | reaper interval (`Duration` or millis)|

  When a `:store` is supplied, [[load-all]] runs *before* the http
  listener accepts requests, so any persisted conversations are live
  in memory by the time the first browser reconnects."
  ([] (start! {}))
  ([{:keys [port store ui-css? halos? conversation-ttl reaper-interval]
     :or {port 8080 ui-css? true halos? false}}]
   (when @!server
     (stop!))
   (reset! !ui-css? (boolean ui-css?))
   (reset! !halos?  (boolean halos?))
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
  (async/reset-state!)
  (reset! !conversations {})
  (reset! !sse-sessions  {})
  (reset! !mounts        (sorted-map))
  (reset! !store         (store/in-memory-store))
  (reset! !ui-css?       true)
  (reset! !halos?        false)
  nil)

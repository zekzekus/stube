(ns dev.zeko.stube.server
  "Standalone http-kit lifecycle around the embeddable stube runtime.

  The mutable conversation/SSE/timer state lives on a kernel value from
  `dev.zeko.stube.embed/make-kernel`.  This namespace keeps the original
  greenfield API (`mount!`, `start!`, `stop!`) as a convenience shell."
  (:require [clojure.pprint              :as pprint]
            [org.httpkit.server          :as http-kit]
            [dev.zeko.stube.adapter.ring :as ring-adapter]
            [dev.zeko.stube.embed        :as embed]
            [dev.zeko.stube.fragments    :as f]
            [dev.zeko.stube.halos        :as halos]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.store        :as store])
  (:import (java.time Duration)))

;; ---------------------------------------------------------------------------
;; Standalone state
;; ---------------------------------------------------------------------------

(defonce ^:private !kernel      (atom nil))
(defonce ^:private !mounts      (atom (sorted-map)))
(defonce ^:private !server      (atom nil))
(defonce ^:private !reaper-stop (atom nil))

(defn- new-kernel [{:keys [store ui-css? halos? app principal-fn]
                    :or {ui-css? true halos? false}}]
  (embed/make-kernel {:store        (or store (store/in-memory-store))
                      :base-path    ""
                      :ui-css?      ui-css?
                      :halos?       halos?
                      :app          app
                      :principal-fn principal-fn}))

(defn default-kernel
  "The kernel instance used by the standalone server API."
  []
  (or @!kernel
      (let [k (new-kernel {})]
        (reset! !kernel k)
        k)))

(defn current-store
  "The store currently in use by the standalone kernel."
  []
  (embed/current-store (default-kernel)))

;; ---------------------------------------------------------------------------
;; Conversation helpers (legacy API wrappers)
;; ---------------------------------------------------------------------------

(defn create-conversation!
  "Mint a fresh conversation for `flow-id` and return its cid."
  ([flow-id]
   (create-conversation! flow-id nil))
  ([flow-id owner-token]
   (embed/create-conversation! (default-kernel) flow-id owner-token)))

(defn pending-flow
  "Pop and return the pending root for `cid`, if any."
  [cid]
  (embed/pending-root (default-kernel) cid))

(defn conversation
  "Snapshot of the named conversation, or nil."
  [cid]
  (embed/conversation (default-kernel) cid))

(defn active-conversations
  "Return a snapshot of all active conversations keyed by cid."
  []
  (embed/active-conversations (default-kernel)))

(defn swap-conv!
  "Atomically apply `(f conv) → [conv' fragments]` to `cid`."
  [cid f]
  (embed/swap-conv! (default-kernel) cid f))

(defn end-conversation!
  "Drop a conversation, its SSE binding, async state, and persisted copy."
  [cid]
  (embed/end-conversation! (default-kernel) cid))

(defn end!
  "Public admin wrapper around [[end-conversation!]]."
  [cid]
  (end-conversation! cid))

(defn reap!
  "End conversations whose `:conv/touched` is older than `ttl`."
  [ttl]
  (embed/reap! (default-kernel) ttl))

(defn with-kernel-bindings
  "Run `f` with render cid and async hooks for the standalone kernel."
  [cid f]
  (embed/with-kernel-bindings (default-kernel) cid f))

;; ---------------------------------------------------------------------------
;; SSE and dispatch helpers
;; ---------------------------------------------------------------------------

(defn register-sse! [cid sse-gen]
  (embed/register-sse! (default-kernel) cid sse-gen))

(defn unregister-sse! [cid]
  (embed/unregister-sse! (default-kernel) cid))

(defn sse [cid]
  (embed/sse (default-kernel) cid))

(def ^{:doc "Push kernel fragments to an open Datastar SSE generator."}
  push-fragments! f/push!)

(defn apply-conv!
  "Apply `(f conv) → [conv' fragments]`, push fragments, and end the
  conversation if the kernel marked it ended."
  [cid f]
  (embed/apply-conv! (default-kernel) cid f))

(defn run-effects!
  "Fold `effects` into conversation `cid` and push any fragments."
  [cid effects]
  (embed/run-effects! (default-kernel) cid effects))

(defn dispatch!
  "Dispatch one event into conversation `cid`."
  [cid event]
  (embed/dispatch! (default-kernel) cid event))

(defn schedule-event! [event]
  (embed/schedule-event! (default-kernel) event))

(defn subscribe! [sub]
  (embed/subscribe! (default-kernel) sub))

(defn unsubscribe! [sub]
  (embed/unsubscribe! (default-kernel) sub))

(defn subscriptions []
  (embed/subscriptions (default-kernel)))

(defn publish! [topic msg]
  (embed/publish! (or kernel/*current-kernel* (default-kernel)) topic msg))

;; ---------------------------------------------------------------------------
;; Mounts and dev tooling
;; ---------------------------------------------------------------------------

(defn mount!
  "Register a flow at a URL path.  After [[start!]], `GET <path>` will
  serve the shell page and pre-bind the resulting conversation to the
  named flow.

  Optional `opts` map is forwarded to the shell handler:

  * `:init-args-fn` — `(fn [request] init-args-map)`.  Extracts init-args
    from the GET request (e.g. query params) to seed the component's initial
    state.  Example:

        (s/mount! \"/counter\" :demo/counter
          {:init-args-fn (fn [req]
                           {:n (parse-long (or (s/query-value req \"n\") \"0\"))})})"
  ([path flow-id]
   (mount! path flow-id {}))
  ([path flow-id opts]
   (when-not (string? path)
     (throw (ex-info "mount! path must be a string" {:got path})))
   (when-not (qualified-keyword? flow-id)
     (throw (ex-info "mount! flow-id must be a namespaced keyword"
                     {:got flow-id})))
   (swap! !mounts assoc path {:flow-id flow-id :opts (or opts {})})
   nil))

(defn unmount! [path]
  (swap! !mounts dissoc path)
  nil)

(defn mounts [] @!mounts)

(defn inspect
  "Pretty-print and return a compact summary of live conversation `cid`."
  [cid]
  (when-let [c (conversation cid)]
    (let [summary (halos/inspect-summary c)]
      (pprint/pprint summary)
      summary)))

(defn ui-css?
  "True when the stock stube stylesheet should be linked from shells."
  []
  (embed/ui-css? (default-kernel)))

(defn halos?
  "True when the standalone server is willing to serve dev halos."
  []
  (embed/halos? (default-kernel)))

(defn enable-halos! [cid]
  (embed/enable-halos! (default-kernel) cid))

(defn enable-halos-and-redraw! [cid]
  (embed/enable-halos-and-redraw! (default-kernel) cid))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(declare stop!)

(defn- ->duration [x]
  (cond
    (nil? x) nil
    (instance? Duration x) x
    (integer? x) (Duration/ofMillis x)
    :else (throw (ex-info "Expected java.time.Duration or millisecond integer"
                          {:got x}))))

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

(defn- ring-handler []
  (ring-adapter/ring-handler (default-kernel) {:mounts-fn mounts}))

(defn start!
  "Start http-kit on `port` (default 8080).  Idempotent: a second call
  with the server already running stops the old one first.

  Accepts the embedder options `:app` and `:principal-fn` and forwards
  them to the underlying kernel.  See
  [[dev.zeko.stube.embed/make-kernel]] for the full set."
  ([] (start! {}))
  ([{:keys [port store ui-css? halos? app principal-fn
            conversation-ttl reaper-interval]
     :or {port 8080 ui-css? true halos? false}}]
   (when @!server
     (stop!))
   (when-let [old @!kernel]
     (embed/halt! old))
   (let [k (new-kernel {:store        store
                        :ui-css?      ui-css?
                        :halos?       halos?
                        :app          app
                        :principal-fn principal-fn})]
     (reset! !kernel k)
     (when (and store (seq (embed/active-conversations k)))
       (println (str "stube: restored " (count (embed/active-conversations k))
                     " conversations from store"))))
   (let [stop-fn (http-kit/run-server (ring-handler)
                                      {:port                 port
                                       :legacy-return-value? false})]
     (reset! !server (fn [] (http-kit/server-stop! stop-fn)))
     (println (str "stube listening on http://localhost:" port))
     (doseq [[p {:keys [flow-id]}] (mounts)]
       (println "  mount" p "→" flow-id))
     (start-reaper! conversation-ttl reaper-interval)
     stop-fn)))

(defn stop!
  "Stop the running server, if any.  Runs the kernel shutdown sequence
  ahead of closing the http-kit listener so `:stop` hooks fire, open
  SSE streams receive a final `:close`, and the store is flushed
  before the JVM exits."
  []
  (stop-reaper!)
  (when-let [k @!kernel]
    (embed/halt! k))
  (when-let [stop-fn @!server]
    (stop-fn)
    (reset! !server nil)))

;; ---------------------------------------------------------------------------
;; Test / REPL helpers
;; ---------------------------------------------------------------------------

(defn reset-state!
  "Wipe all standalone in-memory state.  Intended for tests and REPL iteration."
  []
  (stop!)
  (when-let [k @!kernel]
    (embed/halt! k))
  (reset! !kernel (new-kernel {}))
  (reset! !mounts (sorted-map))
  nil)

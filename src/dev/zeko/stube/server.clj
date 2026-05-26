(ns dev.zeko.stube.server
  "Standalone http-kit lifecycle around a default stube kernel.

  Two concerns live here:

  * The lifecycle: `start!`, `stop!`, `mount!`, `unmount!`, plus the
    per-conversation reaper that runs alongside the listener.
  * The default-kernel convenience surface: `default-kernel`,
    `conversation`, `active-conversations`, `end!`, `publish!`,
    `inspect`.  These all delegate to `dev.zeko.stube.runtime` with
    the process-global kernel value.

  Adapters and tests that already have a kernel in hand should call
  [[dev.zeko.stube.runtime]] directly — this namespace is for the
  greenfield `(s/start!)` + `(s/mount!)` workflow.

  The mutable conversation/SSE/timer state lives on the kernel value
  returned by [[dev.zeko.stube.embed/make-kernel]] (a thin public
  facade over [[dev.zeko.stube.runtime/make-kernel]])."
  (:require [clojure.pprint              :as pprint]
            [org.httpkit.server          :as http-kit]
            [dev.zeko.stube.adapter.ring :as ring-adapter]
            [dev.zeko.stube.fragments    :as f]
            [dev.zeko.stube.halos        :as halos]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.runtime      :as rt]
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
  (rt/make-kernel {:store        (or store (store/in-memory-store))
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

;; ---------------------------------------------------------------------------
;; Default-kernel convenience surface
;; ---------------------------------------------------------------------------
;;
;; Each function delegates to `dev.zeko.stube.runtime` with the
;; standalone default kernel.  Hosts that hold their own kernel value
;; should call runtime directly; these exist so the
;; `(s/start!)` + `(s/mount!)` style flow doesn't have to thread the
;; kernel through every call site.

(defn conversation
  "Snapshot of the named conversation in the standalone kernel, or nil."
  [cid]
  (rt/conversation (default-kernel) cid))

(defn active-conversations
  "Return a snapshot of all active conversations keyed by cid."
  []
  (rt/active-conversations (default-kernel)))

(defn end!
  "Drop a conversation, its SSE binding, async state, and persisted copy."
  [cid]
  (rt/end-conversation! (default-kernel) cid))

(defn publish!
  "Publish `msg` to every live instance subscribed to `topic`.  From
  inside a component dispatch this targets the active runtime kernel;
  outside a dispatch it falls back to the standalone default kernel."
  [topic msg]
  (rt/publish! (or kernel/*current-kernel* (default-kernel)) topic msg))

(def ^{:doc "Push kernel fragments to an open Datastar SSE generator."}
  push-fragments! f/push!)

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
              (rt/reap! (default-kernel) ttl)
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
     (rt/halt! old))
   (let [k (new-kernel {:store        store
                        :ui-css?      ui-css?
                        :halos?       halos?
                        :app          app
                        :principal-fn principal-fn})]
     (reset! !kernel k)
     (when (and store (seq (rt/active-conversations k)))
       (println (str "stube: restored " (count (rt/active-conversations k))
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
    (rt/halt! k))
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
    (rt/halt! k))
  (reset! !kernel (new-kernel {}))
  (reset! !mounts (sorted-map))
  nil)

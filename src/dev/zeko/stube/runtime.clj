(ns dev.zeko.stube.runtime
  "Embeddable stube runtime instances.

  `dev.zeko.stube.kernel` remains the pure effect fold.  This namespace
  holds the small amount of mutable runtime state needed to embed that
  fold in a host Ring app: live conversations, SSE channels, timers,
  subscriptions, and the pending-root baton between shell render and
  first SSE attach."
  (:require [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.errors       :as errors]
            [dev.zeko.stube.fragments    :as f]
            [dev.zeko.stube.kernel       :as pure]
            [dev.zeko.stube.lifecycle    :as lc]
            [dev.zeko.stube.render       :as render]
            [dev.zeko.stube.session      :as session]
            [dev.zeko.stube.shell        :as shell]
            [dev.zeko.stube.store        :as store])
  (:import (java.time Duration Instant)
           (java.util UUID)))

(declare dispatch! schedule-event! stop-keepalive! subscribe! unsubscribe!)

;; ---------------------------------------------------------------------------
;; Kernel values
;; ---------------------------------------------------------------------------

(defn- normalize-base-path [base-path]
  (let [base (or base-path "")]
    (cond
      (or (= base "") (= base "/")) ""
      (.endsWith base "/") (subs base 0 (dec (count base)))
      :else base)))

(defn- default-options [opts]
  (let [custom-session? (contains? opts :session-id-fn)]
    (merge {:context-fn        (constantly nil)
            :app               nil
            :principal-fn      nil
            :store             (store/in-memory-store)
            :base-path         ""
            :route-style       :adapter
            :root-selector     "#root"
            :session-id-fn     session/request-session
            :ensure-session-fn (when-not custom-session?
                                 session/ensure-session)
            :on-conv-mint      (fn [conv _request] conv)
            :on-error          nil
            :ui-css?           true
            :halos?            false
            ;; SSE comment-frame heartbeat that keeps reverse-proxy idle
            ;; timers happy.  15s sits under the common 30/60s thresholds
            ;; (nginx, ALB).  Set to nil or 0 to disable.
            :sse-keepalive-ms  15000}
           opts)))

(defn make-kernel
  "Create an embeddable stube runtime instance.

  Stable embedder options:

  * `:context-fn` — `(fn [request] ctx)` stored on each conversation and
    readable in handlers with `(s/context self)`.
  * `:app` — opaque host value (typically a small map of dependencies
    such as `{:db ds :mail mailer}`).  Read from component code with
    `(s/app)`.  Not persisted; rebuild from live JVM state on each
    `make-kernel` call.
  * `:principal-fn` — `(fn [request] principal-or-nil)` invoked once at
    mint time.  Result is persisted on the conversation as
    `:conv/principal`; component code reads it with `(s/principal)`.
    Re-authentication is the host's job — if the principal needs to
    change, end the conversation and re-mint.
  * `:store` — persistence backend from `dev.zeko.stube.store`.
  * `:base-path` — URL prefix used by shell/render helpers.
  * `:session-id-fn` — host session lookup; defaults to `stube_sid`.
  * `:on-conv-mint` — optional `(fn [conv request] conv')` hook.
  * `:on-error` — reserved hook for adapter error reporting.
  * `:sse-keepalive-ms` — interval in milliseconds for the SSE
    heartbeat that keeps reverse-proxy idle timers happy.  Defaults
    to 15000.  Set to nil or 0 to disable (e.g. when the host's proxy
    has no idle timeout, or in tests)."
  ([]
   (make-kernel {}))
  ([opts]
   (let [{:keys [store base-path] :as opts} (default-options opts)
         restored (store/load-all store)]
     (assoc opts
            :id (str (UUID/randomUUID))
            :base-path (normalize-base-path base-path)
            :!conversations  (atom restored)
            :!sse-sessions   (atom {})
            :!sse-keepalive  (atom {})
            :!pending-roots  (atom {})
            :!timers         (atom {})
            :!subscriptions  (atom {})
            ;; Per-cid monitor objects.  `swap-conv!` locks on the
            ;; matching monitor so the dispatch function runs exactly
            ;; once even when concurrent events race on the same
            ;; conversation.  Without this, `swap!`'s retry semantics
            ;; would re-run handlers (and their side effects: spawned
            ;; futures, publish calls, subscribe atoms) more than once,
            ;; causing fan-out amplification.
            :!cid-locks      (atom {})
            :!shutting-down? (atom false)))))

(defn current-store [k] (:store k))
(defn base-path [k] (:base-path k))
(defn route-style [k] (:route-style k))
(defn root-selector [k] (:root-selector k))
(defn ui-css? [k] (boolean (:ui-css? k)))
(defn halos? [k] (boolean (:halos? k)))

(defn shutting-down?
  "True once [[halt!]] has begun the shutdown sequence for `k`.  HTTP
  adapters should refuse new conversation mints (typically 503) while
  this is true."
  [k]
  (boolean (some-> (:!shutting-down? k) deref)))

(defn ensure-session
  "Return `[sid set-cookie-header-or-nil]` for `request` under kernel
  `k`.  Kernels with a custom `:session-id-fn` default to host-managed
  sessions and do not mint a stube cookie."
  [k request]
  (if-let [ensure (:ensure-session-fn k)]
    (ensure request)
    [((:session-id-fn k) request) nil]))

(defn authorized?
  "True when `request` owns conversation `cid` under kernel `k`."
  [k request cid]
  (let [conv  (get @(:!conversations k) cid)
        owner (:conv/owner-token conv)]
    (or (nil? owner)
        (= owner ((:session-id-fn k) request)))))

(defn with-kernel-bindings
  "Run `f` with render URL context and async hooks for kernel `k`."
  [k cid f]
  (let [principal (get-in @(:!conversations k) [cid :conv/principal])]
    (binding [render/*cid* cid
              render/*base-path* (:base-path k)
              render/*route-style* (:route-style k)
              render/*root-selector* (:root-selector k)
              pure/*current-kernel* k
              pure/*current-app* (:app k)
              pure/*current-principal* principal
              pure/*schedule-event!* #(schedule-event! k %)
              pure/*subscribe!* #(subscribe! k %)
              pure/*unsubscribe!* #(unsubscribe! k %)
              pure/*run-io!* #(future
                                (try (%)
                                     (catch Throwable t
                                       (binding [*out* *err*]
                                         (println "dev.zeko.stube.runtime: :io effect threw —"
                                                  (ex-message t))))))
              errors/*on-error* (:on-error k)]
      (f))))

(defn- with-render-bindings [k cid f]
  (let [principal (get-in @(:!conversations k) [cid :conv/principal])]
    (binding [render/*cid* cid
              render/*base-path* (:base-path k)
              render/*route-style* (:route-style k)
              render/*root-selector* (:root-selector k)
              pure/*current-app* (:app k)
              pure/*current-principal* principal]
      (f))))

;; ---------------------------------------------------------------------------
;; Conversation lifecycle
;; ---------------------------------------------------------------------------

(defn- put-pending-root! [k cid root]
  (swap! (:!pending-roots k) assoc cid root)
  nil)

(defn pending-root
  "Pop and return the root embed/flow for `cid`, if any."
  [k cid]
  (let [[old _] (swap-vals! (:!pending-roots k) dissoc cid)]
    (get old cid)))

(defn- forget-pending-root! [k cid]
  (swap! (:!pending-roots k) dissoc cid)
  nil)

(defn conversation
  "Snapshot of conversation `cid` in kernel `k`, or nil."
  [k cid]
  (get @(:!conversations k) cid))

(defn active-conversations
  "Snapshot of all active conversations in kernel `k`."
  [k]
  @(:!conversations k))

(defn- context-for [k request]
  ((:context-fn k) request))

(defn- principal-for [k request]
  (when-let [f (:principal-fn k)] (f request)))

(defn- install-conversation! [k root-id init-args request owner-token]
  (let [ctx       (context-for k request)
        principal (principal-for k request)
        conv (cond-> (conv/new-conversation)
               owner-token       (assoc :conv/owner-token owner-token)
               (some? ctx)       (assoc :conv/context ctx)
               (some? principal) (assoc :conv/principal principal))
        conv ((:on-conv-mint k) conv request)
        cid  (:conv/id conv)]
    (swap! (:!conversations k) assoc cid conv)
    (put-pending-root! k cid (conv/embed root-id (or init-args {})))
    cid))

(defn create-conversation!
  "Compatibility helper for standalone server code that already resolved
  the owner token."
  ([k root-id]
   (create-conversation! k root-id nil))
  ([k root-id owner-token]
   (install-conversation! k root-id {} nil owner-token)))

(defn mint-conversation!
  "Register a new conversation for `root-id` and return its cid."
  ([k root-id request]
   (mint-conversation! k root-id {} request))
  ([k root-id init-args request]
   (let [[sid _set-cookie] (ensure-session k request)]
     (install-conversation! k root-id init-args request sid))))

(defn- cid-lock
  "Return (and lazily mint) the per-cid monitor object for `cid`.  Locking
  on this serialises every `swap-conv!` for `cid` so the dispatch
  function (which has side effects: spawned futures, publish/subscribe
  mutations) runs exactly once."
  [k cid]
  (or (get @(:!cid-locks k) cid)
      (-> (swap! (:!cid-locks k)
                 (fn [m]
                   (if (contains? m cid) m (assoc m cid (Object.)))))
          (get cid))))

(defn swap-conv!
  "Apply `(f conv) → [conv' fragments]` to conversation `cid` under the
  per-cid lock, then atomically commit `conv'`.  `f` is called exactly
  once — unlike a bare `swap!` whose retry semantics would re-run `f`
  (and its side effects) under contention."
  [k cid f]
  (locking (cid-lock k cid)
    (when-let [c (get @(:!conversations k) cid)]
      (let [pair     (f c)
            [c' _fr] pair]
        (swap! (:!conversations k) assoc cid c')
        (try
          (store/save! (:store k) c')
          (catch Throwable t
            (binding [*out* *err*]
              (println "dev.zeko.stube.runtime: store save! threw —" (ex-message t)))))
        pair))))

(defn- cancel-timers! [k cid]
  (doseq [f (get @(:!timers k) cid)]
    (future-cancel f))
  (swap! (:!timers k) dissoc cid)
  nil)

(defn remove-subscriptions-for-cid! [k cid]
  (swap! (:!subscriptions k)
         (fn [topics]
           (into {}
                 (keep (fn [[topic subscribers]]
                         (let [subscribers' (into {}
                                                  (remove (fn [[[sub-cid _iid] _event]]
                                                            (= sub-cid cid)))
                                                  subscribers)]
                           (when (seq subscribers')
                             [topic subscribers']))))
                 topics)))
  nil)

(defn end-conversation!
  "Drop a conversation, any SSE binding, pending root, timers,
  subscriptions, and persisted copy."
  [k cid]
  (cancel-timers! k cid)
  (stop-keepalive! k cid)
  (remove-subscriptions-for-cid! k cid)
  (forget-pending-root! k cid)
  (swap! (:!conversations k) dissoc cid)
  (swap! (:!sse-sessions k) dissoc cid)
  (swap! (:!cid-locks k) dissoc cid)
  (try
    (store/delete! (:store k) cid)
    (catch Throwable t
      (binding [*out* *err*]
        (println "dev.zeko.stube.runtime: store delete! threw —" (ex-message t)))))
  nil)

(defn end! [k cid] (end-conversation! k cid))

(defn- ->duration [x]
  (cond
    (nil? x) nil
    (instance? Duration x) x
    (integer? x) (Duration/ofMillis x)
    :else (throw (ex-info "Expected java.time.Duration or millisecond integer"
                          {:got x}))))

(defn reap!
  "End conversations whose `:conv/touched` is older than `ttl`."
  [k ttl]
  (let [ttl     (->duration ttl)
        cutoff  (.minus (Instant/now) ttl)
        expired (->> @(:!conversations k)
                     (keep (fn [[cid c]]
                             (when-let [^Instant touched (:conv/touched c)]
                               (when (.isBefore touched cutoff)
                                 cid))))
                     vec)]
    (doseq [cid expired]
      (end-conversation! k cid))
    expired))

;; ---------------------------------------------------------------------------
;; SSE and dispatch
;; ---------------------------------------------------------------------------

(defn- start-keepalive!
  "Start a daemon Thread that pings `sse-gen` every `interval-ms` until
  either the channel rejects the write or the thread is interrupted.
  Returns the Thread so the caller can stash it for cancellation."
  [sse-gen interval-ms]
  (let [^Thread t (Thread.
                    ^Runnable
                    (fn []
                      (try
                        (loop []
                          (Thread/sleep ^long interval-ms)
                          (when (f/push-keep-alive! sse-gen)
                            (recur)))
                        (catch InterruptedException _ nil)
                        (catch Throwable _ nil)))
                    "stube-sse-keepalive")]
    (.setDaemon t true)
    (.start t)
    t))

(defn- stop-keepalive! [k cid]
  (when-let [^Thread t (get @(:!sse-keepalive k) cid)]
    (.interrupt t)
    (swap! (:!sse-keepalive k) dissoc cid)))

(defn register-sse! [k cid sse-gen]
  (swap! (:!sse-sessions k) assoc cid sse-gen)
  (when-let [ms (:sse-keepalive-ms k)]
    (when (pos? ms)
      (stop-keepalive! k cid)
      (swap! (:!sse-keepalive k) assoc cid (start-keepalive! sse-gen ms)))))

(defn unregister-sse! [k cid]
  (stop-keepalive! k cid)
  (swap! (:!sse-sessions k) dissoc cid))

(defn sse [k cid]
  (get @(:!sse-sessions k) cid))

(def push-fragments! f/push!)

(defn apply-conv!
  "Apply `(f conv) → [conv' fragments]`, push fragments over SSE, and
  end the conversation if the kernel marked it ended."
  [k cid f]
  (when (conversation k cid)
    (let [[conv' frags] (with-kernel-bindings
                          k cid
                          #(swap-conv! k cid f))]
      (when-let [sse-gen (sse k cid)]
        (push-fragments! sse-gen frags))
      (when (:conv/ended? conv')
        (end-conversation! k cid))
      [conv' frags])))

(defn run-effects! [k cid effects]
  (apply-conv! k cid (fn [c] (pure/run-effects c effects))))

(defn dispatch!
  "Dispatch one event into a live conversation and return the fragments
  produced.  Also pushes those fragments to an open SSE stream."
  [k cid {:keys [instance-id] :as event}]
  (when-let [live (conversation k cid)]
    (when (and (not (:conv/ended? live))
               (conv/instance live instance-id))
      (second (apply-conv! k cid (fn [c] (pure/dispatch c event)))))))

(defn shell-for
  "Return the embeddable Hiccup shell fragment for conversation `cid`."
  [k cid]
  (shell/fragment cid {:dev? (halos? k)
                       :base-path (:base-path k)
                       :route-style (:route-style k)
                       :root-selector (:root-selector k)}))

(defn head-tags
  "Return Hiccup head nodes required by [[shell-for]] for kernel `k`."
  [k]
  (shell/head-tags {:dev? (halos? k)
                    :ui-css? (ui-css? k)
                    :base-path (:base-path k)
                    :route-style (:route-style k)
                    :root-selector (:root-selector k)}))

;; ---------------------------------------------------------------------------
;; Per-kernel timers and pub/sub
;; ---------------------------------------------------------------------------

(def ^:private no-payload ::no-payload)

(defn- route-event->event-map [iid route-event]
  (let [{:keys [event payload]}
        (if (vector? route-event)
          (let [[event & payloads] route-event]
            {:event event
             :payload (case (count payloads)
                        0 no-payload
                        1 (first payloads)
                        (vec payloads))})
          {:event route-event
           :payload no-payload})]
    (cond-> {:instance-id iid
             :event       event
             :signals     {}}
      (not= no-payload payload) (assoc :payload payload))))

(defn- forget-timer! [k cid f]
  (swap! (:!timers k) update cid
         (fn [fs]
           (let [fs' (disj (or fs #{}) f)]
             (when (seq fs') fs'))))
  nil)

(defn schedule-event!
  "Schedule a future event for a cid/iid within kernel `k`."
  [k {:keys [cid instance-id delay-ms event]}]
  (let [!self (atom nil)
        fut   (future
                (try
                  (Thread/sleep (max 0 (long delay-ms)))
                  (dispatch! k cid (route-event->event-map instance-id event))
                  (catch InterruptedException _ nil)
                  (catch Throwable t
                    (binding [*out* *err*]
                      (println "dev.zeko.stube.runtime: scheduled event threw —" (ex-message t))))
                  (finally
                    (when-let [self @!self]
                      (forget-timer! k cid self)))))]
    (reset! !self fut)
    (swap! (:!timers k) update cid (fnil conj #{}) fut)
    fut))

(defn subscribe!
  "Subscribe cid/iid to `topic` within kernel `k`."
  [k {:keys [cid instance-id topic event]}]
  (swap! (:!subscriptions k) assoc-in [topic [cid instance-id]] event)
  nil)

(defn unsubscribe!
  "Remove one cid/iid subscription.  If `topic` is nil, remove all of
  the instance's subscriptions."
  [k {:keys [cid instance-id topic]}]
  (let [sub-key [cid instance-id]]
    (swap! (:!subscriptions k)
           (fn [topics]
             (into {}
                   (keep (fn [[t subscribers]]
                           (let [subscribers' (if (or (nil? topic) (= topic t))
                                                (dissoc subscribers sub-key)
                                                subscribers)]
                             (when (seq subscribers')
                               [t subscribers']))))
                   topics))))
  nil)

(defn subscriptions [k]
  @(:!subscriptions k))

(defn- published-event-map [iid route-event payload]
  {:instance-id iid
   :event       (if (vector? route-event) (first route-event) route-event)
   :payload     payload
   :signals     {}})

(defn publish!
  "Asynchronously deliver `msg` to every live subscriber of `topic` in
  kernel `k`."
  [k topic msg]
  (let [targets (get @(:!subscriptions k) topic)]
    (doseq [[[cid iid] route-event] targets]
      (future
        (dispatch! k cid (published-event-map iid route-event msg))))
    (count targets)))

;; ---------------------------------------------------------------------------
;; Dev tooling helpers
;; ---------------------------------------------------------------------------

(defn enable-halos! [k cid]
  (when (and (halos? k) (conversation k cid))
    (swap-conv! k cid (fn [c] [(assoc c :conv/halos? true) []]))
    nil))

(defn enable-halos-and-redraw! [k cid]
  (when (and (halos? k) (conversation k cid))
    (let [[_conv' frags]
          (with-kernel-bindings
            k cid
            #(swap-conv! k cid (fn [c]
                                 (pure/redraw-top
                                   (assoc c :conv/halos? true)))))]
      (when-let [sse-gen (sse k cid)]
        (push-fragments! sse-gen frags))
      :enabled)))

;; ---------------------------------------------------------------------------
;; Pure replay against a kernel configuration
;; ---------------------------------------------------------------------------

(defn- replay-event [conv event]
  (let [event (if (fn? event) (event conv) event)]
    (cond-> event
      (nil? (:instance-id event)) (assoc :instance-id (conv/top-id conv))
      (nil? (:signals event))     (assoc :signals {}))))

(defn replay-with
  "Purely replay `events` against a fresh conversation rooted at
  `root-id`, using `k`'s render configuration but mutating no runtime
  state.  See [[dev.zeko.stube.embed/replay-with]] for the public-facing
  forwarder."
  [k root-id events]
  (let [ctx (context-for k nil)
        c0  (cond-> (conv/new-conversation)
              (some? ctx) (assoc :conv/context ctx))]
    (binding [render/*base-path* (:base-path k)
              render/*route-style* (:route-style k)
              render/*root-selector* (:root-selector k)
              pure/*run-io!* nil]
      (let [[booted boot-frags]
            (with-render-bindings
              k (:conv/id c0)
              #(pure/run-effects c0 (pure/boot root-id)))]
        (reduce (fn [[c frags] event]
                  (let [[c' more] (with-render-bindings
                                    k (:conv/id c)
                                    #(pure/dispatch c (replay-event c event)))]
                    [c' (into frags more)]))
                [booted (vec boot-frags)]
                events)))))

(defn- shutdown-stop-iids
  "Order in which `:stop` should fire across a conversation's live
  instances during shutdown: top-of-stack first, children before their
  own frame.  Pre-existing `:end` handling uses parent-first
  pop-style; here the issue spec asks for children-before-parents, so
  the per-frame descendant list is reversed before concatenating."
  [conv]
  (vec
    (mapcat (fn [frame-iid]
              (rseq (conv/descendant-ids conv frame-iid)))
            (rseq (or (:conv/stack conv) [])))))

(defn- run-shutdown-stop-hooks! [k]
  ;; Run :stop through apply-conv! so any patches it emits go out over
  ;; SSE before the :close fragment.  Do NOT set :conv/ended? here:
  ;; apply-conv!'s end-conversation! path would `delete!` from the
  ;; store, defeating the final flush-store! step.
  (doseq [[cid _conv] @(:!conversations k)]
    (try
      (apply-conv! k cid
        (fn [c]
          (let [iids (shutdown-stop-iids c)]
            (if (seq iids)
              (lc/run-stop-hooks pure/run-effects c iids)
              [c []]))))
      (catch Throwable t
        (binding [*out* *err*]
          (println "stube halt!: :stop hook for" cid "threw —" (ex-message t)))))))

(defn- flush-store! [k]
  (doseq [[_cid conv] @(:!conversations k)]
    (try
      (store/save! (:store k) conv)
      (catch Throwable t
        (binding [*out* *err*]
          (println "stube halt!: final save! threw —" (ex-message t)))))))

(defn halt!
  "Drain a kernel.  Sequence:

    1. Mark the kernel as shutting down so HTTP adapters can refuse
       new conversation mints.
    2. Cancel pending scheduled events.
    3. Run `:stop` hooks for every live instance (children before
       their frame, top stack frame first).
    4. Drain open SSE streams with a final `:close` fragment.
    5. Flush the store with one last `save!` per conversation.
    6. Clear per-kernel runtime registries.

  Returns nil.  Idempotent: subsequent calls on the same kernel are
  cheap no-ops."
  [k]
  (when (compare-and-set! (:!shutting-down? k) false true)
    (doseq [cid (keys @(:!timers k))]
      (cancel-timers! k cid))
    (doseq [cid (keys @(:!sse-keepalive k))]
      (stop-keepalive! k cid))
    (run-shutdown-stop-hooks! k)
    (doseq [[_cid sse-gen] @(:!sse-sessions k)]
      (try
        (push-fragments! sse-gen [f/close])
        (catch Throwable _ nil)))
    (flush-store! k)
    (reset! (:!sse-sessions k) {})
    (reset! (:!sse-keepalive k) {})
    (reset! (:!pending-roots k) {})
    (reset! (:!subscriptions k) {})
    (reset! (:!cid-locks k) {})
    (reset! (:!conversations k) {}))
  nil)

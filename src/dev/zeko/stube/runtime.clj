(ns dev.zeko.stube.runtime
  "Embeddable stube runtime instances.

  `dev.zeko.stube.kernel` remains the pure effect fold.  This namespace
  holds the small amount of mutable runtime state needed to embed that
  fold in a host Ring app: live conversations, SSE channels, timers,
  subscriptions, and the pending-root baton between shell render and
  first SSE attach."
  (:require [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.fragments    :as f]
            [dev.zeko.stube.kernel       :as pure]
            [dev.zeko.stube.render       :as render]
            [dev.zeko.stube.session      :as session]
            [dev.zeko.stube.shell        :as shell]
            [dev.zeko.stube.store        :as store])
  (:import (java.time Duration Instant)
           (java.util UUID)))

(declare dispatch! schedule-event! subscribe! unsubscribe!)

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
    (merge {:context-fn       (constantly nil)
            :store            (store/in-memory-store)
            :base-path        ""
            :route-style      :adapter
            :root-selector    "#root"
            :session-id-fn    session/request-session
            :ensure-session-fn (when-not custom-session?
                                 session/ensure-session)
            :on-conv-mint     (fn [conv _request] conv)
            :on-error         nil
            :ui-css?          true
            :halos?           false}
           opts)))

(defn make-kernel
  "Create an embeddable stube runtime instance.

  Stable embedder options:

  * `:context-fn` — `(fn [request] ctx)` stored on each conversation and
    readable in handlers with `(s/context self)`.
  * `:store` — persistence backend from `dev.zeko.stube.store`.
  * `:base-path` — URL prefix used by shell/render helpers.
  * `:session-id-fn` — host session lookup; defaults to `stube_sid`.
  * `:on-conv-mint` — optional `(fn [conv request] conv')` hook.
  * `:on-error` — reserved hook for adapter error reporting."
  ([]
   (make-kernel {}))
  ([opts]
   (let [{:keys [store base-path] :as opts} (default-options opts)
         restored (store/load-all store)]
     (assoc opts
            :id (str (UUID/randomUUID))
            :base-path (normalize-base-path base-path)
            :!conversations (atom restored)
            :!sse-sessions  (atom {})
            :!pending-roots (atom {})
            :!timers        (atom {})
            :!subscriptions (atom {})))))

(defn current-store [k] (:store k))
(defn base-path [k] (:base-path k))
(defn route-style [k] (:route-style k))
(defn root-selector [k] (:root-selector k))
(defn ui-css? [k] (boolean (:ui-css? k)))
(defn halos? [k] (boolean (:halos? k)))

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
  (binding [render/*cid* cid
            render/*base-path* (:base-path k)
            render/*route-style* (:route-style k)
            render/*root-selector* (:root-selector k)
            pure/*schedule-event!* #(schedule-event! k %)
            pure/*subscribe!* #(subscribe! k %)
            pure/*unsubscribe!* #(unsubscribe! k %)]
    (f)))

(defn- with-render-bindings [k cid f]
  (binding [render/*cid* cid
            render/*base-path* (:base-path k)
            render/*route-style* (:route-style k)
            render/*root-selector* (:root-selector k)]
    (f)))

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

(defn- install-conversation! [k root-id init-args request owner-token]
  (let [ctx  (context-for k request)
        conv (cond-> (conv/new-conversation)
               owner-token (assoc :conv/owner-token owner-token)
               (some? ctx) (assoc :conv/context ctx))
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

(defn swap-conv!
  "Atomically apply `(f conv) → [conv' fragments]` to conversation `cid`."
  [k cid f]
  (let [box (volatile! nil)]
    (swap! (:!conversations k)
           (fn [m]
             (if-let [c (get m cid)]
               (let [pair     (f c)
                     [c' _fr] pair]
                 (vreset! box pair)
                 (assoc m cid c'))
               m)))
    (when-let [[c' _] @box]
      (try
        (store/save! (:store k) c')
        (catch Throwable t
          (binding [*out* *err*]
            (println "dev.zeko.stube.runtime: store save! threw —" (ex-message t))))))
    @box))

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
  (remove-subscriptions-for-cid! k cid)
  (forget-pending-root! k cid)
  (swap! (:!conversations k) dissoc cid)
  (swap! (:!sse-sessions k) dissoc cid)
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

(defn register-sse! [k cid sse-gen]
  (swap! (:!sse-sessions k) assoc cid sse-gen))

(defn unregister-sse! [k cid]
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

(defn replay
  "Purely replay `events` against a fresh conversation rooted at
  `root-id`.  Kernel state is not read or mutated."
  [k root-id events]
  (let [ctx (context-for k nil)
        c0  (cond-> (conv/new-conversation)
              (some? ctx) (assoc :conv/context ctx))]
    (binding [render/*base-path* (:base-path k)
              render/*route-style* (:route-style k)
              render/*root-selector* (:root-selector k)]
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

(defn halt!
  "Close open SSE streams and clear per-kernel runtime registries."
  [k]
  (doseq [[_cid sse-gen] @(:!sse-sessions k)]
    (try
      (push-fragments! sse-gen [f/close])
      (catch Throwable _ nil)))
  (doseq [cid (keys @(:!timers k))]
    (cancel-timers! k cid))
  (reset! (:!sse-sessions k) {})
  (reset! (:!pending-roots k) {})
  (reset! (:!subscriptions k) {})
  nil)

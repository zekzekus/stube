(ns dev.zeko.stube.async
  "Side-effecting registries that live in parallel to the conversation
  atom: timers, pub/sub, and the pending-flow baton.

  Three independent state shapes:

  | atom            | shape                          | role                           |
  |-----------------|--------------------------------|--------------------------------|
  | `!pending-flows`| `{cid → flow-id}`              | one-shot baton between mount   |
  |                 |                                | and first SSE connect          |
  | `!timers`       | `{cid → #{future}}`            | scheduled future-fired events  |
  | `!subscriptions`| `{topic → {[cid iid] event}}`  | live pub/sub routing           |

  Delivery — both timer fires and `publish!` — calls back into a
  conversation via a dispatch function the server installs at startup
  with [[install-dispatch!]].  Keeping the dependency one-way (server
  requires async, async never requires server) avoids the circular
  require that would otherwise emerge from
  `schedule-event! → dispatch! → kernel hook → schedule-event!`."
  (:import (java.lang Thread)))

;; ---------------------------------------------------------------------------
;; The deliverer the server installs
;; ---------------------------------------------------------------------------

(defonce ^:private !dispatch-fn
  ;; Default no-op so tests that touch this namespace without booting a
  ;; server don't NPE.  Real value installed by [[install-dispatch!]].
  (atom (fn [_cid _ev] nil)))

(defn install-dispatch!
  "Called once by the server at namespace load (or by tests that need
  delivery without booting http) so timer and pub/sub deliveries can
  route events back into live conversations."
  [f]
  (reset! !dispatch-fn f))

;; ---------------------------------------------------------------------------
;; Pending-flow baton
;; ---------------------------------------------------------------------------

(defonce ^:private !pending-flows (atom {}))

(defn put-pending-flow!
  "Record the root flow id a freshly-minted cid should boot when its
  first SSE connect arrives."
  [cid flow-id]
  (swap! !pending-flows assoc cid flow-id)
  nil)

(defn pending-flow
  "Pop and return the pending flow id for `cid`, if any.  After this
  call the cid no longer has a pending flow."
  [cid]
  (let [[old _] (swap-vals! !pending-flows dissoc cid)]
    (get old cid)))

(defn forget-pending-flow!
  "Drop any pending entry for `cid` (used when a conversation ends
  before SSE ever attached)."
  [cid]
  (swap! !pending-flows dissoc cid)
  nil)

;; ---------------------------------------------------------------------------
;; Timers
;; ---------------------------------------------------------------------------

(defonce ^:private !timers (atom {}))

(defn cancel-timers!
  "Cancel every scheduled future for `cid` and forget them."
  [cid]
  (doseq [f (get @!timers cid)]
    (future-cancel f))
  (swap! !timers dissoc cid)
  nil)

(defn- forget-timer! [cid f]
  (swap! !timers update cid
         (fn [fs]
           (let [fs' (disj (or fs #{}) f)]
             (when (seq fs') fs'))))
  nil)

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

(defn schedule-event!
  "Schedule a future event for a cid/iid.  The future is cancelled when
  the conversation ends; if the instance disappears first, delivery is a
  no-op."
  [{:keys [cid instance-id delay-ms event]}]
  (let [!self (atom nil)
        f     (future
                (try
                  (Thread/sleep (max 0 (long delay-ms)))
                  (@!dispatch-fn cid (route-event->event-map instance-id event))
                  (catch InterruptedException _ nil)
                  (catch Throwable t
                    (binding [*out* *err*]
                      (println "dev.zeko.stube.async: scheduled event threw —" (ex-message t))))
                  (finally
                    (when-let [self @!self]
                      (forget-timer! cid self)))))]
    (reset! !self f)
    (swap! !timers update cid (fnil conj #{}) f)
    f))

;; ---------------------------------------------------------------------------
;; Subscriptions / publish
;; ---------------------------------------------------------------------------

(defonce ^:private !subscriptions (atom {}))

(defn subscribe!
  "Subscribe cid/iid to `topic`; published values arrive as `event`."
  [{:keys [cid instance-id topic event]}]
  (swap! !subscriptions assoc-in [topic [cid instance-id]] event)
  nil)

(defn unsubscribe!
  "Remove one cid/iid subscription.  If `topic` is nil, remove all of the
  instance's subscriptions."
  [{:keys [cid instance-id topic]}]
  (let [sub-key [cid instance-id]]
    (swap! !subscriptions
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

(defn remove-subscriptions-for-cid!
  "Drop every subscription owned by `cid` (used on conversation end)."
  [cid]
  (swap! !subscriptions
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

(defn subscriptions
  "Snapshot of topic subscriptions, for tests/inspection."
  []
  @!subscriptions)

(defn- published-event-map [iid route-event payload]
  {:instance-id iid
   :event       (if (vector? route-event) (first route-event) route-event)
   :payload     payload
   :signals     {}})

(defn publish!
  "Asynchronously deliver `msg` to every live subscriber of `topic`.
  Returns the number of subscribers targeted."
  [topic msg]
  (let [targets (get @!subscriptions topic)]
    (doseq [[[cid iid] route-event] targets]
      (future
        (@!dispatch-fn cid (published-event-map iid route-event msg))))
    (count targets)))

;; ---------------------------------------------------------------------------
;; Cleanup hooks (used by server reset-state! and tests)
;; ---------------------------------------------------------------------------

(defn reset-state!
  "Wipe every async registry.  Intended for tests / REPL iteration."
  []
  (doseq [cid (keys @!timers)]
    (cancel-timers! cid))
  (reset! !pending-flows {})
  (reset! !subscriptions {})
  nil)

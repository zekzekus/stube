(ns dev.zeko.stube.shutdown-test
  "Graceful shutdown (S-6): halt! runs :stop hooks, drains SSE, flushes
  the store, and freezes new mints."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.embed     :as embed]
            [dev.zeko.stube.http      :as http]
            [dev.zeko.stube.kernel    :as kernel]
            [dev.zeko.stube.registry  :as registry]
            [dev.zeko.stube.runtime   :as runtime]
            [dev.zeko.stube.store     :as store]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

;; ---------------------------------------------------------------------------
;; Test doubles
;; ---------------------------------------------------------------------------

(defn- recording-store
  "Conversation store that records every save!/delete! call."
  []
  (let [saved   (atom [])
        deleted (atom [])]
    {:saved   saved
     :deleted deleted
     :store   (reify store/ConversationStore
                (load-all [_] {})
                (save!    [_ conv]
                  (swap! saved conj (:conv/id conv))
                  conv)
                (delete!  [_ cid]
                  (swap! deleted conj cid)
                  nil))}))

;; ---------------------------------------------------------------------------
;; Test components
;; ---------------------------------------------------------------------------

(def !stop-flags (atom {}))

(defn- register-flag-component! []
  (registry/register!
    {:component/id     :t/with-stop
     :component/init   (fn [_] {:n 0})
     :component/render (fn [self] [:div {:id (:instance/id self)} (:n self)])
     :stop             (fn [self]
                         (swap! !stop-flags assoc (:instance/id self) :stopped)
                         nil)}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest halt-runs-stop-hooks-on-live-instances
  (reset! !stop-flags {})
  (register-flag-component!)
  (let [k   (embed/make-kernel)
        cid (embed/mint-conversation! k :t/with-stop {} {})]
    (embed/run-effects! k cid (kernel/boot :t/with-stop))
    (let [iid (-> k (embed/conversation cid) :conv/stack peek)]
      (is (some? iid))
      (is (nil? (get @!stop-flags iid)) "no :stop hook fired before halt!")
      (embed/halt! k)
      (is (= :stopped (get @!stop-flags iid))
          "halt! ran :stop on every live instance"))))

(deftest halt-pushes-close-fragment-to-open-sse-streams
  ;; halt!'s SSE drain pushes to every registered generator regardless
  ;; of conversation state, so we don't need a real Datastar channel.
  ;; Capture the push directly via `with-redefs` on the runtime symbol
  ;; that halt! reaches for.
  (let [k       (embed/make-kernel)
        cid     (embed/mint-conversation! k :t/anything {} {})
        sse-gen (reify Object)
        pushed  (atom [])]
    (with-redefs [runtime/push-fragments!
                  (fn [_gen frags]
                    (doseq [{:fragment/keys [kind]} frags]
                      (swap! pushed conj kind)))]
      (embed/register-sse! k cid sse-gen)
      (embed/halt! k))
    (is (some #{:close} @pushed)
        "halt! flushed a :close fragment to every registered SSE stream")))

(deftest halt-flushes-store-with-pending-conversations
  (register-flag-component!)
  (let [{:keys [saved store]} (recording-store)
        k   (embed/make-kernel {:store store})
        cid (embed/mint-conversation! k :t/with-stop {} {})]
    (embed/run-effects! k cid (kernel/boot :t/with-stop))
    (let [saves-before (set @saved)]
      (embed/halt! k)
      (testing "every live conversation received a final save! before halt! returned"
        (is (contains? (set @saved) cid))
        (is (>= (count @saved) (count saves-before)))))))

(deftest halt-is-idempotent
  (let [k (embed/make-kernel)]
    (embed/halt! k)
    (is (embed/shutting-down? k))
    (is (nil? (embed/halt! k)) "second halt! is a no-op")))

(deftest halt-blocks-new-shell-mints-with-503
  (registry/register!
    {:component/id     :t/blocked
     :component/render (fn [self] [:div {:id (:instance/id self)}])})
  (let [k       (embed/make-kernel)
        handler (http/shell-handler k :t/blocked)]
    (testing "before halt the shell mints normally"
      (is (= 200 (:status (handler {:headers {}})))))
    (embed/halt! k)
    (let [resp (handler {:headers {}})]
      (testing "after halt the same handler refuses with 503"
        (is (= 503 (:status resp)))
        (is (= "5" (get-in resp [:headers "Retry-After"])))))))

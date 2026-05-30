(ns dev.zeko.stube.server-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dev.zeko.stube.core     :as s]
            [dev.zeko.stube.registry :as registry]
            [dev.zeko.stube.runtime  :as rt]
            [dev.zeko.stube.server   :as server])
  (:import (java.time Duration Instant)))

(use-fixtures :each (fn [t]
                      (server/reset-state!)
                      (registry/clear!)
                      (t)
                      (registry/clear!)
                      (server/reset-state!)))

(defn- eventually [pred]
  (let [deadline (+ (System/currentTimeMillis) 1000)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 10) (recur))
        :else false))))

(defn- install-instance! [cid inst]
  (rt/swap-conv!
    (server/default-kernel)
    cid
    (fn [c]
      [(-> c
           (assoc :conv/instances {(:instance/id inst) inst})
           (assoc :conv/stack [(:instance/id inst)]))
       []])))

(deftest active-conversations-and-end
  (let [cid (rt/create-conversation! (server/default-kernel) :test/root nil)]
    (is (contains? (server/active-conversations) cid))
    (server/end! cid)
    (is (not (contains? (server/active-conversations) cid)))))

(deftest reap-removes-expired-conversations
  (let [k         (server/default-kernel)
        old-cid   (rt/create-conversation! k :test/old nil)
        fresh-cid (rt/create-conversation! k :test/fresh nil)
        old-time  (.minus (Instant/now) (Duration/ofHours 2))]
    (rt/swap-conv! k old-cid (fn [c] [(assoc c :conv/touched old-time) []]))
    (is (= [old-cid] (rt/reap! k (Duration/ofHours 1))))
    (is (nil? (server/conversation old-cid)))
    (is (some? (server/conversation fresh-cid)))))

(deftest inspect-pretty-prints-live-conversation-summary
  (let [k    (server/default-kernel)
        cid  (rt/create-conversation! k :test/root nil)
        inst {:instance/id "ix-1"
              :instance/type :test/root
              :instance/children {}
              :answer "ok"}]
    (rt/swap-conv!
      k cid
      (fn [c]
        [(-> c
             (assoc :conv/instances {"ix-1" inst})
             (assoc :conv/stack ["ix-1"])
             (assoc :conv/last-event {:instance-id "ix-1" :event :submit}))
         []]))
    (let [summary (atom nil)
          printed (with-out-str
                    (reset! summary (s/inspect cid)))]
      (is (= cid (:id @summary)))
      (is (= :submit (get-in @summary [:last-event :event])))
      (is (= {:answer "ok"}
             (get-in @summary [:instances "ix-1" :state])))
      (is (str/includes? printed cid)))))

(deftest schedule-event-dispatches-to-live-instance
  (registry/register!
    {:component/id :test/timer
     :component/render (fn [s] [:div {:id (:instance/id s)} (:n s)])
     :component/handle (fn [s {:keys [event]}]
                         (case event
                           :tick [(update s :n inc) []]
                           [s []]))})
  (let [k   (server/default-kernel)
        cid (rt/create-conversation! k :test/timer nil)
        iid "ix-timer"]
    (install-instance! cid {:instance/id iid
                            :instance/type :test/timer
                            :instance/children {}
                            :instance/rendered? true
                            :n 0})
    (rt/schedule-event! k {:cid cid :instance-id iid :delay-ms 10 :event :tick})
    (is (eventually #(= 1 (:n (get-in (server/conversation cid)
                                      [:conv/instances iid])))))))

(deftest publish-delivers-to-subscribed-instance
  (registry/register!
    {:component/id :test/subscriber
     :component/render (fn [s] [:div {:id (:instance/id s)} (pr-str (:seen s))])
     :component/handle (fn [s {:keys [event payload]}]
                         (case event
                           :published [(assoc s :seen payload) []]
                           [s []]))})
  (let [k   (server/default-kernel)
        cid (rt/create-conversation! k :test/subscriber nil)
        iid "ix-sub"]
    (install-instance! cid {:instance/id iid
                            :instance/type :test/subscriber
                            :instance/children {}
                            :instance/rendered? true
                            :seen nil})
    (rt/subscribe! k {:cid cid :instance-id iid :topic :test/topic :event :published})
    (is (= 1 (server/publish! :test/topic {:msg "hello"})))
    (is (eventually #(= {:msg "hello"}
                        (:seen (get-in (server/conversation cid)
                                       [:conv/instances iid]))))))
  (server/reset-state!)
  (is (empty? (rt/subscriptions (server/default-kernel)))))

(deftest publish-local-scopes-delivery-to-one-conversation
  (registry/register!
    {:component/id :test/local-sub
     :component/render (fn [s] [:div {:id (:instance/id s)} (pr-str (:seen s))])
     :component/handle (fn [s {:keys [event payload]}]
                         (case event
                           :published [(assoc s :seen payload) []]
                           [s []]))})
  (let [k    (server/default-kernel)
        cid-a (rt/create-conversation! k :test/local-sub nil)
        cid-b (rt/create-conversation! k :test/local-sub nil)
        iid-a "ix-a"
        iid-b "ix-b"]
    (install-instance! cid-a {:instance/id iid-a
                              :instance/type :test/local-sub
                              :instance/children {}
                              :instance/rendered? true
                              :seen nil})
    (install-instance! cid-b {:instance/id iid-b
                              :instance/type :test/local-sub
                              :instance/children {}
                              :instance/rendered? true
                              :seen nil})
    (rt/subscribe! k {:cid cid-a :instance-id iid-a :topic :scoped :event :published})
    (rt/subscribe! k {:cid cid-b :instance-id iid-b :topic :scoped :event :published})
    (is (= 1 (rt/publish-local! k cid-a :scoped {:msg "to-a"}))
        "publish-local! reports only the in-cid subscriber count")
    (is (eventually #(= {:msg "to-a"}
                        (:seen (get-in (server/conversation cid-a)
                                       [:conv/instances iid-a])))))
    (Thread/sleep 30)
    (is (nil? (:seen (get-in (server/conversation cid-b)
                             [:conv/instances iid-b])))
        "other conversations' subscribers stay silent")))

(deftest dispatch-to-delivers-event-to-named-instance
  (registry/register!
    {:component/id :test/peer
     :component/render (fn [s] [:div {:id (:instance/id s)} (pr-str (:seen s))])
     :component/handle (fn [s {:keys [event payload]}]
                         (case event
                           :poke   [(update s :pokes (fnil inc 0)) []]
                           :seen-it [(assoc s :seen payload) []]
                           [s []]))})
  (let [k   (server/default-kernel)
        cid (rt/create-conversation! k :test/peer nil)
        iid "ix-peer"]
    (install-instance! cid {:instance/id iid
                            :instance/type :test/peer
                            :instance/children {}
                            :instance/rendered? true})
    (rt/dispatch-to! k {:cid cid :target-iid iid :event :poke})
    (is (eventually #(= 1 (:pokes (get-in (server/conversation cid)
                                          [:conv/instances iid])))))
    (rt/dispatch-to! k {:cid cid :target-iid iid :event [:seen-it 42]})
    (is (eventually #(= 42 (:seen (get-in (server/conversation cid)
                                          [:conv/instances iid])))))
    (rt/dispatch-to! k {:cid cid :target-iid "ix-missing" :event :poke})
    (is (eventually #(= 1 (:pokes (get-in (server/conversation cid)
                                          [:conv/instances iid]))))
        "dispatch to a vanished iid is dropped silently (no throw)")))

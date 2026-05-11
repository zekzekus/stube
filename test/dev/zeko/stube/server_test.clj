(ns dev.zeko.stube.server-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dev.zeko.stube.core :as s]
            [dev.zeko.stube.registry :as registry]
            [dev.zeko.stube.server :as server])
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
  (server/swap-conv!
    cid
    (fn [c]
      [(-> c
           (assoc :conv/instances {(:instance/id inst) inst})
           (assoc :conv/stack [(:instance/id inst)]))
       []])))

(deftest active-conversations-and-end
  (let [cid (server/create-conversation! :test/root)]
    (is (contains? (server/active-conversations) cid))
    (server/end! cid)
    (is (not (contains? (server/active-conversations) cid)))))

(deftest reap-removes-expired-conversations
  (let [old-cid   (server/create-conversation! :test/old)
        fresh-cid (server/create-conversation! :test/fresh)
        old-time  (.minus (Instant/now) (Duration/ofHours 2))]
    (server/swap-conv! old-cid (fn [c] [(assoc c :conv/touched old-time) []]))
    (is (= [old-cid] (server/reap! (Duration/ofHours 1))))
    (is (nil? (server/conversation old-cid)))
    (is (some? (server/conversation fresh-cid)))))

(deftest built-in-routes-do-not-conflict
  (is (some? (#'server/build-router))))

(deftest inspect-pretty-prints-live-conversation-summary
  (let [cid  (server/create-conversation! :test/root)
        inst {:instance/id "ix-1"
              :instance/type :test/root
              :instance/children {}
              :answer "ok"}]
    (server/swap-conv!
      cid
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
  (let [cid (server/create-conversation! :test/timer)
        iid "ix-timer"]
    (install-instance! cid {:instance/id iid
                            :instance/type :test/timer
                            :instance/children {}
                            :instance/rendered? true
                            :n 0})
    (server/schedule-event! {:cid cid :instance-id iid :delay-ms 10 :event :tick})
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
  (let [cid (server/create-conversation! :test/subscriber)
        iid "ix-sub"]
    (install-instance! cid {:instance/id iid
                            :instance/type :test/subscriber
                            :instance/children {}
                            :instance/rendered? true
                            :seen nil})
    (server/subscribe! {:cid cid :instance-id iid :topic :test/topic :event :published})
    (is (= 1 (server/publish! :test/topic {:msg "hello"})))
    (is (eventually #(= {:msg "hello"}
                        (:seen (get-in (server/conversation cid)
                                       [:conv/instances iid]))))))
  (server/reset-state!)
  (is (empty? (server/subscriptions))))

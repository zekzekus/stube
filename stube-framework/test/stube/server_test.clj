(ns stube.server-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [stube.core :as s]
            [stube.server :as server])
  (:import (java.time Duration Instant)))

(use-fixtures :each (fn [t] (server/reset-state!) (t) (server/reset-state!)))

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

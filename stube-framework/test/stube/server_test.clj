(ns stube.server-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
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

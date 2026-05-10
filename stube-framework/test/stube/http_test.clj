(ns stube.http-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [stube.http   :as http]
            [stube.server :as server]))

(use-fixtures :each (fn [t] (server/reset-state!) (t) (server/reset-state!)))

(deftest stale-event-returns-410
  (let [resp (http/event-handler {:path-params {:cid "cv-missing"
                                                :iid "ix-missing"
                                                :event "go"}})]
    (is (= 410 (:status resp)))
    (is (re-find #"stale" (:body resp)))))

(deftest stale-event-forgets-existing-conversation
  (let [cid  (server/create-conversation! :test/root)
        resp (http/event-handler {:path-params {:cid cid
                                                :iid "ix-missing"
                                                :event "go"}})]
    (is (= 410 (:status resp)))
    (is (nil? (server/conversation cid)))))

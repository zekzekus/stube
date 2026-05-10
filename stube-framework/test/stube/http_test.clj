(ns stube.http-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [stube.http :as http]
            [stube.registry :as registry]
            [stube.server :as server]))

(use-fixtures :each (fn [t]
                      (server/reset-state!)
                      (registry/clear!)
                      (t)
                      (registry/clear!)
                      (server/reset-state!)))

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

(deftest shell-sets-session-cookie
  (let [resp ((http/shell-handler :test/root) {:headers {}})]
    (is (= 200 (:status resp)))
    (is (re-find #"stube_sid=" (get-in resp [:headers "Set-Cookie"]))))
  (let [resp ((http/shell-handler :test/root)
              {:headers {"cookie" "stube_sid=already"}})]
    (is (nil? (get-in resp [:headers "Set-Cookie"])))
    (is (some #(= "already" (:conv/owner-token %))
              (vals (server/active-conversations))))))

(deftest event-rejects-wrong-session
  (registry/register!
    {:component/id :test/noop
     :component/handle (fn [s _] [s []])})
  (let [cid (server/create-conversation! :test/root "owner")
        inst {:instance/id "ix-1"
              :instance/type :test/noop
              :instance/children {}}]
    (server/swap-conv!
      cid
      (fn [c]
        [(-> c
             (assoc :conv/instances {"ix-1" inst})
             (assoc :conv/stack ["ix-1"]))
         []]))
    (is (= 403 (:status (http/event-handler
                         {:path-params {:cid cid :iid "ix-1" :event "go"}
                          :headers {"cookie" "stube_sid=wrong"}}))))
    (is (= 204 (:status (http/event-handler
                         {:path-params {:cid cid :iid "ix-1" :event "go"}
                          :headers {"cookie" "stube_sid=owner"}}))))))

(deftest wrong-session-cannot-stale-end-conversation
  (let [cid (server/create-conversation! :test/root "owner")]
    (is (= 403 (:status (http/event-handler
                         {:path-params {:cid cid :iid "ix-missing" :event "go"}
                          :headers {"cookie" "stube_sid=wrong"}}))))
    (is (some? (server/conversation cid)))))

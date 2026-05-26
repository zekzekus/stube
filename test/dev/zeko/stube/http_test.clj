(ns dev.zeko.stube.http-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [dev.zeko.stube.fragments :as fragments]
            [dev.zeko.stube.http :as http]
            [dev.zeko.stube.registry :as registry]
            [dev.zeko.stube.runtime :as rt]
            [dev.zeko.stube.server :as server]))

(use-fixtures :each (fn [t]
                      (server/reset-state!)
                      (registry/clear!)
                      (t)
                      (registry/clear!)
                      (server/reset-state!)))

(deftest signal-patches-serialize-to-json
  (is (= "{\"password-ix-1\":\"\"}"
         (#'fragments/json-str {:password-ix-1 ""}))))

(deftest stale-event-returns-410
  (let [resp (http/event-handler {:path-params {:cid "cv-missing"
                                                :iid "ix-missing"
                                                :event "go"}})]
    (is (= 410 (:status resp)))
    (is (re-find #"stale" (:body resp)))))

(deftest stale-instance-event-in-live-conversation-is-noop
  (let [cid  (rt/create-conversation! (server/default-kernel) :test/root nil)
        resp (http/event-handler {:path-params {:cid cid
                                                :iid "ix-missing"
                                                :event "go"}})]
    (is (= 204 (:status resp)))
    (is (some? (server/conversation cid)))))

(deftest shell-sets-session-cookie
  (let [resp ((http/shell-handler :test/root) {:headers {}})]
    (is (= 200 (:status resp)))
    (is (re-find #"stube_sid=" (get-in resp [:headers "Set-Cookie"]))))
  (let [resp ((http/shell-handler :test/root)
              {:headers {"cookie" "stube_sid=already"}})]
    (is (nil? (get-in resp [:headers "Set-Cookie"])))
    (is (some #(= "already" (:conv/owner-token %))
              (vals (server/active-conversations))))))

(deftest shell-set-cookie-matches-conv-owner-token
  ;; Pins the shell→SSE handshake.  shell-handler used to call
  ;; ensure-session twice on a cookie-less request: once to compute the
  ;; Set-Cookie header, once inside mint-conversation!.  Each call
  ;; minted a fresh sid, so the conversation ended up owned by a sid
  ;; the browser was never told about — and the subsequent SSE GET
  ;; (now carrying the cookie from Set-Cookie) was rejected as
  ;; cross-session.
  (let [resp     ((http/shell-handler :test/root) {:headers {}})
        cookie   (get-in resp [:headers "Set-Cookie"])
        sid      (second (re-find #"stube_sid=([^;]+)" cookie))
        owners   (->> (server/active-conversations) vals
                      (keep :conv/owner-token) set)]
    (is (some? sid) "Set-Cookie should include stube_sid=<value>")
    (is (contains? owners sid)
        "minted conversation must be owned by the sid in Set-Cookie")))

(deftest event-rejects-wrong-session
  (registry/register!
    {:component/id :test/noop
     :component/handle (fn [s _] [s []])})
  (let [cid (rt/create-conversation! (server/default-kernel) :test/root "owner")
        inst {:instance/id "ix-1"
              :instance/type :test/noop
              :instance/children {}}]
    (rt/swap-conv! (server/default-kernel)
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
  (let [cid (rt/create-conversation! (server/default-kernel) :test/root "owner")]
    (is (= 403 (:status (http/event-handler
                         {:path-params {:cid cid :iid "ix-missing" :event "go"}
                          :headers {"cookie" "stube_sid=wrong"}}))))
    (is (some? (server/conversation cid)))))

(deftest stale-upload-instance-in-live-conversation-is-noop
  (let [cid  (rt/create-conversation! (server/default-kernel) :test/root nil)
        resp (http/upload-handler {:path-params {:cid cid :iid "ix-missing"}})]
    (is (= 204 (:status resp)))
    (is (some? (server/conversation cid)))))

(deftest upload-handler-dispatches-edn-file-summary
  (registry/register!
    {:component/id :test/upload
     :component/render (fn [s] [:div {:id (:instance/id s)} "upload"])
     :component/handle (fn [s {:keys [event payload]}]
                         (case event
                           :upload-received [(assoc s :seen payload) []]
                           [s []]))})
  (let [cid  (rt/create-conversation! (server/default-kernel) :test/upload "owner")
        iid  "ix-upload"
        tmp  (doto (java.io.File/createTempFile "stube-upload" ".txt")
               (spit "hello upload"))]
    (try
      (rt/swap-conv! (server/default-kernel)
        cid
        (fn [c]
          [(-> c
               (assoc :conv/instances {iid {:instance/id iid
                                             :instance/type :test/upload
                                             :instance/children {}
                                             :instance/rendered? true}})
               (assoc :conv/stack [iid]))
           []]))
      (let [resp (http/upload-handler
                   {:path-params {:cid cid :iid iid}
                    :headers {"cookie" "stube_sid=owner"}
                    :multipart-params {"note" "caption"
                                       "file" {:filename "hello.txt"
                                               :content-type "text/plain"
                                               :tempfile tmp}}})
            seen (get-in (server/conversation cid)
                         [:conv/instances iid :seen])]
        (is (= 200 (:status resp)))
        (is (= {:note "caption"} (:fields seen)))
        (is (= [{:field "file"
                 :filename "hello.txt"
                 :content-type "text/plain"
                 :size (.length tmp)
                 :tempfile (.getAbsolutePath (io/file tmp))}]
               (:files seen))))
      (finally
        (io/delete-file tmp true)))))

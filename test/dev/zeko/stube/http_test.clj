(ns dev.zeko.stube.http-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.embed :as embed]
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

(deftest component-style-handler-serves-resource-when-it-exists
  (let [resp (http/component-style-handler
               {:path-params {:asset "test-style/yes.css"}})]
    (is (= 200 (:status resp)))
    (is (re-find #"text/css" (get-in resp [:headers "Content-Type"])))
    (is (re-find #"rebeccapurple" (:body resp)))))

(deftest component-style-handler-404s-on-missing-resource
  (let [resp (http/component-style-handler
               {:path-params {:asset "test-style/missing.css"}})]
    (is (= 404 (:status resp)))))

(deftest component-style-handler-rejects-traversal-paths
  ;; The handler must reject ../, slashes inside segments, and any
  ;; character that would let an attacker step out of the resource
  ;; root.  Returns 400 (or rejects matching) for everything off-shape.
  (doseq [asset ["../../etc/passwd"
                 "../../etc/passwd.css"
                 "foo/../bar.css"
                 "deep/path/x.css"]]
    (let [resp (http/component-style-handler
                 {:path-params {:asset asset}})]
      (is (= 400 (:status resp))
          (str "must reject traversal-shaped asset: " asset)))))

(deftest component-behavior-handler-serves-js-resource
  (let [resp (http/component-behavior-handler
               {:path-params {:asset "test-style/echo.js"}})]
    (is (= 200 (:status resp)))
    (is (re-find #"application/javascript"
                 (get-in resp [:headers "Content-Type"])))
    (is (re-find #"mount" (:body resp)))))

(deftest component-module-handler-serves-js-resource
  (let [resp (http/component-module-handler
               {:path-params {:asset "test-style/setup.js"}})]
    (is (= 200 (:status resp)))
    (is (re-find #"test module loaded" (:body resp)))))

(deftest behaviors-js-handler-serves-bridge
  (let [resp (http/behaviors-js-handler {})]
    (is (= 200 (:status resp)))
    (is (re-find #"stube behaviors bridge" (:body resp)))
    (testing "writes flow through Datastar's public data-bind seam, not internal handles"
      ;; The bridge must locate a `[data-stube-signal-mirror=…]` element
      ;; (rendered by `s/signal-mirror`), write its `.value`, and
      ;; dispatch a standard DOM `input` event.  This avoids coupling
      ;; the bridge to any Datastar-internal symbol — see
      ;; `kasten/stube_notes.md §1` (against 0.3.3) for the misadventure
      ;; with the outbound `datastar-signal-patch` event.
      (is (re-find #"data-stube-signal-mirror" (:body resp)))
      (is (re-find #"dispatchEvent\(new Event\(\"input\"" (:body resp))))
    (testing "bridge must not dispatch datastar-signal-patch (Datastar v1 fires that *outbound* on its own setter)"
      (is (not (re-find #"datastar-signal-patch" (:body resp)))))))

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

(defn- install-noop-instance!
  "Register a no-op component and put one live instance on `cid`'s stack
  so event-handler reaches its dispatch branch."
  [k cid]
  (registry/register!
    {:component/id :test/noop
     :component/handle (fn [s _] [s []])})
  (rt/swap-conv! k cid
    (fn [c]
      [(-> c
           (assoc :conv/instances {"ix-1" {:instance/id "ix-1"
                                           :instance/type :test/noop
                                           :instance/children {}}})
           (assoc :conv/stack ["ix-1"]))
       []])))

(deftest event-bounds-request-parsing
  (let [k   (server/default-kernel)
        cid (rt/create-conversation! k :test/root "owner")]
    (install-noop-instance! k cid)
    (testing "oversize signals body → 413, without OOMing the parser"
      (let [big  (str "{\"x\":\"" (apply str (repeat 70000 \a)) "\"}")
            resp (http/event-handler
                   k {:path-params    {:cid cid :iid "ix-1" :event "go"}
                      :request-method :post
                      :headers        {"cookie" "stube_sid=owner"}
                      :body           (java.io.ByteArrayInputStream.
                                        (.getBytes ^String big "UTF-8"))})]
        (is (= 413 (:status resp)))))
    (testing "oversize EDN payload param → 413"
      (let [resp (http/event-handler
                   k {:path-params    {:cid cid :iid "ix-1" :event "go"}
                      :request-method :post
                      :headers        {"cookie" "stube_sid=owner"}
                      :query-string   (str "_stube_payload="
                                           (apply str (repeat 5000 \1)))})]
        (is (= 413 (:status resp)))))
    (testing "unparseable EDN payload param → 400"
      (let [resp (http/event-handler
                   k {:path-params    {:cid cid :iid "ix-1" :event "go"}
                      :request-method :post
                      :headers        {"cookie" "stube_sid=owner"}
                      ;; %28 = '(' — three unbalanced opens never close.
                      :query-string   "_stube_payload=%28%28%28"})]
        (is (= 400 (:status resp)))))
    (testing "in-bounds request still dispatches (204)"
      (let [resp (http/event-handler
                   k {:path-params    {:cid cid :iid "ix-1" :event "go"}
                      :request-method :post
                      :headers        {"cookie" "stube_sid=owner"}
                      :query-string   "_stube_payload=42"})]
        (is (= 204 (:status resp)))))))

(deftest event-handler-bounds-keyword-interning
  ;; A path event whose keyword was never interned (no component names
  ;; it) must be a harmless no-op, NOT a fresh permanent keyword.  An
  ;; event a component does handle — a keyword literal, hence interned —
  ;; still dispatches.
  (registry/register!
    {:component/id :test/counter
     :component/handle (fn [s {:keys [event]}]
                         ;; `:bump` literal here → interned at load time.
                         (if (= event :bump)
                           [(update s :n (fnil inc 0)) []]
                           [s []]))})
  (let [k   (server/default-kernel)
        cid (rt/create-conversation! k :test/root "owner")]
    (rt/swap-conv! k cid
      (fn [c]
        [(-> c
             (assoc :conv/instances {"ix-1" {:instance/id "ix-1"
                                             :instance/type :test/counter
                                             :instance/rendered? true
                                             :instance/children {}}})
             (assoc :conv/stack ["ix-1"]))
         []]))
    (let [req (fn [event]
                {:path-params    {:cid cid :iid "ix-1" :event event}
                 :request-method :post
                 :headers        {"cookie" "stube_sid=owner"}})]
      (testing "interned, handled event dispatches"
        (is (= 204 (:status (http/event-handler k (req "bump")))))
        (is (= 1 (get-in (server/conversation cid) [:conv/instances "ix-1" :n]))))
      (testing "never-named event is a no-op (204) and changes nothing"
        (is (= 204 (:status (http/event-handler
                              k (req "totally-unnamed-event-xyz")))))
        (is (= 1 (get-in (server/conversation cid)
                         [:conv/instances "ix-1" :n])))))))

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
               (spit "hello upload"))
        ;; Capture before the handler runs: the default upload path now
        ;; deletes the tempfile once dispatch consumes it, so reading
        ;; `.length` afterwards would see 0.
        expected-size (.length tmp)
        expected-path (.getAbsolutePath (io/file tmp))]
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
                 :size expected-size
                 :tempfile expected-path}]
               (:files seen))))
      (finally
        (io/delete-file tmp true)))))

(deftest upload-handler-reclaims-tempfiles-by-default
  (registry/register!
    {:component/id :test/up-clean
     :component/handle (fn [s _] [s []])})
  (let [k   (server/default-kernel)
        cid (rt/create-conversation! k :test/up-clean "owner")
        iid "ix-up"
        tmp (doto (java.io.File/createTempFile "stube-cleanup" ".txt")
              (spit "data"))]
    (rt/swap-conv! k cid
      (fn [c]
        [(-> c
             (assoc :conv/instances {iid {:instance/id iid
                                          :instance/type :test/up-clean
                                          :instance/rendered? true
                                          :instance/children {}}})
             (assoc :conv/stack [iid]))
         []]))
    (http/upload-handler
      k {:path-params {:cid cid :iid iid}
         :headers {"cookie" "stube_sid=owner"}
         :multipart-params {"file" {:filename "x.txt"
                                    :content-type "text/plain"
                                    :tempfile tmp}}})
    (is (not (.exists tmp))
        "default upload path deletes the tempfile after dispatch consumes it")))

(deftest upload-handler-keeps-tempfiles-when-opted-in
  (registry/register!
    {:component/id :test/up-keep
     :component/handle (fn [s _] [s []])})
  (let [k   (embed/make-kernel {:keep-upload? true})
        cid (rt/create-conversation! k :test/up-keep "owner")
        iid "ix-up"
        tmp (doto (java.io.File/createTempFile "stube-keep" ".txt")
              (spit "data"))]
    (try
      (rt/swap-conv! k cid
        (fn [c]
          [(-> c
               (assoc :conv/instances {iid {:instance/id iid
                                            :instance/type :test/up-keep
                                            :instance/rendered? true
                                            :instance/children {}}})
               (assoc :conv/stack [iid]))
           []]))
      (http/upload-handler
        k {:path-params {:cid cid :iid iid}
           :headers {"cookie" "stube_sid=owner"}
           :multipart-params {"file" {:filename "x.txt"
                                      :content-type "text/plain"
                                      :tempfile tmp}}})
      (is (.exists tmp)
          ":keep-upload? leaves the tempfile for async processing")
      (finally
        (io/delete-file tmp true)))))

(deftest upload-handler-rejects-oversize-body
  (let [k   (server/default-kernel)
        cid (rt/create-conversation! k :test/up-clean "owner")]
    (registry/register!
      {:component/id :test/up-clean
       :component/handle (fn [s _] [s []])})
    (rt/swap-conv! k cid
      (fn [c]
        [(-> c
             (assoc :conv/instances {"ix-1" {:instance/id "ix-1"
                                             :instance/type :test/up-clean
                                             :instance/children {}}})
             (assoc :conv/stack ["ix-1"]))
         []]))
    (is (= 413 (:status (http/upload-handler
                          k {:path-params {:cid cid :iid "ix-1"}
                             :headers {"cookie" "stube_sid=owner"
                                       "content-length" "999999999"}}))))))

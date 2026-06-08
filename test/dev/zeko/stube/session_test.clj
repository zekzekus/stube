(ns dev.zeko.stube.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [dev.zeko.stube.embed   :as embed]
            [dev.zeko.stube.runtime :as rt]
            [dev.zeko.stube.session :as session]))

(deftest cookie-header-attributes
  (testing "secure by default; always HttpOnly + SameSite=Lax + Path=/"
    (let [h (session/session-cookie-header "abc" {})]
      (is (re-find #"stube_sid=abc" h))
      (is (re-find #"HttpOnly" h))
      (is (re-find #"SameSite=Lax" h))
      (is (re-find #"Path=/" h))
      (is (re-find #"Secure" h))))
  (testing "dev opt-out drops only Secure"
    (let [h (session/session-cookie-header "abc" {:secure? false})]
      (is (not (re-find #"Secure" h)))
      (is (re-find #"HttpOnly" h))
      (is (re-find #"SameSite=Lax" h))))
  (testing "domain + custom path"
    (let [h (session/session-cookie-header "abc" {:domain "example.com"
                                                  :path   "/app"})]
      (is (re-find #"Domain=example.com" h))
      (is (re-find #"Path=/app" h)))))

(deftest ensure-session-mints-and-reuses
  (testing "fresh request mints a sid and a Secure cookie by default"
    (let [[sid cookie] (session/ensure-session {:headers {}})]
      (is (string? sid))
      (is (re-find #"Secure" cookie))))
  (testing "a request that already carries the cookie reuses it, no Set-Cookie"
    (let [[sid cookie] (session/ensure-session
                         {:headers {"cookie" "stube_sid=keep"}})]
      (is (= "keep" sid))
      (is (nil? cookie))))
  (testing "secure? false drops Secure on the minted cookie"
    (let [[_ cookie] (session/ensure-session {:headers {}} {:secure? false})]
      (is (not (re-find #"Secure" cookie))))))

(deftest embedded-kernel-cookie-is-secure-by-default
  (testing "make-kernel mints a Secure cookie — embedders run behind TLS"
    (let [k (embed/make-kernel)
          [_ cookie] (rt/ensure-session k {:headers {}})]
      (is (re-find #"Secure" cookie))))
  (testing ":dev-cookie? true opts out for plain-HTTP localhost"
    (let [k (embed/make-kernel {:dev-cookie? true})
          [_ cookie] (rt/ensure-session k {:headers {}})]
      (is (not (re-find #"Secure" cookie)))))
  (testing ":cookie-domain / :cookie-path flow through to the cookie"
    (let [k (embed/make-kernel {:cookie-domain "ex.com" :cookie-path "/w"})
          [_ cookie] (rt/ensure-session k {:headers {}})]
      (is (re-find #"Domain=ex.com" cookie))
      (is (re-find #"Path=/w" cookie)))))

(deftest rotate-session-rotates-owner-and-returns-cookie
  (let [k         (embed/make-kernel)
        cid       (rt/mint-conversation! k :test/root {} {:headers {}})
        old-owner (:conv/owner-token (rt/conversation k cid))
        old-csrf  (rt/conversation-csrf-token k cid)
        cookie    (embed/rotate-session! k cid)
        new-owner (:conv/owner-token (rt/conversation k cid))]
    (is (string? cookie))
    (is (re-find #"Secure" cookie) "rotated cookie keeps the kernel's attributes")
    (is (not= old-owner new-owner) "owner token is rotated")
    (is (re-find (re-pattern (str "stube_sid=" new-owner)) cookie)
        "the cookie carries the new owner token")
    (is (= old-csrf (rt/conversation-csrf-token k cid))
        "CSRF token is preserved so the current page keeps working")
    (testing "unknown cid → nil"
      (is (nil? (embed/rotate-session! k "cv-nope"))))))

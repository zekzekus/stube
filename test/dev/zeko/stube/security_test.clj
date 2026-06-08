(ns dev.zeko.stube.security-test
  (:require [clojure.test :refer [deftest is testing]]
            [dev.zeko.stube.security :as sec]))

(deftest content-security-policy-builds-a-header-value
  (is (= "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; frame-ancestors 'none'"
         (sec/content-security-policy
           {:default-src     "'self'"
            :script-src      ["'self'" "https://cdn.jsdelivr.net"]
            :frame-ancestors "'none'"})))
  (testing "string directive names work too"
    (is (= "default-src 'self'"
           (sec/content-security-policy {"default-src" "'self'"})))))

(deftest wrap-defaults-adds-baseline-headers
  (let [handler (fn [_] {:status 200
                         :headers {"Content-Type" "text/html"}
                         :body "ok"})
        resp    ((sec/wrap-defaults handler) {})]
    (is (= "nosniff"     (get-in resp [:headers "X-Content-Type-Options"])))
    (is (= "same-origin" (get-in resp [:headers "Referrer-Policy"])))
    (is (= "SAMEORIGIN"  (get-in resp [:headers "X-Frame-Options"])))
    (is (= "same-origin" (get-in resp [:headers "Cross-Origin-Opener-Policy"])))
    (is (= "text/html"   (get-in resp [:headers "Content-Type"]))
        "handler's own headers are preserved")))

(deftest wrap-defaults-does-not-clobber-handler-headers
  (let [handler (fn [_] {:status 200 :headers {"X-Frame-Options" "DENY"} :body ""})
        resp    ((sec/wrap-defaults handler) {})]
    (is (= "DENY" (get-in resp [:headers "X-Frame-Options"]))
        "a header the handler already set wins")))

(deftest wrap-defaults-csp-and-nil-removal
  (let [handler (fn [_] {:status 200 :body ""})
        resp    ((sec/wrap-defaults handler
                   {:csp     "default-src 'self'"
                    :headers {"X-Frame-Options" nil}}) {})]
    (is (= "default-src 'self'" (get-in resp [:headers "Content-Security-Policy"])))
    (is (not (contains? (:headers resp) "X-Frame-Options"))
        "a nil value in :headers removes that default")))

(deftest wrap-defaults-passes-non-map-responses-through
  (let [resp ((sec/wrap-defaults (fn [_] nil)) {})]
    (is (nil? resp) "a nil response is left alone")))

(deftest wrap-defaults-supports-async-ring
  (let [handler (fn [_ respond _] (respond {:status 200 :body ""}))
        result  (atom nil)]
    ((sec/wrap-defaults handler) {} #(reset! result %) identity)
    (is (= "nosniff" (get-in @result [:headers "X-Content-Type-Options"])))))

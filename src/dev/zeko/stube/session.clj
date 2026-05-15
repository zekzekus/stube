(ns dev.zeko.stube.session
  "Session cookies + conversation ownership checks.

  Each browser gets a `stube_sid` cookie minted on first visit; that
  value is recorded on the conversation as `:conv/owner-token` when the
  cid is created.  Subsequent requests for that cid are accepted only
  when the cookie matches the stored token.  This is the single
  primitive [[authorized?]] both http and halos handlers use."
  (:require [clojure.string  :as str]
            [dev.zeko.stube.server :as server])
  (:import (java.util UUID)))

(def ^:private session-cookie "stube_sid")

(defn cookie-map
  "Parse the Cookie header into a `{name → value}` map."
  [{:keys [headers]}]
  (into {}
        (keep (fn [part]
                (let [[k v] (str/split (str/trim part) #"=" 2)]
                  (when (seq k) [k v]))))
        (some-> (or (get headers "cookie") (get headers "Cookie"))
                (str/split #";"))))

(defn request-session
  "Return the `stube_sid` cookie value on the request, or nil."
  [req]
  (get (cookie-map req) session-cookie))

(defn- new-session []
  (str (UUID/randomUUID)))

(defn- session-cookie-header [sid]
  (str session-cookie "=" sid "; Path=/; HttpOnly; SameSite=Lax"))

(defn ensure-session
  "Return `[sid set-cookie-header-or-nil]`.  The `Set-Cookie` value is
  only non-nil on the first request from a fresh browser."
  [req]
  (if-let [sid (request-session req)]
    [sid nil]
    (let [sid (new-session)]
      [sid (session-cookie-header sid)])))

(defn authorized?
  "True when the request's session cookie matches the cid's recorded
  owner-token, or when the conversation has no owner-token (legacy)."
  [req cid]
  (let [conv  (server/conversation cid)
        owner (:conv/owner-token conv)]
    (or (nil? owner)
        (= owner (request-session req)))))

(defn forbidden-response
  "403 body sent when [[authorized?]] returns false."
  []
  {:status  403
   :headers {"Content-Type" "text/plain; charset=utf-8"
             "Cache-Control" "no-store"}
   :body    "stube conversation belongs to a different session."})

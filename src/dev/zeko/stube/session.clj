(ns dev.zeko.stube.session
  "Session cookies + conversation ownership checks.

  Each browser gets a `stube_sid` cookie minted on first visit; that
  value is recorded on the conversation as `:conv/owner-token` when the
  cid is created.  Subsequent requests for that cid are accepted only
  when the cookie matches the stored token.  This is the single
  primitive [[authorized?]] both http and halos handlers use."
  (:require [clojure.string  :as str])
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

(defn session-cookie-header
  "Build the `Set-Cookie` value for `sid`.  Always `HttpOnly` and
  `SameSite=Lax`; `:secure?` (default true) adds `Secure`, `:domain`
  scopes the cookie, `:path` defaults to `/`."
  [sid {:keys [secure? domain path] :or {secure? true path "/"}}]
  (str session-cookie "=" sid
       "; Path=" path
       (when domain (str "; Domain=" domain))
       "; HttpOnly; SameSite=Lax"
       (when secure? "; Secure")))

(defn ensure-session
  "Return `[sid set-cookie-header-or-nil]`.  The `Set-Cookie` value is
  only non-nil on the first request from a fresh browser.

  `opts` controls the emitted cookie attributes — `:secure?` (default
  true; only turn it off behind plain-HTTP localhost dev, or the browser
  will not send the cookie back), `:domain`, and `:path`."
  ([req] (ensure-session req {}))
  ([req opts]
   (if-let [sid (request-session req)]
     [sid nil]
     (let [sid (new-session)]
       [sid (session-cookie-header sid opts)]))))

(defn authorized?
  "True when the request's session cookie matches the conversation's
  recorded owner-token, or when the conversation has no owner-token
  (legacy / host-managed auth)."
  [req conv]
  (let [owner (:conv/owner-token conv)]
    (or (nil? owner)
        (= owner (request-session req)))))

(defn forbidden-response
  "403 body sent when [[authorized?]] returns false."
  []
  {:status  403
   :headers {"Content-Type" "text/plain; charset=utf-8"
             "Cache-Control" "no-store"}
   :body    "stube conversation belongs to a different session."})

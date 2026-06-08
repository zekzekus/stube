(ns dev.zeko.stube.examples.secure-ring
  "A worked secure embedding — the chain `docs/security.md` describes, in
  one file.  It wires:

    * `make-kernel` with secure defaults (the `Secure` cookie stays on —
      this host is assumed to be behind TLS), a `:principal-fn`, audit
      hooks (`:on-auth-fail` / `:on-stale` / `:on-shell-mint`), and a
      fail-closed `:before-dispatch` authz gate;
    * the *mount* mechanism for the page, so stube does the cookie +
      CSRF handshake for us — a hand-rolled page that calls
      `mint-conversation!` directly would have to replay
      `ensure-session` and attach the `Set-Cookie` itself;
    * `security/wrap-defaults` + a Datastar-compatible CSP around the
      whole ring handler.

  Run from a REPL: `(dev.zeko.stube.examples.secure-ring/start! 8080)`
  then open http://localhost:8080/app.  NOTE: the cookie is `Secure`, so
  a browser will not return it over plain HTTP — this example is about
  the *shape* of a secure stack, not about clicking through it on
  localhost.  Add `:dev-cookie? true` to the kernel to exercise it over
  http (see the `embedded-ring` example for the plain-HTTP variant).

  This namespace is a reference; it is not auto-loaded by the example
  browser."
  (:require [org.httpkit.server          :as http-kit]
            [reitit.ring                 :as ring]
            [dev.zeko.stube.adapter.ring :as stube-ring]
            [dev.zeko.stube.core         :as s]
            [dev.zeko.stube.embed        :as stube]
            [dev.zeko.stube.security     :as security]))

;; --------------------------------------------------------------------------
;; A protected component: only a signed-in principal may bump the counter.
;; Sign-in here is just `?user=<name>` on the shell GET (a real host reads
;; its own session / JWT in `:principal-fn`).  Each shell GET re-mints, so
;; navigating to /app?user=ada captures that principal at mint time.
;; --------------------------------------------------------------------------

(s/defcomponent :secure/counter
  :init   (fn [_] {:n 0})
  :render (fn [self]
            (let [user (:user (s/principal))]
              [:section (s/root-attrs self {:class "stube-card"})
               [:h2 "Protected counter"]
               (if user
                 [:p "Signed in as " [:strong user] " · "
                  [:a {:href "/app"} "sign out"]]
                 [:p "Not signed in · " [:a {:href "/app?user=ada"} "sign in as ada"]])
               [:button (s/on self :click :as :inc) "+1"]
               [:strong {:style "padding: 0 0.75rem"} (:n self)]]))
  :handle (fn [self {:keys [event]}]
            (case event
              :inc (update self :n inc)
              self)))

;; --------------------------------------------------------------------------
;; Host auth model + the secure kernel.
;; --------------------------------------------------------------------------

(defn- request-principal [req]
  (some->> (:query-string req)
           (re-find #"(?:^|&)user=([^&]+)")
           second
           (hash-map :user)))

(defn- audit [event]
  ;; A real host ships these to its log pipeline; we just print.
  (fn [info] (println "AUDIT" event (pr-str (dissoc info :request)))))

(defonce secure-kernel
  (stube/make-kernel
    {;; Secure cookie stays ON (default) — this host terminates TLS.
     :principal-fn  request-principal
     :on-auth-fail  (audit :auth-fail)
     :on-stale      (audit :stale)
     :on-shell-mint (audit :shell-mint)
     ;; Centralised authz: a write event requires a signed-in principal.
     ;; Returns :continue or [:reject status body]; a throwing hook
     ;; fails closed (rejects).
     :before-dispatch
     (fn [conv _event _req]
       (if (:conv/principal conv)
         :continue
         [:reject 401 "sign in to interact"]))}))

;; On a *real* login/logout (not a query-param toggle), rotate the
;; session so a fixed stube_sid doesn't survive the privilege change:
;;
;;   (let [cookie (stube/rotate-session! secure-kernel cid)]
;;     {:status 303 :headers {"Location" "/app" "Set-Cookie" cookie}})
;;
;; See docs/security.md §7.

;; --------------------------------------------------------------------------
;; Ring handler: stube owns /app (shell) + its SSE/event/upload/asset
;; routes; security/wrap-defaults adds the response headers and CSP.
;; --------------------------------------------------------------------------

(defn handler []
  (-> (ring/ring-handler
        (ring/router
          (stube-ring/ring-routes secure-kernel {:mounts {"/app" :secure/counter}}))
        (ring/create-default-handler))
      (security/wrap-defaults
        {:csp (security/content-security-policy
                ;; 'unsafe-eval' is required by Datastar's expression
                ;; engine; the CDN origin is where datastar.js loads.
                ;; See docs/security.md §5.
                {:default-src     "'self'"
                 :script-src      ["'self'" "'unsafe-eval'" "https://cdn.jsdelivr.net"]
                 :style-src       ["'self'" "'unsafe-inline'"]
                 :connect-src     ["'self'"]
                 :frame-ancestors "'none'"})})))

(defonce !server (atom nil))

(defn start!
  ([] (start! 8080))
  ([port]
   (when-let [stop @!server] (stop))
   (let [stop (http-kit/run-server (handler) {:port port})]
     (reset! !server stop)
     (println (str "secure Ring example on http://localhost:" port "/app"))
     stop)))

(defn stop! []
  (when-let [stop @!server] (stop) (reset! !server nil)))

(defn -main [& _args]
  (start!)
  @(promise))

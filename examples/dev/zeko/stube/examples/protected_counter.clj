(ns dev.zeko.stube.examples.protected-counter
  "Session-protected counter — Seaside's `WASessionProtectedCounter`.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/protected-counter>.

  Two layers of protection cooperate here:

  * **Framework layer** — stube binds every cid to the browser's
    `stube_sid` cookie, so another browser cannot POST events into this
    conversation.
  * **Application layer** — the embedder declares a `:principal-fn`
    when constructing the kernel; that principal is stamped onto the
    conversation at mint time and surfaced through `(s/principal)`.
    Components decide what to render based on whether a principal is
    present.

  The standalone server in this example fakes a tiny session store via
  query string (`?user=ada`) so the demo stays self-contained.  A real
  host would read the principal out of its own session middleware in
  `:principal-fn` instead."
  (:require [dev.zeko.stube.core :as s]))

;; ---------------------------------------------------------------------------
;; Fake host session — replace with your auth middleware in a real app.
;; ---------------------------------------------------------------------------
;;
;; This is a stand-in for whatever your host already does to identify
;; the user — Ring session middleware, a JWT, an OAuth flow, etc.  The
;; kernel never sees this code; it only sees the principal that
;; :principal-fn extracts from a request.

(defn- request-principal
  "Read a `?user=` query param off the request as the demo's principal."
  [request]
  (some-> request :query-string
          (->> (re-find #"(?:^|&)user=([^&]+)"))
          second))

;; ---------------------------------------------------------------------------
;; Component
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/protected-counter
  :doc "Counter that is only interactive when the host has a logged-in principal."

  :init (constantly {:n 0})

  :render
  (fn [self]
    [:section (s/root-attrs self
                {:class "stube-card"
                 :style "max-width:28rem; margin:1rem; font-family:system-ui, sans-serif;"})
     [:h2 {:style "margin-top:0;"} "Protected counter"]
     (if-let [user (s/principal)]
       [:div
        [:p "Signed in as " [:strong user] "."]
        [:div {:style "font-size:2rem; font-variant-numeric:tabular-nums;"}
         (:n self)]
        [:div {:class "stube-actions"}
         [:button (merge {:type "button" :class "stube-button"}
                         (s/on self :click :as :dec))
          "−"]
         [:button (merge {:type "button" :class "stube-button stube-button--primary"}
                         (s/on self :click :as :inc))
          "+"]]
        [:p {:style "color:#666; font-size:0.9rem;"}
         "Try copying this page's POST URL into another browser session: "
         "the framework-level owner cookie rejects it before the handler runs."]]
       [:div
        [:p "This page is only available to a signed-in user."]
        [:p {:style "color:#555;"}
         "In a real host, this branch would link to the host's login flow.  "
         "For this demo, append "
         [:code "?user=ada"]
         " to the URL and reload to mint a fresh conversation with a principal."]])])

  :handle
  (fn [self {:keys [event]}]
    (case event
      :inc (update self :n inc)
      :dec (update self :n dec)
      nil)))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/protected-counter" :demo/protected-counter)

(defn -main [& _args]
  (s/start! {:port           8080
             :principal-fn   request-principal})
  @(promise))

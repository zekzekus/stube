(ns dev.zeko.stube.examples.protected-counter
  "Session-protected counter — Seaside's `WASessionProtectedCounter`.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/protected-counter>.

  stube already binds every cid to the browser's `stube_sid` cookie, so
  another browser cannot post events into this conversation.  The app's
  own authenticated principal is ordinary conversation state here; a
  host app with real auth would pass/verify that principal at its own
  boundary rather than asking stube to invent an auth model."
  (:require [clojure.string :as str]
            [dev.zeko.stube.core :as s]))

(s/defcomponent :demo/protected-counter
  :doc "WASessionProtectedCounter port: app-level login composed with stube's cid owner cookie."

  :init (constantly {:user nil :user-name "" :n 0})
  :keep #{:user-name}

  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :class "stube-card"
               :style "max-width:28rem; margin:1rem; font-family:system-ui, sans-serif;"}
     [:h2 {:style "margin-top:0;"} "Protected counter"]
     (if-let [user (:user self)]
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
          "+"]
         [:button (merge {:type "button" :class "stube-button"}
                         (s/on self :click :as :logout))
          "Log out"]]
        [:p {:style "color:#666; font-size:0.9rem;"}
         "Try copying this page's POST URL into another browser session: "
         "the framework-level owner cookie rejects it before the handler runs."]]
       [:form (merge {:style "display:grid; gap:0.75rem;"}
                     (s/on self :submit :as :login))
        [:p {:style "color:#555;"}
         "Enter any name to bind this conversation to an app-level principal."]
        [:label
         [:span {:class "stube-label"} "User"]
         [:input (merge {:class "stube-input" :name "user-name"
                         :value (:user-name self)
                         :placeholder "ada"}
                        (s/local-bind self :user-name))]]
        [:button {:type "submit" :class "stube-button stube-button--primary"}
         "Sign in"]])])

  :handle
  (fn [self {:keys [event]}]
    (case event
      :login
      (let [user (str/trim (str (:user-name self)))]
        (if (str/blank? user)
          [self []]
          [(assoc self :user user :user-name "" :n 0) []]))

      :logout [(assoc self :user nil :n 0) []]
      :inc    [(update self :n inc) []]
      :dec    [(update self :n dec) []]
      [self []])))

(s/mount! "/protected-counter" :demo/protected-counter)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

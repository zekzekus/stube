(ns dev.zeko.stube.examples.chat
  "Multi-user chat — Seaside's `CTChat`.

  Run from the project root:

      clojure -M:examples

  Then open <http://localhost:8080/chat> in two browsers.

  Messages are example-level shared data.  Each conversation subscribes
  to the chat topic, and `s/publish!` asynchronously dispatches incoming
  messages back to every subscribed instance."
  (:require [clojure.string :as str]
            [dev.zeko.stube.core :as s])
  (:import (java.time Instant)
           (java.util UUID)))

(defonce ^:private !messages (atom []))
(def ^:private topic :demo.chat/messages)

(defn- subscribe-effects []
  [(s/subscribe topic :chat-message)])

(defn- new-message [name text]
  {:id   (str (UUID/randomUUID))
   :name (if (str/blank? name) "anonymous" (str/trim name))
   :text (str/trim text)
   :at   (str (Instant/now))})

(defn- remember [messages msg]
  (if (some #(= (:id %) (:id msg)) messages)
    messages
    (->> (conj messages msg)
         (take-last 30)
         vec)))

(defn- publish-message! [msg]
  (swap! !messages remember msg)
  (s/publish! topic msg))

(s/defcomponent :demo/chat
  :doc "CTChat port: shared messages plus per-conversation pub/sub delivery."

  :init (constantly {:name "Ada" :draft "" :messages @!messages})
  :keep #{:name :draft}
  :start  (fn [_self] (subscribe-effects))
  :wakeup (fn [self] [(assoc self :messages @!messages) (subscribe-effects)])
  :stop   (fn [_self] [(s/unsubscribe topic)])

  :render
  (fn [self]
    [:section (s/root-attrs self {:class "stube-card"
                                  :style "max-width:42rem; margin:1rem; font-family:system-ui, sans-serif;"})
     [:h2 {:style "margin-top:0;"} "Chat"]
     [:p {:style "color:#555;"}
      "Open the standalone " [:code "/chat"] " URL in two tabs.  Each visit "
      "gets its own conversation id, but both subscribe to " [:code topic]
      " and receive " [:code ":chat-message"] " events."]
     [:ol {:style "min-height:12rem; max-height:18rem; overflow:auto;
                    padding-left:1.25rem; border:1px solid #ddd; padding-top:0.5rem;"}
      (for [{:keys [id name text at]} (:messages self)]
        [:li {:key id :style "margin-bottom:0.6rem;"}
         [:strong name] " " [:small {:style "color:#777;"} at]
         [:div text]])]
     [:form (merge {:style "display:grid; grid-template-columns:8rem 1fr auto;
                            gap:0.5rem; align-items:end; margin-top:0.75rem;"}
                   (s/on self :submit :as :send))
      [:label
       [:span {:class "stube-label"} "Name"]
       [:input (merge {:class "stube-input" :name "name" :value (:name self)}
                      (s/local-bind self :name))]]
      [:label
       [:span {:class "stube-label"} "Message"]
       [:input (merge {:class "stube-input" :name "draft" :value (:draft self)
                       :placeholder "Say hello"}
                      (s/local-bind self :draft))]]
      [:button {:type "submit" :class "stube-button stube-button--primary"}
       "Send"]]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :send
      (let [text (str/trim (str (:draft self)))]
        (when-not (str/blank? text)
          (let [msg (new-message (:name self) text)]
            [(-> self
                 (assoc :draft "")
                 (update :messages remember msg))
             [(s/io #(publish-message! msg))]])))

      :chat-message
      (update self :messages remember payload)

      nil)))

(s/mount! "/chat" :demo/chat)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

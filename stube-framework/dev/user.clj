(ns user
  "REPL entry point. Auto-loaded by Clojure when `dev/` is on the
  classpath — start the REPL with the `:dev` alias active so this
  file (and the `examples/` path) are picked up, e.g.

      clj -A:dev:<your-global-repl-alias>

  Quick start at the REPL:

      (go)        ; require all examples + start server on :8080
      (browse)    ; open http://localhost:8080/ in a browser
      (mounts)    ; inspect the URL → component map
      (convs)     ; list live conversation ids
      (inspect <cid>)
      (reset)     ; wipe in-memory state and stop the server
      (restart)   ; reset + go

      (portal)    ; open Portal inspector + route tap> there
      (tap> (inspect (first (convs))))
      (portal-clear)
      (portal-close)"
  (:require [clojure.java.browse :as browse]
            [clojure.repl        :refer :all]
            [clojure.pprint      :refer [pprint]]
            [dev.zeko.stube.core   :as s]
            [dev.zeko.stube.server :as server]))

(def ^:dynamic *port* 8080)

(defn load-examples
  "Require every shipped example namespace. Each one mounts itself at
  load time, so after this call the demo URLs are live as soon as the
  server is started."
  []
  (require 'dev.zeko.stube.examples.main)
  :loaded)

(defn go
  "Load the examples and start the server. Idempotent — calling it
  twice does not double-mount."
  ([] (go *port*))
  ([port]
   (load-examples)
   (s/start! {:port port})
   (println (str "stube examples up — http://localhost:" port "/"))
   :started))

(defn stop  [] (s/stop!))
(defn reset [] (server/reset-state!))

(defn restart
  ([] (restart *port*))
  ([port]
   (reset)
   (go port)))

(defn browse
  ([] (browse "/"))
  ([path]
   (browse/browse-url (str "http://localhost:" *port* path))))

(defn mounts  [] (s/mounts))
(defn convs   [] (keys (s/active-conversations)))
(defn inspect [cid] (s/inspect cid))

;; ---------------------------------------------------------------------------
;; Portal inspector — available when started with the global
;; `:repl/inspect` (or `:repl/reloaded`) alias, which puts djblue/portal
;; on the classpath.  `requiring-resolve` keeps this file loadable even
;; under leaner REPL profiles that don't pull Portal in.

(defonce ^:private !portal (atom nil))
(defonce ^:private !tap    (atom nil))

(defn- portal-fn [sym]
  (or (requiring-resolve (symbol "portal.api" (name sym)))
      (throw (ex-info "Portal not on the classpath — start the REPL with :repl/inspect or :repl/reloaded"
                      {:sym sym}))))

(defn portal
  "Open a Portal window (idempotent) and route `tap>` to it.
  Returns the portal instance — `(tap> @portal)` to inspect it.

  Pass an options map to forward to `portal.api/open`, e.g.
  `(portal {:theme :portal.colors/nord})`."
  ([] (portal nil))
  ([opts]
   (let [open (portal-fn 'open)
         submit (portal-fn 'submit)
         inst (or @!portal (reset! !portal (if opts (open opts) (open))))]
     (when-not @!tap
       (add-tap submit)
       (reset! !tap submit))
     inst)))

(defn portal-clear
  "Clear all values from the open Portal window."
  []
  ((portal-fn 'clear)))

(defn portal-close
  "Close Portal and detach the tap. Safe to call when nothing is open."
  []
  (when-let [t @!tap]    (remove-tap t)  (reset! !tap nil))
  (when @!portal         ((portal-fn 'close)) (reset! !portal nil))
  :closed)

(comment
  (go)
  (browse)
  (mounts)
  (convs)
  (inspect <cid>)
  (reset)
  (restart)

  (portal)
  (tap> (mounts))
  (tap> (convs))
  (tap> (inspect (first (convs))))
  (portal-clear)
  (portal-close))

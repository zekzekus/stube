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
  twice does not double-mount.

  Accepts a port (legacy 1-arg) or an option map merged into the start!
  options. Examples:

      (go)
      (go 8081)
      (go {:halos? true})
      (go {:port 8081 :halos? true})"
  ([] (go {}))
  ([port-or-opts]
   (let [opts (cond
                (integer? port-or-opts) {:port port-or-opts}
                (map?     port-or-opts) port-or-opts
                :else (throw (ex-info "go expects a port or an opts map"
                                      {:got port-or-opts})))
         port (or (:port opts) *port*)]
     (load-examples)
     (s/start! (merge {:port port} opts))
     (println (str "stube examples up — http://localhost:" port "/"
                   (when (:halos? opts) "  (halos enabled; add ?halos=1)")))
     :started)))

(defn stop  [] (s/stop!))
(defn reset [] (server/reset-state!))

(defn restart
  ([] (restart {}))
  ([port-or-opts]
   (reset)
   (go port-or-opts)))

(defn browse
  ([] (browse "/"))
  ([path]
   (browse/browse-url (str "http://localhost:" *port* path))))

(defn mounts  [] (s/mounts))
(defn convs   [] (keys (s/active-conversations)))
(defn inspect [cid] (s/inspect cid))

;; ---------------------------------------------------------------------------
;; Halos — dev overlay (see docs/halos-spike.md)
;; ---------------------------------------------------------------------------
;;
;; Quick path:
;;
;;     (go-halos)              ; restart with halos enabled
;;     (browse-halos)          ; open the example browser with ?halos=1
;;     (browse-halos "/multicounter")
;;     (tree)                  ; tree of the first live conv
;;     (history)               ; history snapshots of the first live conv
;;     (where :demo/counter)   ; file:line of a defcomponent

(defn go-halos
  "Like `go`, but enables the dev halos overlay."
  ([]          (go-halos *port*))
  ([port]      (go (cond-> {:halos? true}
                     (integer? port) (assoc :port port)
                     (map? port)     (merge port)))))

(defn restart-halos
  "Reset + go-halos."
  ([]     (reset) (go-halos))
  ([port] (reset) (go-halos port)))

(defn browse-halos
  "Open a mounted path with `?halos=1` so the overlay activates."
  ([] (browse-halos "/"))
  ([path]
   (let [sep (if (re-find #"\?" path) "&" "?")]
     (browse/browse-url (str "http://localhost:" *port* path sep "halos=1")))))

(defn- first-cid []
  (or (first (convs))
      (throw (ex-info "no live conversations — visit a mounted URL first" {}))))

(defn tree
  "Pretty-print the component tree for `cid` (defaults to the first
  live conversation)."
  ([]    (tree (first-cid)))
  ([cid] (s/tree cid)))

(defn history
  "Summarise `:conv/history` for `cid` (defaults to the first live
  conversation)."
  ([]    (history (first-cid)))
  ([cid] (s/conv-history cid)))

(defn where
  "Return the file:line where component `type-kw` was defined."
  [type-kw]
  (s/where type-kw))

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

  ;; Halos dev overlay
  (go-halos)
  (browse-halos "/multicounter")
  (tree)
  (history)
  (where :demo/counter)
  (restart-halos)

  (portal)
  (tap> (mounts))
  (tap> (convs))
  (tap> (inspect (first (convs))))
  (portal-clear)
  (portal-close))

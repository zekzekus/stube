(ns dev.zeko.stube.examples.main
  "Loads every shipped example onto a single server.

      clojure -M:examples

  After startup, visit <http://localhost:8080/> for the example browser.

  The example-browser namespace requires every demo and mounts the
  interactive landing page.  This namespace only starts the shared
  server entry point used by `clojure -M:examples`.

  See `seaside-examples.md` for the full curated list of Seaside apps
  and which ones drive new framework functionality."
  (:require [dev.zeko.stube.core              :as s]
            [dev.zeko.stube.examples.example-browser]))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn- request-user-principal
  "Read `?user=` off the request as the demo's principal.  The
  protected-counter example needs a principal-fn on the kernel to flip
  into its signed-in branch when you visit
  `/protected-counter?user=ada`; other demos ignore the value."
  [request]
  (some-> request :query-string
          (->> (re-find #"(?:^|&)user=([^&]+)"))
          second))

(defn -main [& _args]
  (s/start! {:port         8080
             :principal-fn request-user-principal})
  (println "stube examples up — visit http://localhost:8080/ for the example browser")
  @(promise))

(ns dev.zeko.stube.examples.kasten.main
  "Standalone entry for the kasten UI port. Run with:

      clojure -M -m dev.zeko.stube.examples.kasten.main

  Then visit <http://localhost:8080/kasten>."
  (:require [dev.zeko.stube.core :as s]
            ;; The require below registers the components and the
            ;; `(s/mount! \"/kasten\" :kasten/desk)` form.
            [dev.zeko.stube.examples.kasten.desk]))

(defn -main [& _args]
  (s/start! {:port 8080 :ui-css? false})
  (println "kasten up — visit http://localhost:8080/kasten")
  @(promise))

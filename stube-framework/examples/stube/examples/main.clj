(ns stube.examples.main
  "Loads every shipped example onto a single server.

      clojure -M:examples

  After startup:

  | URL                                       | demo                              | introduces      |
  |-------------------------------------------|-----------------------------------|-----------------|
  | <http://localhost:8080/guess>             | `defflow` number-guessing wizard  | slice 1         |
  | <http://localhost:8080/multicounter>      | three embedded counters           | slice 2         |
  | <http://localhost:8080/wizard>            | three-step wizard with Back       | slice 3         |

  Each example file does its own `s/mount!` at load time; this
  namespace just `require`s them and starts the server."
  (:require [stube.core              :as s]
            [stube.examples.guess]
            [stube.examples.multicounter]
            [stube.examples.wizard]))

(defn -main [& _args]
  (s/start! {:port 8080})
  (println "stube examples up — try:")
  (println "  http://localhost:8080/guess")
  (println "  http://localhost:8080/multicounter")
  (println "  http://localhost:8080/wizard")
  @(promise))

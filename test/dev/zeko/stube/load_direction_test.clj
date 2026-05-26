(ns dev.zeko.stube.load-direction-test
  "Enforces the pure/impure split that makes `s/dispatch` and
  `s/replay` testable without a server running.

  The kernel layer (conversation/effects/fragments/kernel/frame/
  lifecycle/registry/render and the doc/error/dev helpers they pull)
  must not transitively `:require` the runtime, the standalone
  server, the http layer, the adapters, or the kit Integrant glue.
  Adding such a require would not break any other test today, but
  it would silently destroy the load-direction invariant.  This
  test catches the regression at the first run."
  (:require [clojure.test :refer [deftest is testing]]))

(def pure-namespaces
  "Namespaces that must remain side-effect-free at load time.

  `render` and `frame` are included even though they pull `halos` and
  `chassis` — neither has a runtime/server/http hop, so they're still
  on the pure side of the seam."
  '#{dev.zeko.stube.conversation
     dev.zeko.stube.dev
     dev.zeko.stube.effects
     dev.zeko.stube.errors
     dev.zeko.stube.fragments
     dev.zeko.stube.frame
     dev.zeko.stube.keyed
     dev.zeko.stube.kernel
     dev.zeko.stube.lifecycle
     dev.zeko.stube.registry
     dev.zeko.stube.render})

(def impure-namespaces
  "Namespaces that pull I/O (atoms over live state, Ring handlers,
  http-kit, persistence) and so must not load from the pure side."
  '#{dev.zeko.stube.adapter.ring
     dev.zeko.stube.embed
     dev.zeko.stube.halos.http
     dev.zeko.stube.http
     dev.zeko.stube.kit
     dev.zeko.stube.runtime
     dev.zeko.stube.server})

(defn- transitive-aliases [ns-sym]
  (require ns-sym)
  (let [seen (atom #{})]
    (letfn [(walk [n]
              (when (and n (not (@seen n)))
                (swap! seen conj n)
                (doseq [^clojure.lang.Namespace target (vals (ns-aliases (find-ns n)))]
                  (walk (.getName target)))))]
      (walk ns-sym))
    @seen))

(deftest pure-namespaces-do-not-require-impure-namespaces
  (doseq [pure pure-namespaces]
    (testing (str pure " (transitive :require graph)")
      (let [loaded (transitive-aliases pure)]
        (doseq [imp impure-namespaces]
          (is (not (contains? loaded imp))
              (str pure " transitively requires forbidden " imp)))))))

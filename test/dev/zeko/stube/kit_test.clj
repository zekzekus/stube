(ns dev.zeko.stube.kit-test
  "Smoke test for the Integrant adapter.

  Mirrors the three-line `system.edn` from the README: build a config,
  init it, walk the produced route table, halt cleanly.  This stands in
  for the acceptance criterion 'a minimal kit-generated app mounts
  :stube/kernel and :reitit.routes/stube' without depending on the kit
  generator itself."
  (:require [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]
            [reitit.ring :as ring]
            [dev.zeko.stube.kernel :as stube]
            [dev.zeko.stube.kit] ;; registers ig multimethods
            ))

(defn- config []
  {:stube/kernel
   {:base-path     "/stube"
    :context-fn    (constantly {:db ::stub})
    :session-id-fn (constantly "sid")}

   :reitit.routes/stube
   {:kernel (ig/ref :stube/kernel)}})

(deftest init-and-halt-roundtrip
  (let [system (ig/init (config))]
    (try
      (testing ":stube/kernel produces a usable kernel value"
        (let [k (:stube/kernel system)]
          (is (= "/stube" (stube/base-path k)))
          (is (some? (stube/current-store k)))))

      (testing ":reitit.routes/stube returns kit-shaped [base opts children]"
        (let [[base opts children] (:reitit.routes/stube system)]
          (is (= "" base) "kernel owns the prefix; routes mount at empty root")
          (is (map? opts))
          (is (vector? children))
          (is (some (fn [[path _]] (= "/stube/sse/:cid" path)) children)
              "child routes are already fully prefixed by the kernel base-path")))

      (testing "a built reitit router serves the stube assets"
        (let [[_ _ children] (:reitit.routes/stube system)
              handler (ring/ring-handler (ring/router (vec children))
                                         (ring/create-default-handler))
              resp    (handler {:request-method :get :uri "/stube/stube/ui.css"})]
          (is (= 200 (:status resp)))))
      (finally
        (ig/halt! system)))))

(ns ^:no-doc dev.zeko.stube.kit
  "Integrant adapter for embedding stube into kit-clj / Integrant systems.

  Drop these into your `system.edn`:

      :stube/kernel
      {:base-path     \"/stube\"
       :context-fn    #ig/ref :app/context-fn
       :session-id-fn #ig/ref :app/session-id-fn}

      :reitit.routes/stube
      {:kernel #ig/ref :stube/kernel}

  `integrant.core` is required only here.  stube core does not depend
  on Integrant, so apps that don't use kit-clj pay zero cost — they
  simply never `require` this namespace.

  See [[dev.zeko.stube.embed/make-kernel]] for the full option set
  accepted by `:stube/kernel`.

  `^:no-doc` on the ns form keeps cljdoc-analyzer from requiring this
  namespace at build time; analysis runs without integrant on the
  classpath, and consumers using the adapter pull integrant in via
  their own deps anyway."
  (:require [integrant.core              :as ig]
            [dev.zeko.stube.adapter.ring :as stube-ring]
            [dev.zeko.stube.embed        :as stube]))

(defmethod ig/init-key :stube/kernel
  [_ opts]
  (stube/make-kernel opts))

(defmethod ig/halt-key! :stube/kernel
  [_ kernel]
  (stube/halt! kernel))

(defn route-data
  "Build Reitit route data for a configured kernel.

  Returns the `[base-path opts children]` shape kit-clj expects from
  `:reitit/routes`-derived components.  stube generates fully-prefixed
  paths from the kernel's `:base-path`, so we mount them at the empty
  root and let the kernel own the prefix end-to-end.

  Pass additional `ring-routes` options (e.g. `:mounts`) through opts;
  `:kernel` is stripped before forwarding."
  [{:keys [kernel] :as opts}]
  ["" {} (stube-ring/ring-routes kernel (dissoc opts :kernel))])

(derive :reitit.routes/stube :reitit/routes)

(defmethod ig/init-key :reitit.routes/stube
  [_ opts]
  (route-data opts))

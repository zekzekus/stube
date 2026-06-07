(ns <<ns-name>>.stube
  "Starter stube components for this app.

  A stube component is plain data — a map of pure functions registered
  in stube's process-global registry by `s/defcomponent`.  This
  namespace is required from `<<ns-name>>.core`, so the component below
  is registered before the Integrant system starts; `:reitit.routes/stube`
  in `resources/system.edn` then mounts it at \"/stube\".

  To add your own page, define another component here (or in a sibling
  namespace you also require from `core`) and add an entry to the
  `:mounts` map of `:reitit.routes/stube`, e.g.

      :mounts {\"/stube\"   :<<name>>/counter
               \"/widgets\" :<<name>>/my-widget}

  Component authors stay in `dev.zeko.stube.core` (aliased `s` here);
  the embedding/Integrant wiring lives in `dev.zeko.stube.kit`."
  (:require [dev.zeko.stube.core :as s]))

;; A minimal call/answer-free counter: one component, no JavaScript,
;; no client/server contract to hand-maintain.  Open /stube to see it.
(s/defcomponent :<<name>>/counter
  :init   (fn [_] {:n 0})
  :render (fn [self]
            [:main {:style "max-width: 40rem; margin: 4rem auto; font-family: system-ui, sans-serif; line-height: 1.5"}
             [:h1 "stube is wired up 🎉"]
             [:p "This page is served by a stube widget embedded in your Kit app. "
              "Edit " [:code "src/clj/<<sanitized>>/stube.clj"] " to build your own components, "
              "or adjust the mount in " [:code "resources/system.edn"] "."]
             [:section (s/root-attrs self {:style "display: flex; align-items: center; gap: 1rem; margin-top: 2rem"})
              [:button (s/on self :click :as :dec)
               {:style "font-size: 1.5rem; width: 2.5rem; height: 2.5rem"} "−"]
              [:strong {:style "font-size: 1.5rem; min-width: 2rem; text-align: center"} (:n self)]
              [:button (s/on self :click :as :inc)
               {:style "font-size: 1.5rem; width: 2.5rem; height: 2.5rem"} "+"]]])
  :handle (fn [self {:keys [event]}]
            (case event
              :inc (update self :n inc)
              :dec (update self :n dec)
              self)))

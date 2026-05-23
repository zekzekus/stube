(ns dev.zeko.stube.shell
  "The HTML shell page served on the first GET of a mounted flow.

  The shell is effectively empty: a `<div id=\"root\">` placeholder
  plus a `data-init` that opens the long-lived SSE stream.  Once the
  browser connects, every UI patch arrives through that stream — so
  this namespace knows nothing about components or rendering."
  (:require [dev.onionpancakes.chassis.core      :as chassis]
            [starfederation.datastar.clojure.api :as d*]
            [dev.zeko.stube.render               :as render]))

(def datastar-cdn d*/CDN-url)

(defn- root-id [selector]
  (if (and (string? selector) (.startsWith selector "#"))
    (subs selector 1)
    "root"))

(defn fragment
  "Hiccup fragment a host page can slot into its own layout.  It opens
  the SSE stream and contains the DOM target for the first stube patch."
  [cid {:keys [dev? base-path route-style root-selector]
        :or {base-path "" route-style :legacy root-selector "#root"}}]
  (binding [render/*base-path* base-path
            render/*route-style* route-style
            render/*root-selector* root-selector]
    [:div (cond-> {:data-init (str "@get('" (render/sse-url cid) "')")}
            dev? (assoc :data-stube-cid cid
                        :data-stube-base-path base-path))
     [:div {:id (root-id root-selector)}]]))

(defn html
  "Render the shell document for `cid`.  When `dev?` is true (server
  started with `:halos? true`), inject the halos overlay script and the
  `data-stube-cid` hook so the floating pill can activate the overlay."
  [cid opts-or-dev?]
  (let [{:keys [dev? ui-css? base-path route-style root-selector]
         :or {ui-css? true base-path "" route-style :legacy root-selector "#root"}}
        (if (map? opts-or-dev?)
          opts-or-dev?
          {:dev? opts-or-dev?})]
  ;; `data-init` runs once when Datastar processes the element after
  ;; the page loads.  We open the long-lived SSE stream there; every
  ;; patch the kernel pushes thereafter morphs into the DOM by id,
  ;; starting with the `<div id="root">` placeholder below.
    (binding [render/*base-path* base-path
              render/*route-style* route-style
              render/*root-selector* root-selector]
      (let [[_ body-attrs root] (fragment cid {:dev? dev?
                                               :base-path base-path
                                               :route-style route-style
                                               :root-selector root-selector})]
        (chassis/html
          [chassis/doctype-html5
           [:html {:lang "en"}
            [:head
             [:meta {:charset "utf-8"}]
             [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
             [:title "stube"]
             (when ui-css?
               [:link {:rel "stylesheet" :href (render/ui-css-url)}])
             [:script {:type "module" :src (render/preserve-js-url)}]
             [:script {:type "module" :src datastar-cdn}]
             (when dev?
               [:script {:type "module" :src (render/halos-js-url)}])]
            [:body body-attrs root]]])))))

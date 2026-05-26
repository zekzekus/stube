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
  [cid {:keys [dev? base-path root-selector]
        :or {base-path "" root-selector "#root"}}]
  (binding [render/*base-path* base-path
            render/*root-selector* root-selector]
    [:div (cond-> {:data-init (str "@get('" (render/sse-url cid) "')")}
            dev? (assoc :data-stube-cid cid
                        :data-stube-base-path base-path))
     [:div {:id (root-id root-selector)}]]))

(defn head-tags
  "Hiccup nodes a host page should include in `<head>` for a stube shell
  fragment: optional stock CSS, the preserve bridge, Datastar, and
  optional halos tooling.

  Standalone [[html]] uses this directly; embedders normally call the
  public `dev.zeko.stube.embed/head-tags` wrapper for their kernel."
  [{:keys [dev? ui-css? base-path root-selector]
    :or {ui-css? true base-path "" root-selector "#root"}}]
  (binding [render/*base-path* base-path
            render/*root-selector* root-selector]
    (cond-> []
      ui-css? (conj [:link {:rel "stylesheet" :href (render/ui-css-url)}])
      true    (conj [:script {:type "module" :src (render/preserve-js-url)}]
                    [:script {:type "module" :src datastar-cdn}])
      dev?    (conj [:script {:type "module" :src (render/halos-js-url)}]))))

(defn html
  "Render the shell document for `cid`.  When `dev?` is true (server
  started with `:halos? true`), inject the halos overlay script and the
  `data-stube-cid` hook so the floating pill can activate the overlay."
  [cid opts-or-dev?]
  (let [{:keys [dev? ui-css? base-path root-selector]
         :or {ui-css? true base-path "" root-selector "#root"}}
        (if (map? opts-or-dev?)
          opts-or-dev?
          {:dev? opts-or-dev?})]
  ;; `data-init` runs once when Datastar processes the element after
  ;; the page loads.  We open the long-lived SSE stream there; every
  ;; patch the kernel pushes thereafter morphs into the DOM by id,
  ;; starting with the `<div id="root">` placeholder below.
    (binding [render/*base-path* base-path
              render/*root-selector* root-selector]
      (let [[_ body-attrs root] (fragment cid {:dev? dev?
                                               :base-path base-path
                                               :root-selector root-selector})
            assets (head-tags {:dev? dev?
                               :ui-css? ui-css?
                               :base-path base-path
                               :root-selector root-selector})]
        (chassis/html
          [chassis/doctype-html5
           [:html {:lang "en"}
            (into [:head
                   [:meta {:charset "utf-8"}]
                   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                   [:title "stube"]]
                  assets)
            [:body body-attrs root]]])))))

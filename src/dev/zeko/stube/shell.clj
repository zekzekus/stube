(ns dev.zeko.stube.shell
  "The HTML shell page served on the first GET of a mounted flow.

  The shell is effectively empty: a `<div id=\"root\">` placeholder
  plus a `data-init` that opens the long-lived SSE stream.  Once the
  browser connects, every UI patch arrives through that stream — so
  this namespace knows nothing about components or rendering."
  (:require [dev.onionpancakes.chassis.core      :as chassis]
            [starfederation.datastar.clojure.api :as d*]
            [dev.zeko.stube.server               :as server]))

(def datastar-cdn d*/CDN-url)
(def ui-css-path "/stube/ui.css")
(def halos-js-path "/stube/halos.js")

(defn html
  "Render the shell document for `cid`.  When `dev?` is true (server
  started with `:halos? true`), inject the halos overlay script and the
  `data-stube-cid` hook so the floating pill can activate the overlay."
  [cid dev?]
  ;; `data-init` runs once when Datastar processes the element after
  ;; the page loads.  We open the long-lived SSE stream there; every
  ;; patch the kernel pushes thereafter morphs into the DOM by id,
  ;; starting with the `<div id="root">` placeholder below.
  (chassis/html
    [chassis/doctype-html5
     [:html {:lang "en"}
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
       [:title "stube"]
       (when (server/ui-css?)
         [:link {:rel "stylesheet" :href ui-css-path}])
       [:script {:type "module" :src datastar-cdn}]
       (when dev?
         [:script {:type "module" :src halos-js-path}])]
      [:body (cond-> {:data-init (str "@get('/conv/" cid "/sse')")}
               dev? (assoc :data-stube-cid cid))
       [:div {:id "root"}]]]]))

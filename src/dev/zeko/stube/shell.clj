(ns dev.zeko.stube.shell
  "The HTML shell page served on the first GET of a mounted flow.

  The shell is effectively empty: a `<div id=\"root\">` placeholder
  plus a `data-init` that opens the long-lived SSE stream.  Once the
  browser connects, every UI patch arrives through that stream — so
  this namespace knows nothing about components or rendering."
  (:require [clojure.java.io                     :as io]
            [clojure.string                      :as str]
            [dev.onionpancakes.chassis.core      :as chassis]
            [starfederation.datastar.clojure.api :as d*]
            [dev.zeko.stube.registry             :as registry]
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
    [:div (cond-> {:data-init (str "@get('" (render/sse-url cid) "')")
                   :data-stube-base-path base-path}
            dev? (assoc :data-stube-cid cid))
     [:div {:id (root-id root-selector)}]]))

(defn- component-stylesheet-resource [type-kw]
  (str "stube_styles/" (namespace type-kw) "/" (name type-kw) ".css"))

(defn- component-has-stylesheet? [type-kw]
  (some? (io/resource (component-stylesheet-resource type-kw))))

(defn- scope-inline-styles
  "Replace bare `&` with the component selector so author CSS can write
  nested rules without spelling out the data-attribute everywhere.  `&`
  occurring inside a longer identifier (e.g. `&&`) is left alone."
  [type-kw css]
  (let [selector (str "[data-stube-component=\""
                      (render/component-slug type-kw)
                      "\"]")]
    (str/replace css #"&" selector)))

(defn- collect-stylesheet-links []
  (->> (registry/all)
       vals
       (keep (fn [{:component/keys [id]}]
               (when (and id (component-has-stylesheet? id))
                 [:link {:rel "stylesheet"
                         :href (render/component-style-url id)}])))
       (sort-by (comp :href second))))

(defn- collect-module-scripts []
  (->> (registry/all)
       vals
       (mapcat :component/modules)
       distinct
       sort
       (map (fn [module-id]
              [:script {:type "module"
                        :src (render/component-module-url module-id)}]))))

(defn- collect-inline-styles []
  (let [chunks (->> (registry/all)
                    vals
                    (sort-by :component/id)
                    (keep (fn [{:component/keys [id styles]}]
                            (when (and id styles)
                              (scope-inline-styles id styles)))))]
    (when (seq chunks)
      [:style {:type "text/css"} (str/join "\n" chunks)])))

(defn- base-css-links [urls]
  (->> urls
       (filter string?)
       (filter seq)
       (map (fn [href] [:link {:rel "stylesheet" :href href}]))))

(defn- eager-script-block [snippets]
  (let [chunks (->> snippets
                    (filter string?)
                    (filter seq))]
    (when (seq chunks)
      ;; Wrap the body in `chassis/raw` so quotes inside a JSON literal
      ;; aren't HTML-escaped to `&quot;` (which breaks the script with
      ;; `Unexpected token '&'`). `:eager-scripts` are documented as
      ;; "emitted verbatim"; the host owns the contents.
      [:script (chassis/raw (str/join "\n" chunks))])))

(defn head-tags
  "Hiccup nodes a host page should include in `<head>` for a stube shell
  fragment, in this order:

  1. Optional stock `ui.css`.
  2. Kernel-level `:base-css` `<link>`s (host-wide stylesheets that
     should appear regardless of which components are registered).
  3. One `<link>` per registered component that ships a stylesheet at
     `resources/stube_styles/<ns>/<name>.css` (discovered via
     `io/resource`).
  4. One `<style>` block holding inline `:styles` from every registered
     component, with each chunk's `&` prefix scoped to the matching
     `[data-stube-component=\"ns/name\"]` selector.
  5. Kernel-level `:eager-scripts` as a single synchronous `<script>`
     block — emitted *before* any `type=\"module\"` script so inline
     Datastar expressions can rely on the globals it sets up.
  6. Datastar and the framework bridges (`preserve.js`, `behaviors.js`).
  7. One `<script type=\"module\">` per distinct module id declared by a
     component's `:modules` vector, served from
     `resources/stube_modules/<id>.js`.
  8. Optional halos tooling when `:dev?` is true.

  Standalone [[html]] uses this directly; embedders normally call the
  public `dev.zeko.stube.embed/head-tags` wrapper for their kernel.

  Renderer constraint: the returned tree only renders correctly through
  chassis.  Inline `<script>` / `<style>` bodies are wrapped in chassis
  `RawString` so quotes and JSON literals survive verbatim; hosts using
  hiccup2 / rum / reagent SSR must re-wrap those instances in their
  renderer's own raw primitive before emitting, or the bodies will be
  HTML-escaped and the scripts will fail to parse."
  [{:keys [dev? ui-css? base-css eager-scripts base-path root-selector]
    :or {ui-css? true base-css [] eager-scripts [] base-path "" root-selector "#root"}}]
  (binding [render/*base-path* base-path
            render/*root-selector* root-selector]
    (let [stylesheet-links (collect-stylesheet-links)
          inline-styles    (collect-inline-styles)
          module-scripts   (collect-module-scripts)
          base-links       (base-css-links base-css)
          eager-block      (eager-script-block eager-scripts)]
      (cond-> []
        ui-css? (conj [:link {:rel "stylesheet" :href (render/ui-css-url)}])

        (seq base-links)       (into base-links)
        (seq stylesheet-links) (into stylesheet-links)
        inline-styles          (conj inline-styles)
        eager-block            (conj eager-block)

        true (conj [:script {:type "module"
                             :src (render/preserve-js-url)
                             :data-stube-base-path base-path}]
                   [:script {:type "module"
                             :src (render/behaviors-js-url)
                             :data-stube-base-path base-path}]
                   [:script {:type "module" :src datastar-cdn}])

        (seq module-scripts) (into module-scripts)

        dev? (conj [:script {:type "module" :src (render/halos-js-url)}])))))

(defn html
  "Render the shell document for `cid`.  When `dev?` is true (server
  started with `:halos? true`), inject the halos overlay script and the
  `data-stube-cid` hook so the floating pill can activate the overlay."
  [cid opts-or-dev?]
  (let [{:keys [dev? ui-css? base-css eager-scripts base-path root-selector]
         :or {ui-css? true base-css [] eager-scripts [] base-path "" root-selector "#root"}}
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
                               :base-css base-css
                               :eager-scripts eager-scripts
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

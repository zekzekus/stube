(ns dev.zeko.stube.shell-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.onionpancakes.chassis.core :as chassis]
            [dev.zeko.stube.registry :as registry]
            [dev.zeko.stube.shell :as shell]))

(defn- isolated-registry [t]
  (let [snapshot (registry/all)]
    (registry/clear!)
    (try (t)
         (finally
           (registry/clear!)
           (doseq [cdef (vals snapshot)]
             (registry/register! cdef))))))

(use-fixtures :each isolated-registry)

(defn- href-of [node]
  (when (and (vector? node) (= :link (first node)))
    (get-in node [1 :href])))

(defn- script-src [node]
  (when (and (vector? node) (= :script (first node)))
    (get-in node [1 :src])))

(deftest head-tags-emits-preserve-and-behaviors-bridge-with-base-path
  (let [tags     (shell/head-tags {:ui-css? false :base-path "/widget"})
        scripts  (keep script-src tags)
        scripts? (set scripts)]
    (is (contains? scripts? "/widget/preserve.js"))
    (is (contains? scripts? "/widget/behaviors.js"))
    (testing "module scripts carry data-stube-base-path so the bridge can resolve lazy imports"
      (let [behaviors-tag (some (fn [t]
                                  (when (= "/widget/behaviors.js" (script-src t))
                                    t))
                                tags)]
        (is (= "/widget" (get-in behaviors-tag [1 :data-stube-base-path])))))))

(deftest head-tags-emits-stylesheet-link-when-component-css-resource-exists
  (registry/register! {:component/id :test-style/yes})
  (let [tags  (shell/head-tags {:ui-css? false})
        hrefs (set (keep href-of tags))]
    (is (contains? hrefs "/styles/test-style/yes.css")
        "test fixture resource at resources/stube_styles/test-style/yes.css is auto-linked"))
  (registry/register! {:component/id :test-style/missing})
  (let [tags  (shell/head-tags {:ui-css? false})
        hrefs (set (keep href-of tags))]
    (is (not (contains? hrefs "/styles/test-style/missing.css"))
        "components without a stylesheet resource produce no <link>")))

(deftest head-tags-emits-inline-style-block-with-scoped-selector
  (registry/register! {:component/id :test-inline/widget
                       :component/styles "& { display: grid }"})
  (let [tags  (shell/head-tags {:ui-css? false})
        style (some (fn [t] (when (and (vector? t) (= :style (first t))) t)) tags)]
    (is (some? style))
    (is (str/includes? (str (last style))
                       "[data-stube-component=\"test-inline/widget\"] { display: grid }"))))

(deftest head-tags-emits-module-script-per-distinct-module-declaration
  (registry/register! {:component/id :test-mod/a :component/modules ["notes/zoom"]})
  (registry/register! {:component/id :test-mod/b :component/modules ["notes/zoom" "notes/search"]})
  (let [tags    (shell/head-tags {:ui-css? false :base-path ""})
        srcs    (set (keep script-src tags))]
    (is (contains? srcs "/modules/notes/zoom.js"))
    (is (contains? srcs "/modules/notes/search.js"))
    (is (= 1 (count (filter #(= "/modules/notes/zoom.js" %) (keep script-src tags))))
        "duplicate module declarations across components dedupe to one <script>")))

(deftest head-tags-emits-base-css-links-before-component-stylesheets
  (registry/register! {:component/id :test-style/yes})
  (let [tags  (shell/head-tags {:ui-css? false
                                :base-css ["/host/app.css" "/host/extra.css"]})
        hrefs (vec (keep href-of tags))]
    (is (= ["/host/app.css" "/host/extra.css" "/styles/test-style/yes.css"]
           hrefs)
        ":base-css URLs are emitted in order, before component-derived stylesheets")))

(deftest head-tags-skips-blank-base-css-entries
  (let [tags  (shell/head-tags {:ui-css? false :base-css ["" nil "/ok.css"]})
        hrefs (vec (keep href-of tags))]
    (is (= ["/ok.css"] hrefs))))

(deftest head-tags-emits-eager-scripts-before-module-scripts
  (let [tags    (shell/head-tags {:ui-css? false
                                  :eager-scripts ["window.Foo = {a:1};"
                                                  "window.Foo.b = 2;"]
                                  :base-path ""})
        ;; eager block is `[:script <raw-body>]` — no attribute map, so
        ;; its second element isn't a map (module scripts always have
        ;; `{:src ... :type "module"}`).
        eager   (some (fn [t]
                        (when (and (vector? t)
                                   (= :script (first t))
                                   (not (map? (second t))))
                          t))
                      tags)
        eager-body (some-> eager last str)
        eager-idx  (.indexOf ^java.util.List (vec tags) eager)
        first-mod  (some-> (some (fn [t]
                                   (when (and (vector? t)
                                              (= :script (first t))
                                              (= "module" (get-in t [1 :type])))
                                     t))
                                 tags))
        first-mod-idx (.indexOf ^java.util.List (vec tags) first-mod)]
    (is (some? eager))
    (is (str/includes? eager-body "window.Foo"))
    (is (str/includes? eager-body "Foo.b = 2"))
    (is (and (>= eager-idx 0) (>= first-mod-idx 0)))
    (is (< eager-idx first-mod-idx)
        "eager <script> precedes the first type=module bridge")))

(deftest head-tags-omits-eager-block-when-list-is-empty-or-blank
  (let [tags (shell/head-tags {:ui-css? false :eager-scripts [nil "" ""]})]
    (is (not-any? (fn [t]
                    (and (vector? t)
                         (= :script (first t))
                         (not (map? (second t)))))
                  tags))))

(deftest head-tags-eager-scripts-render-quotes-raw
  ;; Regression: a JSON literal inside an eager-script body must reach
  ;; the browser with literal `"` quotes, not `&quot;` entities. Hiccup
  ;; would HTML-escape the body by default; chassis/raw bypasses that.
  (let [tags  (shell/head-tags {:ui-css? false
                                :eager-scripts ["window.X={\"k\":\"v\"};"]})
        html  (str (chassis/html tags))]
    (is (str/includes? html "{\"k\":\"v\"}")
        "JSON quotes must render literally; if `&quot;` appears the host script will SyntaxError")
    (is (not (str/includes? html "&quot;")))))

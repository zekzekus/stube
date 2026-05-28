(ns dev.zeko.stube.shell-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
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

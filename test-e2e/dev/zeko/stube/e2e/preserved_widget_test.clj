(ns dev.zeko.stube.e2e.preserved-widget-test
  "S-3 `s/preserve`: third-party DOM under a preserved host survives
  parent re-renders.  We stamp the canvas with a sentinel attribute,
  click Re-render parent, and confirm the sentinel is still there."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest canvas-survives-parent-rerender
  (with-page [page "/preserved-widget"]
    (fx/wait-text page "h1" "Preserved widget")

    (testing "stamp the canvas DOM"
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "canvas"))
          (.isVisible))
      (.evaluate page
                 "() => document.querySelector('canvas')
                          .setAttribute('data-e2e-stamp', 'kept')"))

    (testing "re-render parent twice; canvas DOM identity is preserved"
      (.click (.locator page "button:has-text(\"Re-render parent\")"))
      (.click (.locator page "button:has-text(\"Re-render parent\")"))
      ;; Parent's renders counter bumps, proving the parent did re-render…
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "section[id^=ix] p:has(strong:has-text(\"Parent renders:\"))"))
          (.containsText "2"))
      ;; …yet the canvas's sentinel attribute is intact.
      (is (= "kept" (.getAttribute (.locator page "canvas") "data-e2e-stamp"))
          "s/preserve kept the canvas DOM node across parent morphs"))))

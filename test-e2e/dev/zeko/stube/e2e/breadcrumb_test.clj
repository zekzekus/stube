(ns dev.zeko.stube.e2e.breadcrumb-test
  "`s/decorate`: the mounted component is the base page wrapped with a
  breadcrumb trail.  Drilling into a child page extends the trail; the
  trail's parent button rewinds to the parent."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(defn- trail [page] (.locator page "nav[aria-label=Breadcrumb] button"))

(deftest trail-extends-and-rewinds
  (with-page [page "/breadcrumb"]
    ;; Decorated wrapper *and* the inner page each render their own h2;
    ;; scope to the outermost section to keep the wait unambiguous.
    (fx/wait-text page "section[id^=ix] > h2" "Breadcrumb decoration")

    (testing "boot trail is Examples › Tier 2"
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (trail page))
          (.hasCount 2))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.nth (trail page) 1))
          (.hasText "Tier 2")))

    (testing "drilling into a child page extends the trail"
      (.click (.locator page "button:has(strong:has-text(\"Tree\"))"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (trail page))
          (.hasCount 3))
      (is (= "Tree" (-> (trail page) .last .textContent))))

    (testing "clicking an earlier crumb rewinds the path"
      (.click (.nth (trail page) 0))   ; Examples
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (trail page))
          (.hasCount 1)))))

(ns dev.zeko.stube.e2e.protected-counter-test
  "Auth gate: the component's render branches on `(s/principal)`.
  Without `?user=`, the principal-fn returns nil and the page shows
  the signed-out branch; with `?user=ada` the counter is visible."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest unauth-shows-signed-out-branch
  (with-page [page "/protected-counter"]
    (fx/wait-text page "h2" "Protected counter")
    (is (str/includes? (.content page) "only available to a signed-in user"))
    (is (zero? (.count (.locator page "button:has-text(\"+\")")))
        "no counter buttons should render without a principal")))

(deftest signed-in-shows-counter
  (with-page [page "/protected-counter?user=ada"]
    (fx/wait-text page "h2" "Protected counter")
    (testing "principal-fn produced 'ada' from the query string"
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "section[id^=ix] strong"))
          (.hasText "ada")))
    (testing "+ button increments through the protected path"
      (.click (.locator page "button:has-text(\"+\")"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "section[id^=ix] div >> nth=1"))
          (.containsText "1")))))

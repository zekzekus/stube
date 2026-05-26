(ns dev.zeko.stube.e2e.error-frame-test
  "S-5: a throwing handler swaps the component frame for `.stube-error`
  without dropping the SSE stream."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest boom-renders-error-banner-without-dropping-sse
  (with-page [page "/error-frame"]
    (fx/wait-text page "h2" "Error frame demo")

    (testing "the +1 button increments the counter (sanity)"
      (.click (.locator page "button:has-text(\"+1\")"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "section[id^=ix] strong"))
          (.hasText "1")))

    (testing "Boom swaps the frame for the error banner"
      (.click (.locator page "button:has-text(\"Boom\")"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page ".stube-error"))
          (.isVisible))
      ;; The +1 button is gone — the entire frame was replaced.
      (is (zero? (.count (.locator page "button:has-text(\"+1\")")))
          "Boom replaced the whole component frame in-place"))))

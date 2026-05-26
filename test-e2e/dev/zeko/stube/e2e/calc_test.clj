(ns dev.zeko.stube.e2e.calc-test
  "Dense click-event routing: twenty buttons funnel through one
  `:handle` `case`.  Tests do `3 + 4 =` and verifies the display."
  (:require [clojure.test :refer [deftest use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(defn- press [page label]
  (.click (.locator page (str "button:text-is(\"" label "\")"))))

(defn- display [page]
  ;; The calc section's first direct <div> is the display (h2, div=display, div=grid).
  (-> page (.locator "section[id^=ix] > div") (.nth 0)))

(deftest three-plus-four-equals-seven
  (with-page [page "/calc"]
    (fx/wait-text page "h2" "Calculator")
    (press page "3")
    (press page "+")
    (press page "4")
    (press page "=")
    (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
          (display page))
        (.hasText "7"))))

(ns dev.zeko.stube.e2e.table-report-test
  "Sortable WATableReport port: clicking a header toggles the column's
  sort direction; row order changes and the heading gets the arrow."
  (:require [clojure.test :refer [deftest testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(defn- first-name-cell [page]
  (-> page (.locator "table tbody tr") .first (.locator "td") .first))

(deftest header-click-toggles-name-column-sort
  (with-page [page "/table-report"]
    (fx/wait-text page "h2" "Table report")
    (testing "default sort is by name ascending — Ada first"
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (first-name-cell page))
          (.hasText "Ada")))

    (testing "clicking the Name header flips the order — Margaret first"
      (.click (.locator page "th button:has-text(\"Name\")"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (first-name-cell page))
          (.hasText "Margaret")))))

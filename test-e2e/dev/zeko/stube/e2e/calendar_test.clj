(ns dev.zeko.stube.e2e.calendar-test
  "Mini calendar with per-cell structured payloads.  Clicking day 15 in
  the current month makes the flow answer a LocalDate; the next screen
  shows that date back to the user."
  (:require [clojure.test :refer [deftest testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest pick-a-day-advances-to-the-show-date-screen
  (with-page [page "/calendar"]
    (testing "calendar header is present"
      ;; The grid renders rows of day-of-week labels + clickable day cells.
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "section[id^=ix] header"))
          (.isVisible)))

    (testing "picking day 15 answers and flow advances"
      ;; Cells without :on click are decorative; only in-month cells are
      ;; clickable.  Day 15 is always in any month.
      (.click (.locator page "div[style*=cursor] >> text=\"15\""))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "h3"))
          (.hasText "You picked"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "p strong"))
          (.containsText "-15")))))

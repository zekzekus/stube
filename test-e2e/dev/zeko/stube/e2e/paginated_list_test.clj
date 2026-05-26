(ns dev.zeko.stube.e2e.paginated-list-test
  "WABatchedList port.  Next button bumps the page and patches just the
  list block; the previously-Previous button (disabled) becomes enabled."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(defn- prev-btn [page] (.locator page "button:has-text(\"Previous\")"))
(defn- next-btn [page] (.locator page "button:has-text(\"Next\")"))

(deftest next-and-prev-change-pages
  (with-page [page "/paginated-list"]
    (fx/wait-text page "h2" "Paginated list")

    (testing "page 0 has Previous disabled"
      (is (true?  (.isDisabled (prev-btn page))))
      (is (false? (.isDisabled (next-btn page)))))

    (testing "Next advances to page 1 — Previous becomes enabled"
      (.click (next-btn page))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (prev-btn page))
          (.isEnabled)))

    (testing "Previous returns to page 0 — Previous disabled again"
      (.click (prev-btn page))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (prev-btn page))
          (.isDisabled)))))

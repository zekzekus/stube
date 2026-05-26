(ns dev.zeko.stube.e2e.todo-test
  "Slot-local in-place edit via `:call-in-slot`: clicking a row's label
  swaps that row's display span for an editor; the sibling row keeps
  its own text and stays in display mode."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest clicking-row-label-mounts-editor-only-in-that-slot
  (with-page [page "/todo"]
    (fx/wait-text page "h2" "Todo")

    (testing "boot state has two pre-seeded items"
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "ul li"))
          (.hasCount 2)))

    (testing "click first row's label → editor lives inside that row only"
      (.click (.locator page "ul li:first-of-type span"))
      ;; The editor input is *inside* the first li (slot-local).
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "ul li:first-of-type input[name=text]"))
          (.isVisible))
      ;; The second row is still in display mode, with its original text.
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "ul li:nth-of-type(2) span"))
          (.hasText "tick the box when done"))
      ;; And no input leaked into the second row.
      (is (zero? (.count (.locator page "ul li:nth-of-type(2) input[name=text]")))
          "editor must be scoped to the slot of the edited row"))))

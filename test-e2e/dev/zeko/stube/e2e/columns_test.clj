(ns dev.zeko.stube.e2e.columns-test
  "S-7 `s/keyed-children`: adding or removing a column patches one
  fragment, never the whole list."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(defn- columns [page] (.locator page "article.stube-card"))

(deftest add-and-remove-a-keyed-column
  (with-page [page "/columns"]
    (fx/wait-text page "h1" "Columns")

    (testing "boot state has the three configured columns"
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (columns page))
          (.hasCount 3)))

    (testing "Add column → 4 columns, sentinel on the surviving last one persists"
      ;; Mark the current last column so we can prove the existing
      ;; siblings weren't re-rendered when the new fragment landed.
      (.evaluate page
                 "() => document.querySelectorAll('article.stube-card')[2]
                          .setAttribute('data-e2e-keep', 'y')")
      (.click (.locator page "button:has-text(\"Add column\")"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (columns page))
          (.hasCount 4))
      (is (= "y" (-> (columns page) (.nth 2) (.getAttribute "data-e2e-keep")))
          "the third column was not re-rendered when the fourth was appended"))

    (testing "Remove last → 3 columns"
      (.click (.locator page "button:has-text(\"Remove last\")"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (columns page))
          (.hasCount 3)))))

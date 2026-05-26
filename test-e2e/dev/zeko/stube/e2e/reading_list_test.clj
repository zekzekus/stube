(ns dev.zeko.stube.e2e.reading-list-test
  "S-12: URL as durable state.  `?items=a,b,c` restores keyed columns
  on first GET; clicking a card's Close button removes that id and
  the kernel's `:url` projection updates the address bar."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(defn- cards [page] (.locator page "article.stube-card"))

(deftest items-query-restores-columns
  (with-page [page "/reading-list?items=clojure,datastar,seaside"]
    (fx/wait-text page "h1" "Reading list")
    (testing ":init-args-fn parsed ?items=… and :start populated keyed-children"
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (cards page))
          (.hasCount 3)))

    (testing "closing the datastar column drops the card and rewrites the URL"
      (.click (.locator page
                        "article.stube-card:has-text(\"Datastar\") button:text-is(\"Close\")"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (cards page))
          (.hasCount 2))
      (is (str/ends-with? (.url page) "?items=clojure,seaside")
          "URL reflects the surviving ids in original order"))))

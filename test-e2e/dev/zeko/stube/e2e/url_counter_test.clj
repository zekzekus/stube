(ns dev.zeko.stube.e2e.url-counter-test
  "End-to-end smoke for `:url` — the declarative URL projection.

  Exercises two stube-specific properties:

    * `:init-args-fn` reads `?n=` on the initial GET and seeds `:n`.
    * `:url` projects updated state into the address bar after each
      dispatch, with no per-handler `(s/history …)` ceremony."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest init-args-seed-and-url-tracks-state
  (with-page [page "/url-counter?n=5"]
    (testing "init-args-fn seeded :n from the query string"
      (fx/wait-text page "h2" "URL-state counter")
      (is (str/includes? (.content page) ">5<")
          "display should render the seeded value before any click"))

    (testing "+ updates the DOM *and* the URL — no explicit history call"
      (-> page (.locator "button:has-text(\"+\")") .click)
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "section[id^='ix'] span:has-text(\"6\")"))
          (.isVisible))
      (is (str/ends-with? (.url page) "?n=6")
          "URL should reflect the new :n via the :url projection"))))

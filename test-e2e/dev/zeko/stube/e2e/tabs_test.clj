(ns dev.zeko.stube.e2e.tabs-test
  "Inactive embedded children keep their state.  Only the active tab is
  rendered, but the others sit in `:conv/instances` with their own
  state — tabbing back finds them right where they were."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(defn- tab [page label]
  (.locator page (str "nav button:text-is(\"" label "\")")))

(deftest inactive-tab-state-survives-tab-away-and-back
  (with-page [page "/tabs"]
    (fx/wait-text page "h2" "Tabbed navigation")
    (testing "default tab is Counter; no textarea is in the DOM yet"
      (is (zero? (.count (.locator page "textarea")))))

    (testing "switch to Notes, type something"
      (.click (tab page "Notes"))
      (.fill (.locator page "textarea") "hello stube"))

    (testing "switching away to Counter unmounts the textarea from view"
      (.click (tab page "Counter"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "textarea"))
          (.hasCount 0)))

    (testing "switching back to Notes finds the typed value preserved"
      (.click (tab page "Notes"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "textarea"))
          (.hasValue "hello stube")))))

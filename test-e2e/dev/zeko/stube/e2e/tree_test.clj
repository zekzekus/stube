(ns dev.zeko.stube.e2e.tree-test
  "Recursive renderer + per-node expansion set.  Expand-all reveals
  more `<li>` rows than the collapsed root."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest expand-all-reveals-children
  (with-page [page "/tree"]
    (fx/wait-text page "h2" "Tree")
    (let [collapsed (.count (.locator page "li"))]
      (.click (.locator page "button:has-text(\"Expand all\")"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "li"))
          (.not)
          (.hasCount collapsed))
      (is (> (.count (.locator page "li")) collapsed)
          "expand-all should reveal child rows beyond the initial root"))))

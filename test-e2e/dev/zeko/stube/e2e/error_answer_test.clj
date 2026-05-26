(ns dev.zeko.stube.e2e.error-answer-test
  "S-14: a child emits `(s/answer-error ex)`; the parent's
  `:on-error-<key>` resume gets the exception and can re-call the form
  with the user's in-flight draft intact."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest child-error-restores-form-with-draft
  (with-page [page "/error-answer"]
    (fx/wait-text page "h2" "answer-error demo")

    (testing "open the edit form"
      (.click (.locator page "button:has-text(\"Open edit form\")"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page "input[name=draft]"))
          (.isVisible)))

    (testing "first save throws the mock 409; banner appears, draft preserved"
      ;; Fresh JVM: !save-attempts is 0, first :save increments to 1
      ;; (odd) → conflict.  The parent's :on-error-saved resume re-mounts
      ;; the form with the draft we tucked into ex-data.
      (.fill (.locator page "input[name=draft]") "hello")
      (.click (.locator page "button[type=submit]"))
      (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
            (.locator page ".stube-error"))
          (.containsText "Save failed"))
      (is (= "hello" (.inputValue (.locator page "input[name=draft]")))
          ":on-error-saved resume must restore the draft into the re-mounted form"))))

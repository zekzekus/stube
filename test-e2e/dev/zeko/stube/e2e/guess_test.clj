(ns dev.zeko.stube.e2e.guess-test
  "Smoke for `defflow` + `s/await`: the flow asks for a number, branches
  on too-high/too-low/correct, and runs ordinary Clojure between awaits."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest flow-advances-on-each-guess
  (with-page [page "/guess"]
    (testing "first prompt is rendered by the flow's :start"
      (fx/wait-text page "form.stube-prompt p" "Pick a number from 1 to 100."))

    (testing "submitting a number routes through :handle and the flow recurs"
      (-> page (.locator "input[name=answer]") (.fill "1"))
      (-> page (.locator "button[type=submit]") .click)
      ;; The flow will tell us either "Too low" (most likely with 1) or
      ;; — for the 1-in-100 chance the target is 1 — "Got it!".  Either
      ;; way the prompt text has changed, which is the only assertion
      ;; the demo's stochastic shape lets us make deterministically.
      (let [p (-> page (.locator "form.stube-prompt p, div.stube-info p"))]
        (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat p)
            (.not)
            (.hasText "Pick a number from 1 to 100."))
        (is (some? (.textContent p)))))))

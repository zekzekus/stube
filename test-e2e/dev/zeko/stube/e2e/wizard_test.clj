(ns dev.zeko.stube.e2e.wizard-test
  "Multi-step form with a child→parent `::back` answer.  The wizard task
  preserves typed values across Back — re-calling a step pre-fills the
  input from its own state, so nothing the user typed is lost."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest back-preserves-typed-value
  (with-page [page "/wizard"]
    (testing "step 1 asks for the name"
      (fx/wait-text page "form label" "What's your name?"))

    (testing "submitting the name advances to step 2"
      (-> page (.locator "input[name=answer]") (.fill "Ada"))
      (-> page (.locator "button[type=submit]") .click)
      (fx/wait-text page "form label" "Favourite colour?"))

    (testing "Back from step 2 re-renders step 1 with the previous answer pre-filled"
      (-> page (.locator "button:has-text(\"Back\")") .click)
      (fx/wait-text page "form label" "What's your name?")
      (is (= "Ada" (.inputValue (.locator page "input[name=answer]")))
          "wizard remembered the name; Back is a child→parent answer, not a global rewind"))))

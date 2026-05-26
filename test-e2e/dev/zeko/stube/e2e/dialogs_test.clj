(ns dev.zeko.stube.e2e.dialogs-test
  "Stock modal helpers wired into a defflow: `s/confirm` → `s/prompt`
  → `s/choose` → `s/info`.  Drives the four-step happy path.

  Selectors track the stock ui shapes (see `dev.zeko.stube.ui`):
  confirm/choose/info put their text in `p.stube-card__body`; prompt
  uses `label.stube-label` plus an `input[name=value]`."
  (:require [clojure.test :refer [deftest testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(defn- click-text [page label]
  (.click (.locator page (str "button:text-is(\"" label "\")"))))

(deftest dialog-flow-end-to-end
  (with-page [page "/dialogs"]
    (testing "first dialog asks for confirmation"
      (fx/wait-text page "p.stube-card__body" "Ready to play?"))

    (testing "Yes advances to the prompt"
      (click-text page "Yes")
      (fx/wait-text page "label.stube-label" "What's your name?"))

    (testing "filling the prompt and OK advances to the chooser"
      (.fill (.locator page "input[name=value]") "Ada")
      (click-text page "OK")
      (fx/wait-text page "p.stube-card__body" "Pick a colour, Ada:"))

    (testing "picking a colour produces the final info banner"
      (click-text page "red")
      (fx/wait-text page "p.stube-card__body" "Hello Ada — your colour is red."))))

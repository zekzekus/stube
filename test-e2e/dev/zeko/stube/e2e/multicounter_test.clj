(ns dev.zeko.stube.e2e.multicounter-test
  "End-to-end smoke for the multicounter demo.

  The point of this example is morph-by-id: clicking `+` on counter 1
  should only re-render counter 1; counters 2 and 3 must keep their DOM
  identity.  We prove that by stamping a sentinel attribute onto
  counter 2's root *before* the click and asserting it survives —
  a full re-render of the sibling would lose it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(defn- counter [page n]
  (-> page (.locator ".stube-counter") (.nth n)))

(defn- counter-text [page n]
  (-> (counter page n) (.locator "span") .textContent))

(defn- plus-button [counter-locator]
  ;; Render order: [- span +].  The + is the last button in each counter.
  (-> counter-locator (.locator "button") .last))

(deftest plus-on-counter-1-does-not-rerender-siblings
  (with-page [page "/multicounter"]
    (testing "initial render shows three counters at 0, 5, 10"
      (fx/wait-text page ".stube-counter:nth-of-type(1) span" "0")
      (is (= "5"  (counter-text page 1)))
      (is (= "10" (counter-text page 2))))

    (testing "sentinel stamped on counter 2 survives a click on counter 1"
      (.evaluate page
                 (str "() => document.querySelectorAll('.stube-counter')[1]"
                      ".setAttribute('data-e2e-sentinel', 'kept')"))
      (.click (plus-button (counter page 0)))
      (fx/wait-text page ".stube-counter:nth-of-type(1) span" "1")
      (is (= "5"    (counter-text page 1)))
      (is (= "10"   (counter-text page 2)))
      (is (= "kept" (-> (counter page 1) (.getAttribute "data-e2e-sentinel")))
          "counter 2 was morphed in place — full re-render would drop sentinel"))))

(ns dev.zeko.stube.e2e.signal-mirror-test
  "Round-trip smoke for `ctx.setSignal` → `s/signal-mirror` → `data-text`.

  The kasten notes (against 0.3.3) call this exact test out as the
  missing piece that let two consecutive releases ship silent
  `ctx.setSignal` no-ops.  We mount a behavior whose `mount` calls
  `ctx.setSignal('x', 'hello')`, render `<p data-text=\"$x\">`, and
  assert the paragraph picks up the value within a frame.

  If this test fails, the bridge no longer reaches the signal store
  through Datastar's `data-bind` machinery — exactly the regression
  the data-bind seam was chosen to make visible."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [dev.zeko.stube.e2e.fixture :as fx :refer [with-page]]))

(use-fixtures :once fx/ensure-up!)

(deftest behavior-set-signal-reaches-bound-data-text
  (with-page [page "/signal-mirror"]
    (fx/wait-text page "h1" "Signal mirror round-trip")
    (fx/wait-text page "#echo" "hello")
    (is (= "hello" (fx/text page "#echo"))
        "ctx.setSignal must propagate through s/signal-mirror's data-bind input into the bound $x signal")))

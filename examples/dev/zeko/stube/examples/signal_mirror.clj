(ns dev.zeko.stube.examples.signal-mirror
  "Round-trip smoke for `ctx.setSignal` through `s/signal-mirror`.

  Tiny by design: one component, one behavior, no domain state.  The
  behavior's `mount` calls `ctx.setSignal('x', 'hello')`; the rendered
  `<p data-text=\"$x\">` should reflect the value on the next frame.

  Pinned by `test-e2e/.../signal_mirror_test.clj` — exactly the
  round-trip check the kasten notes asked for after two consecutive
  releases shipped silent no-op `ctx.setSignal` implementations."
  (:require [dev.zeko.stube.core :as s]))

(s/defcomponent :demo/signal-mirror
  :init (fn [_] {})

  :render
  (fn [self]
    [:section (s/root-attrs self)
     [:h1 "Signal mirror round-trip"]

     ;; Hidden `<input data-bind:x>` carrying the marker the behaviors
     ;; bridge looks up.  Rendering `(s/signal-mirror :x)` is the
     ;; canonical way to give a behavior a write seam for `:x` without
     ;; coupling to any Datastar internals.
     [:input (s/signal-mirror :x)]

     ;; The behavior runs once at mount and calls
     ;; `ctx.setSignal('x', 'hello')`.  The paragraph below should
     ;; reflect the value on the next frame.
     [:div (s/behavior self :demo/signal-mirror-write {})]

     [:p {:id "echo" :data-text "$x"} ""]]))

(s/mount! "/signal-mirror" :demo/signal-mirror)

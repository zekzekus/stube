(ns dev.zeko.stube.examples.sketch
  "Sketch pad — the canonical demo of the client-side seam.

  A single component that exercises every piece of the JS/CSS posture:

  * **`s/behavior`** — `:demo/sketch-canvas` owns mouse/touch drawing on
    a `<canvas>`.  Its module lives at
    `examples/stube_behaviors/demo/sketch-canvas.js` and is lazy-imported
    by `behaviors.js` on first sighting.
  * **`s/preserve`** — the canvas host is opted out of morph so server
    re-renders never wipe the pixels the user has drawn.
  * **Per-component CSS** at `examples/stube_styles/demo/sketch.css` is
    auto-linked because the component id is `:demo/sketch`.  Selectors
    target `[data-stube-component=\"demo/sketch\"]`, which stube emits
    on every root automatically.
  * **Inline `:styles`** colocate the tiny status badge styling on the
    component itself; the `&` selector is rewritten at head-emit time.
  * **`:modules`** loads `examples/stube_modules/demo/sketch-utils.js`
    once as a module asset, which exposes `globalThis.demoSketchUtils`
    for the behavior to reach into.

  Open <http://localhost:8080/sketch> and draw with the mouse.  Change
  colors and stroke width — note that the canvas pixels survive every
  server re-render because the behavior owns them and `s/preserve` keeps
  Datastar from touching the subtree.  Press `c` anywhere on the page
  to clear (registered as a keyboard shortcut by the module)."
  (:require [dev.zeko.stube.core :as s]))

(def ^:private palette
  [{:id :ink     :hex "#0f172a" :label "Ink"}
   {:id :crimson :hex "#dc2626" :label "Crimson"}
   {:id :ocean   :hex "#2563eb" :label "Ocean"}
   {:id :forest  :hex "#16a34a" :label "Forest"}
   {:id :amber   :hex "#d97706" :label "Amber"}])

(defn- palette-entry [colour-id]
  (or (some #(when (= (:id %) colour-id) %) palette)
      (first palette)))

(s/defcomponent :demo/sketch
  :doc "Server-driven sketch pad with a behavior-owned canvas.  Exercises
the full client-side seam: behaviors, preserve, per-component CSS,
inline :styles, and :modules."

  :init (fn [_]
          {:colour    :ink
           :stroke    4
           :clear-seq 0
           :strokes   0})

  ;; Two declarations that travel with the component definition.
  ;;
  ;; `:modules` makes `head-tags` emit one module asset reference
  ;; pointing at the sketch-utils helper.  The module sets up a global
  ;; namespace + keyboard shortcut once per page, regardless of how
  ;; many sketch instances are mounted.
  ;;
  ;; `:styles` is inline CSS — small, colocated, scoped at head-emit
  ;; time by replacing `&` with the component's
  ;; `[data-stube-component="demo/sketch"]` selector.
  :modules ["demo/sketch-utils"]
  :styles  (str "& .sketch__stats {"
                "  display: inline-flex; gap: .5rem; align-items: center;"
                "  padding: .25rem .75rem; border-radius: 999px;"
                "  background: #eef2ff; color: #3730a3;"
                "  font-size: .85rem; font-variant-numeric: tabular-nums;"
                "}")

  :keep #{:stroke}

  :render
  (fn [self]
    (let [{:keys [colour stroke clear-seq strokes]} self
          active (palette-entry colour)]
      [:section (s/root-attrs self)
       [:div {:class "sketch__header"}
        [:h1 "Sketch pad"]
        [:p "Drawing is owned by a "
         [:code "s/behavior"]
         " — the server controls the palette, the browser owns the pixels."]]

       [:div {:class "sketch__controls"}
        [:div {:class "sketch__palette" :role "radiogroup" :aria-label "Pen colour"}
         (for [{:keys [id hex label]} palette]
           [:button
            (merge {:type "button"
                    :role "radio"
                    :aria-checked (str (= id colour))
                    :title label
                    :class (cond-> "sketch__swatch"
                             (= id colour) (str " sketch__swatch--active"))
                    :style (str "background:" hex)}
                   (s/on self :click :as [:pick-colour id]))])]

        [:label {:class "sketch__stroke"}
         [:span "Stroke"]
         [:input (merge {:type  "range" :min 1 :max 24
                         :name  "stroke" :value stroke}
                        (s/bind :stroke)
                        (s/on self :input :as :resize {:debounce "100ms"}))]
         [:output (str stroke "px")]]

        [:div {:class "sketch__actions"}
         [:button (merge {:type "button"
                          :class "sketch__btn"
                          :data-sketch-clear "true"}
                         (s/on self :click :as :clear))
          "Clear"]
         [:button (merge {:type "button"
                          :class "sketch__btn sketch__btn--snapshot"}
                         (s/on self :click :as :snapshot))
          "Snapshot to console"]]]

       ;; The canvas host:
       ;;   - `s/preserve` keeps the live pixels across morphs;
       ;;   - `s/behavior` carries the current palette/stroke/clear-seq
       ;;     down as DOM attributes so `patched(el, ctx)` can read them.
       ;; The canvas host is opted out of morph (`s/preserve`) so the
       ;; pixels survive every server re-render.  `s/behavior` attaches
       ;; the JS module that owns drawing.  We hand the behavior the
       ;; URL for the `:stroke-end` event — the behavior `fetch`es it
       ;; on every pointerup, which proves out the behavior→server
       ;; round trip without any framework-specific JS plumbing.
       [:div (merge {:class "sketch__canvas-host"}
                    (s/preserve self :pad)
                    (s/behavior self :demo/sketch-canvas
                                {:colour     (:hex active)
                                 :stroke     stroke
                                 :clear-seq  clear-seq
                                 :stroke-url (s/event-url self :stroke-end)}))
        [:canvas {:width 720 :height 360}]]

       [:p {:class "sketch__hint"}
        "Press " [:kbd "c"] " anywhere on the page to clear — wired by "
        [:code ":modules [\"demo/sketch-utils\"]"] "."]

       ;; The inline `:styles` block above only declares the
       ;; `sketch__stats` chip; the rest of the styling lives in
       ;; `examples/stube_styles/demo/sketch.css`.  The stroke count is
       ;; entirely server-rendered: the behavior calls `fetch` on
       ;; `:stroke-url` after every release, the handler increments
       ;; `:strokes`, Datastar morphs this chip with the new value.
       [:div {:class "sketch__stats" :aria-live "polite"}
        [:span "Strokes:"] [:strong strokes]
        [:span "·"]
        [:span "Active colour:"] [:strong (:label active)]]]))

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :pick-colour (assoc self :colour payload)
      :resize      (update self :stroke #(min 24 (max 1 (or % 4))))
      :clear       (-> self (update :clear-seq inc) (assoc :strokes 0))
      ;; The behavior `fetch`es this event URL after every pointerup;
      ;; the handler bumps the server-side counter and Datastar morphs
      ;; the stats chip with the new number.  This is the canonical
      ;; "client widget tells the server something happened" pattern.
      :stroke-end  (update self :strokes inc)
      ;; Snapshot runs in the browser via `s/execute-script` — the
      ;; behavior owns the canvas, so we ask the browser to grab the
      ;; dataURL and log a preview.  Demonstrates the
      ;; `s/execute-script` escape hatch for one-off client calls
      ;; that don't fit the behavior contract.
      :snapshot    [self
                    [(s/execute-script
                       (str "(() => {"
                            " const c = document.querySelector("
                            "   '[data-stube-component=\"demo/sketch\"] canvas');"
                            " if (!c) { console.warn('sketch: canvas not found'); return; }"
                            " const url = c.toDataURL('image/png');"
                            " console.log('sketch snapshot dataURL ('"
                            "   + url.length + ' bytes):', url.slice(0, 64) + '…');"
                            "})()"))]]
      self)))

(s/mount! "/sketch" :demo/sketch)

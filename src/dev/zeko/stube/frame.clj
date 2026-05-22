(ns dev.zeko.stube.frame
  "Rendering an instance into a fragment.

  Three small jobs:

  * Invoke the user's `:render` against the merged instance map (with
    `render/*conv*` bound so `s/render-slot` can resolve children).
  * Decorate the outer hiccup with halo data-attrs when halos are
    active, and cache the resulting HTML on the instance for the dev
    panel's HTML tab.
  * Decide between `selector=#root, mode=inner` (first render of a
    frame) and Datastar's default morph-by-id (subsequent renders),
    so input focus and scroll state survive across re-renders."
  (:require [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.fragments    :as f]
            [dev.zeko.stube.halos        :as halos]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render]))

(defn default-render
  "Default renderer for components that don't supply one (e.g. tasks).
  An empty hidden div with the instance id keeps the morph-by-id wire
  contract intact."
  [self]
  [:div {:id (:instance/id self) :hidden true}])

(defn render-instance
  "Render `iid` with explicit Datastar patch `opts`, mark it and its
  rendered descendants as present in the DOM, and return `[conv' frag]`.

  When `:conv/halos?` is set, the outer hiccup is decorated with
  `data-stube-*` attrs and the resulting HTML is cached on the instance
  under `:instance/last-html` so the dev panel's HTML tab can show it."
  [conv iid opts]
  (let [inst      (conv/instance conv iid)
        cdef      (registry/lookup! (:instance/type inst))
        render-fn (or (:component/render cdef) default-render)
        halos?    (boolean (:conv/halos? conv))
        html      (binding [render/*conv* conv]
                    ;; Keep the dynamic conversation bound through HTML
                    ;; serialization too: user render fns may return lazy
                    ;; seqs whose elements call `s/render-slot` only when
                    ;; Chassis walks the tree.
                    (let [hiccup (cond-> (render-fn inst)
                                   halos? (halos/decorate-root inst))]
                      (render/html hiccup)))
        marked    (conv/mark-rendered conv iid)
        conv'     (cond-> marked
                    halos? (assoc-in [:conv/instances iid :instance/last-html]
                                     html))]
    [conv' (f/elements html opts)]))

(defn render-frame
  "Produce the elements fragment for `iid` and return `[conv' fragment]`.

  Patching strategy:

  * **First render of a frame** — its id is not yet in the DOM, so we
    target the shell's `<div id=\"root\">` with `mode inner`.
  * **Subsequent renders** — the id is in the DOM, so we let Datastar's
    default morph-by-id do the work.  Sibling DOM state — input focus,
    selection, scroll position — is preserved."
  [conv iid]
  (let [inst        (conv/instance conv iid)
        first-time? (not (:instance/rendered? inst))
        opts        (if first-time?
                      {:selector render/*root-selector* :patch-mode :inner}
                      ;; No selector → Datastar morphs by element id.
                      {})]
    (render-instance conv iid opts)))

(defn render-slot-overlay
  "Render the child currently occupying `slot` after a `:call-in-slot`.
  If the slot already had a DOM root, patch that root `outer`; otherwise
  fall back to re-rendering the parent because there is no child anchor
  yet."
  [conv parent-id old-iid new-iid]
  (if old-iid
    (render-instance conv new-iid {:selector (str "#" old-iid)
                                   :patch-mode :outer})
    (render-frame conv parent-id)))

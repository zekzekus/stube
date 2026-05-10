(ns dev.zeko.stube.examples.tabs
  "Tabbed navigation — Seaside's `WASimpleNavigation` rebuilt on stube
  embedding.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/tabs>.

  ──────────────────────────────────────────────────────────────────────
  What this exercises
  ──────────────────────────────────────────────────────────────────────

  Two things multicounter doesn't:

  1. **Inactive children outlive the rendered view.**  The parent only
     `s/render-slot`s the active tab, but the *other* two children
     are still in `:conv/instances`.  Tab away from the counter,
     come back, and the count is preserved.  This is the property
     that makes tab navigation feel desktop-like.

  2. **A handler that touches just its own state changes which child
     renders.**  No `:call`, no `:replace` — `:active` is a plain key
     on the parent's state map and `:render` branches on it.  The
     kernel's auto-re-render pushes the swapped HTML over morph-by-id.

  ──────────────────────────────────────────────────────────────────────
  Component tree
  ──────────────────────────────────────────────────────────────────────

      :demo/tabs                          ← parent, owns three tab children
        ├── :slot/counter → :demo/counter
        ├── :slot/notes   → :demo/notes
        └── :slot/about   → :demo/about-text

  The parent declares the children eagerly in `:children`; the kernel
  instantiates all three at boot and the parent picks one to render."
  (:require [dev.zeko.stube.core :as s]
            ;; Reuse the multicounter's `:demo/counter` so we have a
            ;; non-trivial widget on tab 1 without re-defining it.
            [dev.zeko.stube.examples.multicounter]))

;; ---------------------------------------------------------------------------
;; Tab 2: a tiny notes widget — typed text that survives a tab away
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/notes
  :init   (fn [_] {:text ""})
  :keep   #{:text}

  :render (fn [self]
            [:section {:id    (:instance/id self)
                       :style "padding:1rem;"}
             [:h3 {:style "margin-top:0;"} "Notes"]
             [:textarea (merge {:rows 6 :cols 40
                                :style "width:100%; font-family:inherit;"
                                :placeholder "type something, then click another tab and back"
                                :value (:text self)}
                               (s/on self :input :as :sync)
                               (s/bind :text))]
             [:p {:style "color:#666; font-size:0.9rem;"}
              "Length: " [:strong (count (:text self))] " chars"]])

  :handle (fn [self _] [self []]))

;; ---------------------------------------------------------------------------
;; Tab 3: a static about panel
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/about-text
  :render (fn [self]
            [:section {:id    (:instance/id self)
                       :style "padding:1rem; max-width:36rem;"}
             [:h3 {:style "margin-top:0;"} "About"]
             [:p "This page demonstrates stube's tabbed-navigation pattern. "
                 "All three tabs are instantiated at boot; only the active "
                 "one is rendered, but the inactive ones keep their state."]
             [:p "Try typing on the Notes tab, switching to Counter, then "
                 "switching back.  Notice the text is still there."]]))

;; ---------------------------------------------------------------------------
;; The parent — owns the children, owns which one is active
;; ---------------------------------------------------------------------------

(def ^:private tabs
  ;; Order matters for the header layout; the keyword is both the
  ;; `:active` value and the slot key.
  [[:counter "Counter"]
   [:notes   "Notes"]
   [:about   "About"]])

(defn- header-button [self [tab-kw label]]
  (let [active? (= tab-kw (:active self))]
    [:button (merge {:type  "button"
                     :style (str "padding:0.5rem 1rem; border:1px solid #888; "
                                 "border-bottom:none; cursor:pointer; "
                                 "border-top-left-radius:0.4rem; "
                                 "border-top-right-radius:0.4rem; "
                                 (if active?
                                   "background:#fff; font-weight:bold;"
                                   "background:#ddd;"))}
                    (s/on self :click :as [:tab tab-kw]))
     label]))

(s/defcomponent :demo/tabs
  :children {:slot/counter (s/embed :demo/counter {:start 0})
             :slot/notes   (s/embed :demo/notes)
             :slot/about   (s/embed :demo/about-text)}

  :init   (constantly {:active :counter})

  :render (fn [self]
            [:section {:id    (:instance/id self)
                       :style "font-family:system-ui, sans-serif;
                               padding:1rem; max-width:42rem;"}
             [:h2 "Tabbed navigation"]
             [:nav {:style "display:flex; gap:0.25rem;"}
              (for [t tabs] (header-button self t))]
             [:div {:style "border:1px solid #888; padding:0.5rem;
                            border-radius:0 0.4rem 0.4rem 0.4rem;"}
              ;; Render only the active slot.  The other two children
              ;; are still around in `:conv/instances` — they just
              ;; aren't in the DOM right now.
              (s/render-slot self (keyword "slot" (name (:active self))))]])

  :handle (fn [self {:keys [event payload]}]
            (if (= event :tab)
              [(assoc self :active payload) []]
              [self []])))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/tabs" :demo/tabs)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

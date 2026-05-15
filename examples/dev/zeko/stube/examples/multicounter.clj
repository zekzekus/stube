(ns dev.zeko.stube.examples.multicounter
  "Slice-2 demo: Seaside's `WAMultiCounter` rebuilt with stube embedding.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/multicounter>.

  Each `+` / `−` click only re-renders its own counter (Datastar morphs
  by id), so any DOM state in sibling counters — focus, scroll, even
  in-flight `<input>` selections — is preserved.  The original Seaside
  page (`/seaside/examples/multicounter`) demonstrated exactly this
  behaviour against a server-side continuation; the stube version uses
  the conversation-as-value model and the slice-2 morph-by-id strategy.

  Component tree:

      :demo/multicounter           ← the embedding parent (no UI of its own)
        ├── :slot/c1 → :demo/counter
        ├── :slot/c2 → :demo/counter
        └── :slot/c3 → :demo/counter

  The parent declares its children once at `:component/init` time; the
  kernel eagerly instantiates the three counters and links them under
  `:instance/children`.  The parent's render inlines each via
  `s/render-slot`."
  (:require [dev.zeko.stube.core :as s]))

;; ---------------------------------------------------------------------------
;; The reusable counter widget
;; ---------------------------------------------------------------------------
;;
;; Self-contained: holds its own count, renders itself, knows how to
;; increment and decrement.  Three of these will be embedded into the
;; multicounter; each is fully independent because each gets its own
;; instance id and its own state map in the conversation.

(s/defcomponent :demo/counter
  :init   (fn [{:keys [start]}]
            {:n (or start 0)})

  :render (fn [self]
            ;; The element id MUST be the instance id — that is what
            ;; Datastar morphs against on subsequent renders.
            [:div (s/root-attrs self
                    {:class "stube-counter"
                     :style "display:inline-flex; gap:0.5rem; align-items:center;
                             padding:0.25rem 0.5rem; border:1px solid #ccc;
                             border-radius:0.25rem; margin:0.25rem;"})
             ;; Buttons fire a real `click` DOM event; we route it on
             ;; the server to a meaningful name (`:inc` / `:dec`) so the
             ;; `:handle` `case` can branch on intent rather than on
             ;; "which DOM event happened".
             [:button (merge {:type "button"} (s/on self :click :as :dec)) "−"]
             [:span {:style "min-width:2ch; text-align:center;"}
              (str (:n self))]
             [:button (merge {:type "button"} (s/on self :click :as :inc)) "+"]])

  :handle (fn [self {:keys [event]}]
            (case event
              :inc (update self :n inc)
              :dec (update self :n dec)
              nil)))

;; ---------------------------------------------------------------------------
;; The embedding parent
;; ---------------------------------------------------------------------------
;;
;; This component owns three counters by declaring them in `:children`.
;; It has no behaviour of its own — its only job is to lay out the
;; children.  Notice there is no `:handle`: nothing is dispatched to
;; the parent itself, and that is fine.

(s/defcomponent :demo/multicounter
  :children {:slot/c1 (s/embed :demo/counter {:start 0})
             :slot/c2 (s/embed :demo/counter {:start 5})
             :slot/c3 (s/embed :demo/counter {:start 10})}

  :render (fn [self]
            [:section
             (s/root-attrs self
               {:class "stube-multicounter"
                :style "font-family:system-ui, sans-serif; padding:1rem;"})
             [:h1 "Multicounter"]
             [:p {:style "color:#555;"}
              "Three independent counters, embedded as children. "
              "Each click only re-renders its own counter."]
             [:div {:style "display:flex; flex-wrap:wrap;"}
              (s/render-slot self :slot/c1)
              (s/render-slot self :slot/c2)
              (s/render-slot self :slot/c3)]
             [:p {:style "margin-top:1rem; color:#777; font-size:0.9rem;"}
              "Open the network panel and watch the SSE stream: only the "
              "clicked counter's HTML is sent on each event."]]))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/multicounter" :demo/multicounter)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

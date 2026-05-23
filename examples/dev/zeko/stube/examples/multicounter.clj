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

  Composition primitive: S-7 `s/keyed-children`.  The parent declares
  three columns at `:start` via `s/set-keyed-children`; the kernel
  mints three independent counter instances and inlines them under one
  container.  Switching to `keyed-children` lets the columns-demo
  (`columns.clj`) share the same code path and pay only per-child
  patch cost when columns are added or removed."
  (:require [dev.zeko.stube.core :as s]))

;; ---------------------------------------------------------------------------
;; The reusable counter widget
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/counter
  :init   (fn [{:keys [start]}]
            {:n (or start 0)})

  :render (fn [self]
            [:div (s/root-attrs self
                    {:class "stube-counter"
                     :style "display:inline-flex; gap:0.5rem; align-items:center;
                             padding:0.25rem 0.5rem; border:1px solid #ccc;
                             border-radius:0.25rem; margin:0.25rem;"})
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

(def ^:private initial-columns
  [[:c1 (s/embed :demo/counter {:start 0})]
   [:c2 (s/embed :demo/counter {:start 5})]
   [:c3 (s/embed :demo/counter {:start 10})]])

(s/defcomponent :demo/multicounter
  :init  (constantly {})

  ;; `:start` reconciles the keyed slot before the parent renders the
  ;; first time, so the container goes out in one shot with all three
  ;; counters inlined.  After that, the parent never re-renders — each
  ;; `+`/`-` click morphs its own counter by id.
  :start (fn [self]
           [self [(s/set-keyed-children :slot/cols initial-columns)]])

  :render (fn [self]
            [:section
             (s/root-attrs self
               {:class "stube-multicounter"
                :style "font-family:system-ui, sans-serif; padding:1rem;"})
             [:h1 "Multicounter"]
             [:p {:style "color:#555;"}
              "Three independent counters, embedded as keyed children. "
              "Each click only re-renders its own counter."]
             (s/keyed-children self :slot/cols)
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

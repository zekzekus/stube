(ns dev.zeko.stube.examples.url-state-counter
  "Declarative URL-state counter using the `:url` component key.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/url-counter> or
  <http://localhost:8080/url-counter?n=42> to start at 42.

  ──────────────────────────────────────────────────────────────────────
  What this exercises
  ──────────────────────────────────────────────────────────────────────

  * `:url` — a pure projection of `self` that the kernel diffs after
    every dispatch.  When the URL changes, a `:history` effect fires
    automatically; no per-handler ceremony.  An explicit
    `(s/history …)` always wins, so handlers can override the default.
  * `:init-args-fn` on `s/mount!` — the GET handler reads `?n=` and
    passes it to `mint-conversation!` as init-args, so visiting
    `/url-counter?n=42` starts the counter at 42.

  ──────────────────────────────────────────────────────────────────────
  Pattern: URL as display-only projection
  ──────────────────────────────────────────────────────────────────────

  The URL is *not* authoritative state.  The counter's `:n` lives on
  the instance map; the URL is a projection that updates whenever `:n`
  changes.  On first load the host GET handler reads `?n=` once and
  seeds the conversation.  This keeps the kernel pure: no URL parsing
  in handlers, no bi-directional binding.

  The hand-rolled form (emit `(s/history :push …)` from every handler)
  still works — `examples/dev/zeko/stube/examples/url_state_counter_manual.clj`
  preserves it for comparison."
  (:require [dev.zeko.stube.core :as s]))

(defn- counter-url [n]
  (str "/url-counter?n=" n))

;; ---------------------------------------------------------------------------
;; The component
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/url-counter
  :doc "A counter that declares its URL as a projection of state."

  :init
  (fn [{:keys [n]}]
    ;; `n` comes from the GET request's ?n= query param (see mount! below).
    ;; Defaults to 0 if absent or unparseable.
    {:n (or (some-> n str parse-long) 0)})

  ;; `:url` is computed after every dispatch.  When it differs from the
  ;; last value, the kernel emits a `:history` effect for us.  `:push`
  ;; (vs. the default `:replace`) records a real browser history entry
  ;; per click so Back / Forward walk the counter history.
  :url
  (fn [self] [:push (counter-url (:n self))])

  :render
  (fn [self]
    [:section (s/root-attrs self {:style "max-width:20rem; padding:1.5rem;
                                          font-family:system-ui, sans-serif;
                                          border:1px solid #ccc; border-radius:0.5rem;
                                          background:#fff; margin:1rem;"})
     [:h2 {:style "margin-top:0;"} "URL-state counter"]
     [:p {:style "color:#555; font-size:0.9rem;"}
      "Visit "
      [:code (counter-url 42)]
      " to start at 42. Click ± to update the URL."]
     [:div {:style "display:flex; align-items:center; gap:1rem; margin-top:1rem;"}
      [:button (merge {:type  "button"
                       :style "font-size:1.4rem; width:2.5rem; height:2.5rem;
                               border:1px solid #bbb; border-radius:0.25rem;
                               background:#f4f4f4; cursor:pointer;"}
                      (s/on self :click :as :dec))
       "−"]
      [:span {:style "font-size:2rem; min-width:3rem; text-align:center;"}
       (:n self)]
      [:button (merge {:type  "button"
                       :style "font-size:1.4rem; width:2.5rem; height:2.5rem;
                               border:1px solid #bbb; border-radius:0.25rem;
                               background:#f4f4f4; cursor:pointer;"}
                      (s/on self :click :as :inc))
       "+"]]
     [:p {:style "color:#888; font-size:0.8rem; margin-top:1rem;"}
      "No explicit "
      [:code "(s/history …)"]
      " calls in the handler — "
      [:code ":url"]
      " projects state into the address bar."]])

  :handle
  (fn [self {:keys [event]}]
    (case event
      :inc (update self :n inc)
      :dec (update self :n dec)
      self)))

;; ---------------------------------------------------------------------------
;; Wiring
;;
;; The `:init-args-fn` reads `?n=` from the GET request and passes it as
;; init-args to `mint-conversation!`.  The component's `:init` then seeds
;; `:n` from that value.  Visiting `/url-counter?n=42` starts at 42.
;; ---------------------------------------------------------------------------

(s/mount! "/url-counter" :demo/url-counter
          {:init-args-fn (fn [req]
                           (let [raw (s/query-value req "n")]
                             (cond-> {}
                               raw (assoc :n raw))))})

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

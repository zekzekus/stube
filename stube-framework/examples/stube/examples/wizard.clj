(ns stube.examples.wizard
  "Slice-3 demo: a tiny three-step wizard with a working Back button.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/wizard>.

  ──────────────────────────────────────────────────────────────────────
  What this shows — and why we don't use the global `s/back`
  ──────────────────────────────────────────────────────────────────────

  `s/back` (the `[:back]` effect) rewinds the **whole** conversation
  one snapshot — stack, embedded children, AND every parent task's
  accumulated state.  That is the right semantics for a global \"undo\"
  but the wrong semantics for a multi-step form: the user expects the
  wizard to remember what they typed earlier, even after Back.

  The Seaside-style pattern is to make Back a *child→parent answer*,
  not a global rewind.  Each step has two ways to terminate:

      [:answer typed-value]   ← Next button
      [:answer ::back]        ← Back button

  The wizard task pattern-matches on the answer and either advances
  to the next step or `:call`s the previous step again, this time
  pre-filling the input with whatever the user typed there before.
  Wizard state is never thrown away because the wizard task itself
  is never rewound.

  Component tree
  ──────────────

      :demo/wizard                    ← task component, drives the flow
        ├── (call) :demo/wizard-step  ← \"What's your name?\"
        ├── (call) :demo/wizard-step  ← \"Favourite colour?\"
        └── (call) :demo/wizard-summary ← shows answers + Back button"
  (:require [stube.core :as s]))

;; ---------------------------------------------------------------------------
;; A reusable text-prompt step
;; ---------------------------------------------------------------------------
;;
;; Renders a `<form>` with a label, an input, a Submit button and a
;; Back button.  Submit answers the parent with the typed value; Back
;; answers the parent with the sentinel `::back`.  The parent decides
;; what either of those means in its own flow.

;; Datastar signals are page-global, so the input uses `s/local-bind`:
;; every instance gets its own wire key while the handler still reads the
;; logical `:answer` value from `self`.

(s/defcomponent :demo/wizard-step
  :init   (fn [{:keys [label placeholder initial]}]
            {:label       label
             :placeholder (or placeholder "")
             ;; `:initial` is the value the wizard task wants pre-filled
             ;; into this step.  This is what makes back-then-forward
             ;; round-trip cleanly: the wizard remembers the previous
             ;; answer and hands it back as `:initial` when re-calling
             ;; this step.
             :initial     (or initial "")})

  :keep   #{:answer}

  :render (fn [self]
            [:form (merge {:id (:instance/id self)
                           :style "max-width:24rem; padding:1rem;
                                   font-family:system-ui, sans-serif;"}
                          (s/on self :submit))
             [:label {:style "display:block; margin-bottom:0.5rem;"}
              (:label self)]
             ;; `value` seeds the DOM; Datastar's `data-bind` then
             ;; initialises the signal FROM the input's value, so the
             ;; typed-then-restored flow round-trips cleanly.
             [:input (merge {:name        "answer"
                             :value       (:initial self)
                             :placeholder (:placeholder self)
                             :style       "width:100%; padding:0.4rem;
                                           font-size:1rem;"
                             :autofocus   true}
                            (s/local-bind self :answer))]
             [:div {:style "display:flex; gap:0.5rem; margin-top:0.75rem;"}
              [:button {:type "submit"
                        :style "padding:0.4rem 0.8rem;"} "Next"]
              ;; `type=button` so this does NOT submit the form.  We
              ;; route the click as `:back-click` and answer the
              ;; parent with `::back` — the parent's resume function
              ;; decides what \"back\" means in *its* flow.
              [:button (merge {:type "button"
                               :style "padding:0.4rem 0.8rem;"}
                              (s/on self :click :as :back-click))
               "Back"]]])

  :handle (fn [self {:keys [event]}]
            (case event
              :submit     [self [[:answer (get self :answer (:initial self))]]]
              :back-click [self [[:answer ::back]]]
              [self []])))

;; ---------------------------------------------------------------------------
;; The summary screen
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/wizard-summary
  :init   (fn [{:keys [name colour]}]
            {:name name :colour colour})

  :render (fn [self]
            [:section {:id (:instance/id self)
                       :style "max-width:24rem; padding:1rem;
                               font-family:system-ui, sans-serif;"}
             [:h2 "All set, " [:span (:name self)] "!"]
             [:p "Your favourite colour is "
              [:strong {:style (str "color:" (:colour self) ";")}
               (:colour self)] "."]
             [:div {:style "display:flex; gap:0.5rem; margin-top:1rem;"}
              [:button (merge {:type "button"}
                              (s/on self :click :as :restart))
               "Start over"]
              [:button (merge {:type "button"}
                              (s/on self :click :as :back-click))
               "Back"]]])

  :handle (fn [self {:keys [event]}]
            (case event
              ;; A non-`::back` answer ends the wizard cleanly via the
              ;; wizard task's `:on-done` resume key.
              :restart    [self [[:answer ::restart]]]
              :back-click [self [[:answer ::back]]]
              [self []])))

;; ---------------------------------------------------------------------------
;; The wizard task
;; ---------------------------------------------------------------------------
;;
;; Pure orchestration: no UI of its own, just a chain of `:call`s
;; assembled with the slice-0 resume-key style.  We deliberately *don't*
;; use `defflow` here so the example stays focused on slice 3 — Back
;; navigation through a multi-step form — without dragging cloroutine
;; into the picture.
;;
;; Each resume function pattern-matches on the answer:
;;
;;   * `::back` → re-`:call` the previous step (or end, on step 1).
;;   * any other value → store it on `self` and advance to the next step.
;;
;; The wizard task's own state (`:name`, `:colour`) is therefore
;; preserved across Back; nothing the user typed is lost.

(s/defcomponent :demo/wizard
  :init   (constantly {})

  ;; Helpers — pulled out as private fns to keep the resume-key map
  ;; readable.  They each return the effect vector that `:call`s the
  ;; named step pre-filled with whatever the wizard remembers.

  ;; `:start` runs once when this task is instantiated; we descend
  ;; into the first prompt straight away.
  :start  (fn [self]
            [self [[:call (s/embed :demo/wizard-step
                                   {:label       "What's your name?"
                                    :placeholder "Ada"
                                    :initial     (:name self)})
                    :resume :on-name]]])

  :on-name   (fn [self ans]
               (case ans
                 ;; Back from step 1 — there is nowhere to go, so end.
                 ::back  [self [[:end {:wizard :cancelled}]]]

                 (let [self' (assoc self :name ans)]
                   [self'
                    [[:call (s/embed :demo/wizard-step
                                     {:label       "Favourite colour?"
                                      :placeholder "rebeccapurple"
                                      :initial     (:colour self')})
                      :resume :on-colour]]])))

  :on-colour (fn [self ans]
               (case ans
                 ::back  [self
                          [[:call (s/embed :demo/wizard-step
                                           {:label       "What's your name?"
                                            :placeholder "Ada"
                                            :initial     (:name self)})
                            :resume :on-name]]]

                 (let [self' (assoc self :colour ans)]
                   [self'
                    [[:call (s/embed :demo/wizard-summary
                                     {:name   (:name self')
                                      :colour (:colour self')})
                      :resume :on-done]]])))

  :on-done   (fn [self ans]
               (case ans
                 ;; From the summary's Back button — re-call the colour step.
                 ::back  [self
                          [[:call (s/embed :demo/wizard-step
                                           {:label       "Favourite colour?"
                                            :placeholder "rebeccapurple"
                                            :initial     (:colour self)})
                            :resume :on-colour]]]
                 ;; From the summary's "Start over" button — wipe and reboot.
                 ::restart [(assoc self :name nil :colour nil)
                            [[:call (s/embed :demo/wizard-step
                                             {:label       "What's your name?"
                                              :placeholder "Ada"})
                              :resume :on-name]]]
                 [self [[:end {:wizard :complete}]]])))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/wizard" :demo/wizard)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

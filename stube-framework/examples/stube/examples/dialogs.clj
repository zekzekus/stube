(ns stube.examples.dialogs
  "Three reusable modal dialogs built as `:call`/`:answer` components,
  plus a task that exercises them in sequence.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/dialogs>.

  ──────────────────────────────────────────────────────────────────────
  Seaside analogue
  ──────────────────────────────────────────────────────────────────────

  Maps directly onto:

      WAYesOrNoDialog   ↔  :ui/confirm
      WAInputDialog     ↔  :ui/prompt
      WAChoiceDialog    ↔  :ui/choose

  The Seaside book introduces these as the canonical `call:`/`answer:`
  example: `confirm:`, `request:`, `chooseFrom:caption:` are convenience
  methods on `WAComponent` that internally `call:` a tiny dialog
  component and block on the answer.  This file is the stube
  equivalent — and the natural place to think about whether such
  helpers should live in `stube.core`.

  ──────────────────────────────────────────────────────────────────────
  DX note
  ──────────────────────────────────────────────────────────────────────

  The local `confirm`, `prompt` and `choose` defns at the bottom of this
  file are zero-cost wrappers over `s/embed`.  If we end up writing
  them in every other demo, they should graduate into `stube.core`
  alongside `s/embed`."
  (:require [stube.core :as s]))

;; ---------------------------------------------------------------------------
;; :ui/confirm — yes/no modal
;; ---------------------------------------------------------------------------

(s/defcomponent :ui/confirm
  :init   (fn [{:keys [question]}]
            {:question (or question "Are you sure?")})

  :render (fn [self]
            [:section {:id    (:instance/id self)
                       :class "stube-modal"
                       :style "max-width:24rem; padding:1.25rem; margin:1rem auto;
                               border:1px solid #888; border-radius:0.5rem;
                               background:#f8f8f8;
                               font-family:system-ui, sans-serif;"}
             [:p {:style "margin-top:0;"} (:question self)]
             [:div {:style "display:flex; gap:0.5rem; justify-content:flex-end;"}
              [:button (merge {:type "button"
                               :style "padding:0.4rem 1rem;"}
                              (s/on self :click :as :no))
               "No"]
              [:button (merge {:type "button"
                               :style "padding:0.4rem 1rem;
                                       background:#36c; color:white;
                                       border:1px solid #25b;"}
                              (s/on self :click :as :yes))
               "Yes"]]])

  :handle (fn [self {:keys [event]}]
            (case event
              :yes [self [[:answer true]]]
              :no  [self [[:answer false]]]
              [self []])))

;; ---------------------------------------------------------------------------
;; :ui/prompt — single text-field dialog
;; ---------------------------------------------------------------------------
;;
;; OK answers the typed string; Cancel answers ::cancel.  Like the
;; wizard's per-instance signal trick, we key the bound signal on the
;; iid so several prompts in a row don't collide.

(defn- prompt-signal [self]
  (keyword (str "prompt-" (:instance/id self))))

(s/defcomponent :ui/prompt
  :init   (fn [{:keys [label default]}]
            {:label   (or label "Please enter a value:")
             :default (or default "")})

  :render (fn [self]
            (let [sig (prompt-signal self)]
              [:form (merge {:id    (:instance/id self)
                             :class "stube-modal"
                             :style "max-width:24rem; padding:1.25rem; margin:1rem auto;
                                     border:1px solid #888; border-radius:0.5rem;
                                     background:#f8f8f8;
                                     font-family:system-ui, sans-serif;"}
                            (s/on self :submit))
               [:label {:style "display:block; margin-bottom:0.5rem;"}
                (:label self)]
               [:input (merge {:name      "value"
                               :value     (:default self)
                               :autofocus true
                               :style     "width:100%; padding:0.4rem; font-size:1rem;"}
                              (s/bind sig))]
               [:div {:style "display:flex; gap:0.5rem; justify-content:flex-end;
                              margin-top:0.75rem;"}
                [:button (merge {:type "button"
                                 :style "padding:0.4rem 1rem;"}
                                (s/on self :click :as :cancel))
                 "Cancel"]
                [:button {:type "submit"
                          :style "padding:0.4rem 1rem;
                                  background:#36c; color:white;
                                  border:1px solid #25b;"}
                 "OK"]]]))

  :handle (fn [self {:keys [event signals]}]
            (case event
              :submit (let [v (get signals (prompt-signal self) (:default self))]
                        [self [[:answer v]]])
              :cancel [self [[:answer ::cancel]]]
              [self []])))

;; ---------------------------------------------------------------------------
;; :ui/choose — one-of-N picker
;; ---------------------------------------------------------------------------
;;
;; Each option becomes its own button with `:as :pick-<index>` because
;; we don't yet have structured-payload events (see `seaside-examples.md`
;; Tier 1 §calendar for the same workaround).

(s/defcomponent :ui/choose
  :init   (fn [{:keys [caption options]}]
            {:caption (or caption "Pick one:")
             :options (vec options)})

  :render (fn [self]
            [:section {:id    (:instance/id self)
                       :class "stube-modal"
                       :style "max-width:24rem; padding:1.25rem; margin:1rem auto;
                               border:1px solid #888; border-radius:0.5rem;
                               background:#f8f8f8;
                               font-family:system-ui, sans-serif;"}
             [:p {:style "margin-top:0;"} (:caption self)]
             [:div {:style "display:flex; flex-direction:column; gap:0.4rem;"}
              (for [[i opt] (map-indexed vector (:options self))]
                [:button (merge {:type  "button"
                                 :key   i
                                 :style "padding:0.4rem 0.8rem; text-align:left;"}
                                (s/on self :click :as (keyword (str "pick-" i))))
                 (str opt)])]
             [:div {:style "margin-top:0.75rem; text-align:right;"}
              [:button (merge {:type "button" :style "padding:0.4rem 1rem;"}
                              (s/on self :click :as :cancel))
               "Cancel"]]])

  :handle (fn [self {:keys [event]}]
            (cond
              (= event :cancel) [self [[:answer ::cancel]]]

              ;; `:pick-<n>` → answer with the corresponding option value.
              (clojure.string/starts-with? (name event) "pick-")
              (let [idx (parse-long (subs (name event) (count "pick-")))]
                [self [[:answer (get-in self [:options idx])]]])

              :else [self []])))

;; ---------------------------------------------------------------------------
;; Convenience helpers (DX experiment)
;; ---------------------------------------------------------------------------
;;
;; These exist so the demo task below reads like Seaside.  If they
;; prove pull-their-weight in other examples too, they should move
;; into `stube.core`.

(defn confirm [question]
  (s/embed :ui/confirm {:question question}))

(defn prompt
  ([label]              (prompt label nil))
  ([label default]      (s/embed :ui/prompt {:label label :default default})))

(defn choose
  ([options]          (choose options "Pick one:"))
  ([options caption]  (s/embed :ui/choose {:caption caption :options options})))

;; ---------------------------------------------------------------------------
;; The demo task — uses defflow so the script reads top-to-bottom
;; ---------------------------------------------------------------------------

(s/defcomponent :ui/info
  :init   (fn [{:keys [text]}] {:text text})
  :render (fn [self]
            [:section {:id    (:instance/id self)
                       :style "max-width:24rem; padding:1.25rem; margin:1rem auto;
                               border:1px solid #888; border-radius:0.5rem;
                               background:#eef;
                               font-family:system-ui, sans-serif;"}
             [:p {:style "margin-top:0;"} (:text self)]
             [:button (merge {:type "button" :style "padding:0.4rem 1rem;"}
                             (s/on self :click :as :ok))
              "OK"]])
  :handle (fn [self _] [self [[:answer :ok]]]))

(defn- info [text] (s/embed :ui/info {:text text}))

(s/defflow :demo/dialogs []
  ;; A tiny script that exercises all three dialogs end to end.
  (if-not (s/await (confirm "Ready to play?"))
    (s/await (info "Maybe next time."))
    (let [name (s/await (prompt "What's your name?" "Ada"))]
      (if (= name ::cancel)
        (s/await (info "Cancelled."))
        (let [pick (s/await (choose ["red" "green" "blue"]
                                    (str "Pick a colour, " name ":")))]
          (if (= pick ::cancel)
            (s/await (info (str "OK " name ", maybe later.")))
            (s/await (info (str "Hello " name " — your colour is " pick "."))))))))
  {:done true})

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/dialogs" :demo/dialogs)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

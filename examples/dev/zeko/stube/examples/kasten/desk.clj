(ns dev.zeko.stube.examples.kasten.desk
  "Root component for the kasten UI port. Owns the open-note stack and
  embeds one `:kasten/column` per open note via `:call-in-slot` with a
  note-id-derived slot key. The CSS is concatenated from the per-layer
  files in `kasten/css/layers/` at namespace-load time and inlined in
  the rendered tree (stube has no static-file mount hook; this is the
  smallest way to ship a rich stylesheet inside one example)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dev.zeko.stube.core :as s]
            [dev.zeko.stube.examples.kasten.column]
            [dev.zeko.stube.examples.kasten.mock :as mock]))

;; ---------------------------------------------------------------------------
;; CSS bundle (read once)
;; ---------------------------------------------------------------------------

(def ^:private layer-order
  ["tokens" "base" "utils" "layout" "topbar" "ledger" "typography"
   "links" "folgezettel" "forms" "cards" "states" "responsive"
   "overlays" "a11y" "theme-dark"])

(defn- slurp-layer [name]
  (some-> (io/resource (str "dev/zeko/stube/examples/kasten/css/layers/" name ".css"))
          slurp))

(def ^:private bundled-css
  (str "@layer " (str/join ", " layer-order) ";\n\n"
       (str/join "\n\n"
                 (keep slurp-layer layer-order))))

;; ---------------------------------------------------------------------------
;; Pure stack helpers (mirrors kasten's notes/state)
;; ---------------------------------------------------------------------------

(defn- slot-key [note-id]
  (keyword "kasten" (str "slot-" note-id)))

(defn- index-of [v x]
  (some (fn [[i y]] (when (= y x) i)) (map-indexed vector v)))

(defn- open-in-stack
  "Returns the new stack vector after opening `note-id`. If already in
  the stack, leaves it alone (focus changes only)."
  [stack note-id]
  (if (some #{note-id} stack)
    stack
    (conj (vec stack) note-id)))

(defn- focus-of [stack note-id]
  (or (index-of stack note-id)
      (max 0 (dec (count stack)))))

(defn- close-from-stack [stack note-id]
  (vec (remove #{note-id} stack)))

(defn- shift-focus [focus delta stack]
  (let [n (count stack)]
    (if (zero? n) 0
        (max 0 (min (+ focus delta) (dec n))))))

;; ---------------------------------------------------------------------------
;; Topbar (inline, no embedding — pure render)
;; ---------------------------------------------------------------------------

(defn- topbar [self]
  (let [stack (:stack self)
        focus (:focus self)
        notes (:notes mock/catalog)
        stack-count (count stack)
        focus-index (if (seq stack) (inc focus) 0)
        empty? (zero? stack-count)
        at-end? (or empty? (>= focus (dec stack-count)))
        at-start? (or empty? (<= focus 0))]
    [:header.notes-topbar {:id (str (:instance/id self) "-topbar")}
     [:div.notes-topbar__brand
      [:a.notes-topbar__home {:href "#"} "kasten"]
      [:a.notes-topbar__about {:href "#"} "about"]]
     [:div.notes-topbar__status
      [:span "notes " [:strong (count notes)]]
      [:span "stack " [:strong stack-count]]
      [:span "focus " [:strong (if (zero? focus-index) "--" (str focus-index "/" stack-count))]]]
     [:div.notes-topbar__controls
      [:button.notes-control.notes-control--primary
       (merge {:type "button"} (s/on self :click :as :new))
       [:span "New note"]]
      [:button.notes-control
       (merge {:type "button" :disabled at-start?}
              (s/on self :click :as :prev))
       [:span "Prev"]]
      [:button.notes-control
       (merge {:type "button" :disabled at-end?}
              (s/on self :click :as :next))
       [:span "Next"]]
      [:button.notes-control.notes-control--danger
       (merge {:type "button" :disabled empty?}
              (s/on self :click :as :close-current))
       [:span "Close"]]]
     [:p.notes-topbar__hint
      [:span [:kbd "←"] " / " [:kbd "→"]]
      [:span " to move"]
      [:span " · "]
      [:span [:kbd "x"] " to close"]]]))

;; ---------------------------------------------------------------------------
;; Discovery / desk index (empty / browsing state)
;; ---------------------------------------------------------------------------

(defn- discovery-card [self note]
  [:button.note-disc-card
   (merge {:type "button" :title (:note/title note)}
          (s/on self :click :as [:open (:xt/id note)]))
   [:span.note-disc-card__tag (:note/tag note)]
   [:h3 {:style "margin:.2rem 0 .35rem; font-family:var(--notes-serif); font-size:1.05rem;"}
    (:note/title note)]
   [:p {:style "margin:0; color:var(--notes-card-summary); font-size:.88rem; line-height:1.4;"}
    (:note/summary note)]
   [:span {:style "margin-top:.4rem; color:var(--notes-dim); font-size:.78rem;"}
    (str (:note/date note) " · " (:note/word-count note) "w · "
         "↗" (:note/forward-link-count note 0)
         " ↙" (:note/backlink-count note 0))]])

(defn- desk-index [self]
  [:section.notes-empty-state.notes-empty-state--full
   [:p.notes-empty-state__eyebrow {:style "color:var(--notes-dim); font-size:.75rem;
                                            text-transform:uppercase; letter-spacing:.14em;"}
    "Desk Index"]
   [:h1.notes-empty-state__title
    {:style "font-family:var(--notes-serif); font-size:1.85rem; margin:.2rem 0 .8rem;"}
    "A reading room for notes that prefer to stay open."]
   [:p.notes-empty-state__sub
    {:style "color:var(--notes-dim); margin:0 0 1rem;"}
    "Open any card to begin a column. Pile new ones beside it without losing your place."]
   [:section.notes-discovery
    [:div.notes-discovery__field
     (for [n (:notes mock/catalog)]
       ^{:key (:xt/id n)} (discovery-card self n))]]])

;; ---------------------------------------------------------------------------
;; Ledger of open columns
;; ---------------------------------------------------------------------------

(defn- ledger [self]
  [:div.ledger-scroller
   [:div.ledger-spacer.ledger-spacer--start]
   (for [note-id (:stack self)]
     ^{:key note-id} (s/render-slot self (slot-key note-id)))
   [:div.ledger-spacer.ledger-spacer--end]])

;; ---------------------------------------------------------------------------
;; The desk component
;; ---------------------------------------------------------------------------

(defn- open-effects
  "Compute the [self', effects] pair when opening `note-id` from `self`."
  [self note-id]
  (let [stack' (open-in-stack (:stack self) note-id)
        focus' (focus-of stack' note-id)
        self'  (assoc self :stack stack' :focus focus')
        already-open? (and (some #{note-id} (:stack self)) true)]
    (if already-open?
      ;; Already mounted; just shift focus, re-render parent.
      [self' []]
      [self'
       [[:call-in-slot (slot-key note-id)
         (s/embed :kasten/column {:note-id note-id
                                  :stack-index (dec (count stack'))
                                  :parent-id (:instance/id self)})
         :resume :on-column]]])))

(s/defcomponent :kasten/desk
  :doc "Kasten desk: stack of open note columns plus topbar."

  :init
  (fn [_]
    {:stack []
     :focus 0})

  :keep #{}

  :render
  (fn [self]
    [:div {:id (:instance/id self)
           :class "notes-app"}
     [:style {:type "text/css"} bundled-css]
     [:div.notes-shell
      (topbar self)
      (if (seq (:stack self))
        (ledger self)
        (desk-index self))]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :open
      (open-effects self payload)

      :new
      ;; Mock: just open the first not-yet-open note.
      (let [available (->> (:notes mock/catalog)
                           (map :xt/id)
                           (remove (set (:stack self)))
                           first)]
        (if available
          (open-effects self available)
          [self []]))

      :prev
      [(update self :focus shift-focus -1 (:stack self)) []]

      :next
      [(update self :focus shift-focus 1 (:stack self)) []]

      :close-current
      (let [stack (:stack self)
            focus (:focus self)]
        (if-let [target (get stack focus)]
          [(-> self
               (update :stack close-from-stack target)
               (update :focus #(max 0 (min % (max 0 (- (count (close-from-stack stack target)) 1))))))
           []]
          [self []]))

      [self []]))

  ;; Resume from `:kasten/column` — the column answers `[:close id]` or
  ;; `[:open id]`.
  :on-column
  (fn [self answer]
    (let [[op note-id] (when (vector? answer) answer)]
      (case op
        :close
        [(-> self
             (update :stack close-from-stack note-id)
             (update :focus #(max 0 (min % (max 0 (dec (count (close-from-stack (:stack self) note-id))))))))
         []]

        :open
        (open-effects self note-id)

        [self []]))))

(s/mount! "/kasten" :kasten/desk)

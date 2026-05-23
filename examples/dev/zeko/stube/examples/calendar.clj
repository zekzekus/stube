(ns dev.zeko.stube.examples.calendar
  "Mini calendar / date-picker — Seaside's `WAMiniCalendar`.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/calendar>.

  ──────────────────────────────────────────────────────────────────────
  What this exercises
  ──────────────────────────────────────────────────────────────────────

  * A non-trivial single-component renderer: a 7-column grid of day
    cells, prev/next month navigation, a heading.
  * **One handler routing per-cell clicks**.  Each day cell routes to
    `:pick-day` and carries the day number as a structured payload.
  * Composition with the dialogs example's task pattern: a small flow
    `:call`s the picker and shows the chosen date.

  ──────────────────────────────────────────────────────────────────────
  Reveals — structured event payloads
  ──────────────────────────────────────────────────────────────────────

  Earlier versions encoded the day-of-month into route names
  (`:pick-1`, `:pick-2`, …) and parsed it back out in the handler.
  The current structured event payload keeps the route semantic and
  lets the handler read `:payload` directly."
  (:require [dev.zeko.stube.core :as s])
  (:import (java.time YearMonth)
           (java.time.format TextStyle)
           (java.util Locale)))

;; ---------------------------------------------------------------------------
;; Pure date helpers
;; ---------------------------------------------------------------------------

(defn- ym->title [^YearMonth ym]
  (str (.getDisplayName (.getMonth ym) TextStyle/FULL Locale/ENGLISH)
       " "
       (.getYear ym)))

(defn- ym-cells
  "Return a flat seq of 42 cells (6 weeks × 7 days) covering the visible
  month grid.  Each cell is `{:day n :in-month? bool :date LocalDate}`.
  Weeks start on Monday."
  [^YearMonth ym]
  (let [first-of-month (.atDay ym 1)
        ;; getValue: Mon=1 … Sun=7.  Subtract 1 to get a 0-based offset
        ;; from Monday.
        offset         (-> first-of-month .getDayOfWeek .getValue dec)
        start          (.minusDays first-of-month offset)]
    (for [i (range 42)]
      (let [d (.plusDays start i)]
        {:day        (.getDayOfMonth d)
         :in-month?  (= (YearMonth/from d) ym)
         :date       d}))))

;; ---------------------------------------------------------------------------
;; The picker component
;; ---------------------------------------------------------------------------

(defn- nav-button [self label event-kw]
  [:button (merge {:type "button"
                   :style "padding:0.25rem 0.75rem; border:1px solid #aaa;
                           background:#eee; cursor:pointer;
                           border-radius:0.25rem;"}
                  (s/on self :click :as event-kw))
   label])

(defn- day-cell [self {:keys [day in-month?]}]
  ;; Two reasons not to render an `:on click` for out-of-month cells:
  ;; (a) the user shouldn't accidentally pick from an adjacent month,
  ;; (b) skipping the attribute is the cheapest way to disable the
  ;; cell client-side without an extra css class.
  (let [base "padding:0.4rem 0; text-align:center; border-radius:0.2rem;
              font-size:0.95rem;"
        style (cond
                (not in-month?) (str base "color:#bbb;")
                :else           (str base "background:#f4f4f4; cursor:pointer;
                                           border:1px solid #ddd;"))]
    [:div (cond-> {:style style}
            in-month?
            (merge (s/on self :click :as [:pick-day day])))
     day]))

(s/defcomponent :ui/calendar
  :init (fn [{:keys [year-month]}]
          ;; Default to the current month if the caller didn't pin one.
          (let [^YearMonth ym (or year-month (YearMonth/now))]
            {:year  (.getYear ym)
             :month (.getMonthValue ym)}))

  :render
  (fn [self]
    (let [ym    (YearMonth/of (int (:year self)) (int (:month self)))
          cells (ym-cells ym)
          weeks (partition 7 cells)]
      [:section (s/root-attrs self
                  {:style "display:inline-block; padding:1rem;
                           border:1px solid #888; border-radius:0.5rem;
                           font-family:system-ui, sans-serif;
                           background:#fff;"})
       [:header {:style "display:flex; justify-content:space-between;
                          align-items:center; margin-bottom:0.5rem;"}
        (nav-button self "‹" :prev)
        [:strong (ym->title ym)]
        (nav-button self "›" :next)]
       [:div {:style "display:grid; grid-template-columns:repeat(7, 2.5rem);
                       gap:0.2rem;"}
        ;; Day-of-week headers (Mon-first).
        (for [d ["Mo" "Tu" "We" "Th" "Fr" "Sa" "Su"]]
          [:div {:key d
                 :style "text-align:center; font-size:0.8rem;
                          color:#777; padding:0.2rem 0;"}
           d])
        (for [[i week] (map-indexed vector weeks)
              [j cell] (map-indexed vector week)]
          (with-meta (day-cell self cell) {:key (str i "-" j)}))]
       [:div {:style "margin-top:0.75rem; text-align:right;"}
        [:button (merge {:type "button"
                         :style "padding:0.25rem 0.75rem;"}
                        (s/on self :click :as :cancel))
         "Cancel"]]]))

  :handle
  (fn [self {:keys [event payload]}]
    (let [ym (YearMonth/of (int (:year self)) (int (:month self)))]
      (cond
        (= event :prev)
        (let [ym' (.minusMonths ym 1)]
          (assoc self :year (.getYear ym') :month (.getMonthValue ym')))

        (= event :next)
        (let [ym' (.plusMonths ym 1)]
          (assoc self :year (.getYear ym') :month (.getMonthValue ym')))

        (= event :cancel)
        [(s/answer ::cancel)]

        (= event :pick-day)
        [(s/answer (.atDay ym payload))]

        :else
        nil))))

;; ---------------------------------------------------------------------------
;; A trivial driver flow so we can see what the picker answers
;; ---------------------------------------------------------------------------

(s/defcomponent :ui/show-date
  :init   (fn [{:keys [date]}] {:date date})
  :render (fn [self]
            [:section (s/root-attrs self
                        {:style "padding:1rem; max-width:24rem;
                                 font-family:system-ui, sans-serif;"})
             [:h3 "You picked"]
             [:p [:strong (str (:date self))]]
             [:button (merge {:type "button"
                              :style "padding:0.4rem 1rem;"}
                             (s/on self :click :as :again))
              "Pick another"]])
  :handle (fn [_self _] [(s/answer :again)]))

(s/defflow :demo/calendar []
  (loop []
    (let [d (s/await (s/embed :ui/calendar {}))]
      (cond
        (= d ::cancel) {:cancelled true}
        :else (do (s/await (s/embed :ui/show-date {:date d}))
                  (recur))))))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/calendar" :demo/calendar)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

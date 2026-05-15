(ns dev.zeko.stube.examples.table-report
  "Sortable table report — Seaside's `WATableReport`.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/table-report>.

  The reusable `:ui/table-report` takes EDN column configuration maps.
  Header clicks update sort state and the component re-renders itself;
  no new framework primitive is involved."
  (:require [dev.zeko.stube.core :as s]))

;; ---------------------------------------------------------------------------
;; Demo data
;; ---------------------------------------------------------------------------

(def ^:private rows
  [{:id 1 :name "Ada"      :team "Kernel"    :merged 18 :reviews 41 :quality 0.98 :status "green"}
   {:id 2 :name "Grace"    :team "Transport" :merged 11 :reviews 36 :quality 0.94 :status "green"}
   {:id 3 :name "Edsger"   :team "Kernel"    :merged 7  :reviews 28 :quality 0.99 :status "blue"}
   {:id 4 :name "Barbara"  :team "Runtime"   :merged 13 :reviews 24 :quality 0.92 :status "yellow"}
   {:id 5 :name "Donald"   :team "Docs"      :merged 5  :reviews 31 :quality 0.96 :status "green"}
   {:id 6 :name "Fran"     :team "Examples"  :merged 15 :reviews 18 :quality 0.91 :status "yellow"}
   {:id 7 :name "Alan"     :team "Runtime"   :merged 9  :reviews 33 :quality 0.97 :status "blue"}
   {:id 8 :name "Margaret" :team "Transport" :merged 16 :reviews 27 :quality 0.95 :status "green"}])

(def ^:private columns
  [{:id :name    :label "Name"       :value :name    :sortable? true}
   {:id :team    :label "Team"       :value :team    :sortable? true}
   {:id :merged  :label "Merged PRs" :value :merged  :sortable? true :align :right :format :integer}
   {:id :reviews :label "Reviews"    :value :reviews :sortable? true :align :right :format :integer}
   {:id :quality :label "Quality"    :value :quality :sortable? true :align :right :format :percent}
   {:id :status  :label "Status"     :value :status  :sortable? true}])

;; ---------------------------------------------------------------------------
;; Table helpers
;; ---------------------------------------------------------------------------

(defn- column-value [row column]
  (let [path (:value column (:id column))]
    (cond
      (keyword? path) (get row path)
      (vector? path)  (get-in row path)
      :else           (get row (:id column)))))

(defn- compare-values [a b]
  (cond
    (and (number? a) (number? b)) (compare a b)
    :else                         (compare (str a) (str b))))

(defn- sorted-rows [self]
  (let [{:keys [column dir]} (:sort self)
        col                 (some #(when (= (:id %) column) %) (:columns self))
        ordered             (if col
                              (sort-by #(column-value % col) compare-values (:rows self))
                              (:rows self))]
    (vec (if (= dir :desc) (reverse ordered) ordered))))

(defn- next-sort [sort column-id]
  (if (= (:column sort) column-id)
    {:column column-id
     :dir    (if (= (:dir sort) :asc) :desc :asc)}
    {:column column-id :dir :asc}))

(defn- format-value [value column]
  (case (:format column)
    :integer (format "%d" (long value))
    :percent (format "%.0f%%" (* 100.0 (double value)))
    (str value)))

(defn- align-style [column]
  (when (= (:align column) :right)
    "text-align:right; font-variant-numeric:tabular-nums;"))

(defn- sort-arrow [self column]
  (when (= (get-in self [:sort :column]) (:id column))
    (if (= (get-in self [:sort :dir]) :asc) " ▲" " ▼")))

(defn- render-heading [self column]
  (let [active? (= (get-in self [:sort :column]) (:id column))]
    [:th {:style (str "padding:0.45rem 0.55rem; border-bottom:1px solid #ccc;
                      background:#f6f6f8; text-align:left;"
                     (align-style column))}
     (if (:sortable? column)
       [:button (merge (cond-> {:type  "button"
                                :class (str "stube-button"
                                            (when active? " stube-button--primary"))
                                :style "padding:0.2rem 0.4rem;"}
                         active? (assoc :aria-sort (name (get-in self [:sort :dir]))))
                       (s/on self :click :as [:sort (:id column)]))
        (:label column) (sort-arrow self column)]
       (:label column))]))

(defn- render-cell [row column]
  [:td {:style (str "padding:0.45rem 0.55rem; border-bottom:1px solid #eee;"
                    (align-style column))}
   (format-value (column-value row column) column)])

;; ---------------------------------------------------------------------------
;; Reusable component
;; ---------------------------------------------------------------------------

(s/defcomponent :ui/table-report
  :doc "Reusable WATableReport-style component with EDN column maps and click-to-sort state."

  :init
  (fn [{:keys [rows columns sort caption]}]
    {:rows    (vec rows)
     :columns (vec columns)
     :sort    (or sort {:column (:id (first columns)) :dir :asc})
     :caption (or caption "Report")})

  :render
  (fn [self]
    [:section (s/root-attrs self {:class "stube-card"
                                  :style "max-width:52rem; overflow-x:auto;"})
     [:h3 {:style "margin-top:0;"} (:caption self)]
     [:table {:style "width:100%; border-collapse:collapse; font-size:0.95rem;"}
      [:thead
       [:tr
        (for [column (:columns self)]
          (with-meta (render-heading self column) {:key (:id column)}))]]
      [:tbody
       (for [row (sorted-rows self)]
         [:tr {:key (:id row)}
          (for [column (:columns self)]
            (with-meta (render-cell row column) {:key (:id column)}))])]]
     [:p {:style "color:#666; font-size:0.85rem; margin-bottom:0;"}
      "Sorted by " [:code (name (get-in self [:sort :column]))]
      " " (name (get-in self [:sort :dir])) "."]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :sort (update self :sort next-sort payload)
      nil)))

;; ---------------------------------------------------------------------------
;; Demo wrapper
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/table-report
  :doc "WATableReport port: column configuration maps plus sortable headers."

  :children {:slot/report (s/embed :ui/table-report
                                   {:rows    rows
                                    :columns columns
                                    :caption "Maintainer activity"})}

  :render
  (fn [self]
    [:section (s/root-attrs self {:style "padding:1rem; font-family:system-ui, sans-serif;"})
     [:h2 "Table report"]
     [:p {:style "max-width:42rem; color:#555;"}
      "The report component is driven entirely by EDN column maps.  Each "
      "sortable heading routes a structured " [:code ":sort"]
      " event carrying the column id."]
     (s/render-slot self :slot/report)]))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/table-report" :demo/table-report)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

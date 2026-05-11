(ns dev.zeko.stube.examples.paginated-list
  "Paginated list — Seaside's `WABatchedList`.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/paginated-list>.

  The reusable `:ui/paginated-list` component accepts the collection,
  page size, and a render callback as init args.  To keep the live
  conversation EDN-clean, the demo passes the callback as a qualified
  symbol and resolves it at render time instead of storing an opaque
  function object in component state."
  (:require [dev.zeko.stube.core :as s]))

;; ---------------------------------------------------------------------------
;; Demo data
;; ---------------------------------------------------------------------------

(def ^:private first-names
  ["Ada" "Grace" "Edsger" "Barbara" "Donald" "Fran" "Alan" "Margaret"])

(def ^:private last-names
  ["Lovelace" "Hopper" "Dijkstra" "Liskov" "Knuth" "Allen" "Turing" "Hamilton"])

(def ^:private teams
  ["Kernel" "Transport" "Examples" "Docs"])

(def ^:private roles
  ["maintainer" "reviewer" "builder" "writer"])

(defn- sample-people []
  (mapv (fn [i]
          {:id      (inc i)
           :name    (str (nth first-names (mod i (count first-names)))
                         " "
                         (nth last-names (mod (* i 3) (count last-names))))
           :team    (nth teams (mod i (count teams)))
           :role    (nth roles (mod (+ i 2) (count roles)))
           :tickets (+ 2 (mod (* 7 (inc i)) 19))})
        (range 37)))

;; ---------------------------------------------------------------------------
;; Pure paging helpers
;; ---------------------------------------------------------------------------

(defn- normalise-page-size [n]
  (max 1 (long (or n 10))))

(defn- page-count [items page-size]
  (max 1 (long (Math/ceil (/ (double (count items))
                             (normalise-page-size page-size))))))

(defn- clamp [lo n hi]
  (max lo (min n hi)))

(defn- visible-window [self]
  (let [items     (vec (:items self))
        size      (normalise-page-size (:page-size self))
        pages     (page-count items size)
        page      (clamp 0 (or (:page self) 0) (dec pages))
        start     (* page size)
        end       (min (count items) (+ start size))]
    {:items (subvec items start end)
     :page  page
     :pages pages
     :start start
     :end   end
     :total (count items)}))

(defn- range-label [{:keys [start end total]}]
  (if (zero? total)
    "0 of 0"
    (str (inc start) "–" end " of " total)))

;; ---------------------------------------------------------------------------
;; Row render callbacks
;; ---------------------------------------------------------------------------

(defn render-person-row
  "Render one row for the paginated-list demo.  Public by design: the
  list stores this var's qualified symbol as an EDN-safe callback handle."
  [person absolute-index]
  [:div {:style "display:grid; grid-template-columns:3rem 1.4fr 1fr 1fr 5rem;
                 gap:0.75rem; align-items:center; padding:0.45rem 0.6rem;
                 border-bottom:1px solid #eee;"}
   [:span {:style "color:#888; font-variant-numeric:tabular-nums;"}
    (format "%02d" (inc absolute-index))]
   [:strong (:name person)]
   [:span (:team person)]
   [:span {:style "color:#555;"} (:role person)]
   [:span {:style "text-align:right; font-variant-numeric:tabular-nums;"}
    (:tickets person)]])

(defn render-default-row
  "Fallback row renderer for callers that omit `:render-row`."
  [item absolute-index]
  [:div {:style "padding:0.45rem 0.6rem; border-bottom:1px solid #eee;"}
   [:span {:style "display:inline-block; min-width:3rem; color:#888;"}
    (format "%02d" (inc absolute-index))]
   [:code (pr-str item)]])

(defn- resolve-renderer [renderer]
  (cond
    (fn? renderer)
    renderer

    (qualified-symbol? renderer)
    (if-let [v (requiring-resolve renderer)]
      @v
      (throw (ex-info "Paginated-list renderer symbol does not resolve"
                      {:renderer renderer})))

    :else
    (throw (ex-info "Paginated-list renderer must be a function or qualified symbol"
                    {:renderer renderer}))))

;; ---------------------------------------------------------------------------
;; Reusable component
;; ---------------------------------------------------------------------------

(defn- nav-button [self label event disabled?]
  [:button (merge {:type     "button"
                   :class    "stube-button"
                   :disabled (boolean disabled?)}
                  (s/on self :click :as event))
   label])

(defn- page-button [self current-page n]
  (let [active? (= current-page n)]
    [:button (merge (cond-> {:type  "button"
                             :class (str "stube-button"
                                         (when active? " stube-button--primary"))}
                      active? (assoc :aria-current "page"))
                    (s/on self :click :as [:page n]))
     (inc n)]))

(s/defcomponent :ui/paginated-list
  :doc "Reusable WABatchedList-style component: collection + page state + EDN-safe row-render callback."

  :init
  (fn [{:keys [items page-size render-row caption]}]
    {:items      (vec items)
     :page-size  (normalise-page-size page-size)
     :page       0
     :render-row (or render-row
                     'dev.zeko.stube.examples.paginated-list/render-default-row)
     :caption    (or caption "Items")})

  :render
  (fn [self]
    (let [{:keys [items page pages start] :as window} (visible-window self)
          render-row (resolve-renderer (:render-row self))]
      [:section {:id    (:instance/id self)
                 :class "stube-card"
                 :style "max-width:46rem;"}
       [:header {:style "display:flex; align-items:baseline; gap:1rem;
                          margin-bottom:0.75rem;"}
        [:h3 {:style "margin:0;"} (:caption self)]
        [:span {:style "color:#666; font-size:0.9rem; margin-left:auto;"}
         (range-label window)]]
       [:div {:style "border:1px solid #ddd; border-bottom:none;"}
        [:div {:style "display:grid; grid-template-columns:3rem 1.4fr 1fr 1fr 5rem;
                       gap:0.75rem; padding:0.35rem 0.6rem; background:#f6f6f8;
                       border-bottom:1px solid #ddd; color:#555; font-size:0.85rem;
                       font-weight:600;"}
         [:span "#"] [:span "Name"] [:span "Team"] [:span "Role"]
         [:span {:style "text-align:right;"} "Tickets"]]
        (for [[absolute item] (map vector (range start (:end window)) items)]
          (with-meta (render-row item absolute) {:key (:id item)}))]
       [:footer {:class "stube-actions"
                 :style "align-items:center; flex-wrap:wrap;"}
        (nav-button self "Previous" :prev (zero? page))
        (for [n (range pages)]
          (page-button self page n))
        (nav-button self "Next" :next (= page (dec pages)))]]))

  :handle
  (fn [self {:keys [event payload]}]
    (let [pages (page-count (:items self) (:page-size self))
          move  (fn [n] (assoc self :page (clamp 0 n (dec pages))))]
      (case event
        :prev [(move (dec (:page self))) []]
        :next [(move (inc (:page self))) []]
        :page [(move payload) []]
        [self []]))))

;; ---------------------------------------------------------------------------
;; Demo wrapper
;; ---------------------------------------------------------------------------

(s/defcomponent :demo/paginated-list
  :doc "WABatchedList port: pagination state around a reusable row-render callback."

  :children {:slot/list (s/embed :ui/paginated-list
                                 {:items      (sample-people)
                                  :page-size  7
                                  :render-row 'dev.zeko.stube.examples.paginated-list/render-person-row
                                  :caption    "Contributors"})}

  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :style "padding:1rem; font-family:system-ui, sans-serif;"}
     [:h2 "Paginated list"]
     [:p {:style "max-width:42rem; color:#555;"}
      "A reusable list component owns only pagination state.  The row "
      "renderer is supplied as a qualified symbol, which keeps the "
      "conversation printable while still exercising Seaside's "
      [:code "renderBlock:"] " style API."]
     (s/render-slot self :slot/list)]))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/paginated-list" :demo/paginated-list)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

(ns dev.zeko.stube.examples.reading-list
  "Shareable views — URL as durable state.

  Visit <http://localhost:8080/reading-list> for an empty desk, or
  <http://localhost:8080/reading-list?items=clojure,datastar,seaside>
  to restore three reader columns from the URL.

  ──────────────────────────────────────────────────────────────────────
  What this exercises
  ──────────────────────────────────────────────────────────────────────

  * `:init-args-fn` on `s/mount!` parses `?items=` into `:items`.
  * `:emit-on-mount` (sugar over `:start`) emits the
    `[:set-keyed-children …]` setup so the keyed-children slot exists
    on first render — without this, the URL says \"open three columns\"
    but the desk shows nothing.
  * `:url` projects `:item-ids` back into `?items=` so adding or
    removing a column updates the address bar automatically.

  Two browsers hitting the same URL each get a fresh conversation id;
  the cid is opaque while the URL is the durable, shareable shape."
  (:require [clojure.string :as str]
            [dev.zeko.stube.core :as s]))

;; ---------------------------------------------------------------------------
;; Mock data
;; ---------------------------------------------------------------------------

(def ^:private articles
  {"clojure"
   {:title "Why Clojure?" :body "Lisp on the JVM, immutable by default."}
   "datastar"
   {:title "Datastar in one page"
    :body "Server-sent events for HTML morphs; no JS framework needed."}
   "seaside"
   {:title "Seaside's continuations"
    :body "call:/answer: as the spine of a server-rendered UI."}
   "kasten"
   {:title "Notes desk concepts"
    :body "Open notes are columns; wiki-links open more columns."}})

(defn- url-for [items]
  (if (empty? items)
    "/reading-list"
    (str "/reading-list?items=" (str/join "," items))))

(defn- parse-items [raw]
  (when (seq raw)
    (->> (str/split raw #",")
         (map str/trim)
         (remove str/blank?)
         vec)))

;; ---------------------------------------------------------------------------
;; Item — a single reader column
;; ---------------------------------------------------------------------------

(s/defcomponent :reading/item
  :init   (fn [{:keys [id]}] {:id id})
  :render (fn [self]
            (let [a (get articles (:id self))]
              [:article (s/root-attrs self {:class "stube-card"
                                            :style "min-width:14rem; padding:1rem;"})
               [:h3 {:style "margin-top:0;"} (or (:title a) (:id self))]
               [:p (or (:body a) "(no body)")]
               [:button (merge {:type "button"} (s/on self :click :as [:close (:id self)]))
                "Close"]])))

;; ---------------------------------------------------------------------------
;; Desk — keyed-children container, URL projection, restore-from-URL
;; ---------------------------------------------------------------------------

(defn- pairs [items]
  (mapv (fn [id] [id (s/embed :reading/item {:id id})]) items))

(s/defcomponent :reading/desk
  :doc "Reading list desk: keyed columns whose ids round-trip through the URL."

  :init
  (fn [{:keys [items]}]
    {:item-ids (vec (or items []))})

  ;; URL projection: kernel auto-emits a :history :replace whenever
  ;; `:item-ids` changes.  No per-handler ceremony.
  :url
  (fn [self] (url-for (:item-ids self)))

  ;; Restore-from-URL: emit the keyed-children setup as soon as the
  ;; root is instantiated, using the ids that arrived via :init.
  ;; `:emit-on-mount` is a thin sugar over `:start` — see docs/tutorial.md.
  :emit-on-mount
  (fn [self]
    (when (seq (:item-ids self))
      [(s/set-keyed-children :slot/items (pairs (:item-ids self)))]))

  :render
  (fn [self]
    [:section (s/root-attrs self {:style "max-width:60rem; margin:1rem auto; padding:1rem;
                                          font-family:system-ui, sans-serif;"})
     [:h1 "Reading list"]
     [:p {:style "color:#555;"}
      "URL: "
      [:code (url-for (:item-ids self))]
      "  — copy the URL and open it in another tab to restore these columns."]
     [:div {:style "margin:0.75rem 0;"}
      (for [id (sort (keys articles))
            :when (not (some #{id} (:item-ids self)))]
        [:button (merge {:type "button"
                         :key id
                         :style "margin-right:0.4rem;"}
                        (s/on self :click :as [:open id]))
         "Open " [:code id]])]
     [:div {:style "display:flex; gap:0.75rem; overflow-x:auto; align-items:flex-start;"}
      (s/keyed-children self :slot/items)]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :open
      (let [id     payload
            items' (if (some #{id} (:item-ids self))
                     (:item-ids self)
                     (conj (:item-ids self) id))]
        [(assoc self :item-ids items')
         [(s/set-keyed-children :slot/items (pairs items'))]])

      :close
      (let [id     payload
            items' (vec (remove #{id} (:item-ids self)))]
        [(assoc self :item-ids items')
         [(s/set-keyed-children :slot/items (pairs items'))]])

      self)))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/reading-list" :reading/desk
          {:init-args-fn (fn [req]
                           (let [raw (s/query-value req "items")]
                             {:items (or (parse-items raw) [])}))})

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

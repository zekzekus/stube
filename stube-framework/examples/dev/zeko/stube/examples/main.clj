(ns dev.zeko.stube.examples.main
  "Loads every shipped example onto a single server.

      clojure -M:examples

  After startup, visit <http://localhost:8080/> for the index page.

  The index lists each demo and one line on what it shows.  Every
  example file does its own `s/mount!` at load time; this namespace
  just `require`s them, mounts the index, and starts the server.

  See `seaside-examples.md` for the full curated list of Seaside apps
  and which ones drive new framework functionality."
  (:require [dev.zeko.stube.core              :as s]
            [dev.zeko.stube.examples.guess]
            [dev.zeko.stube.examples.multicounter]
            [dev.zeko.stube.examples.wizard]
            [dev.zeko.stube.examples.calc]
            [dev.zeko.stube.examples.dialogs]
            [dev.zeko.stube.examples.tabs]
            [dev.zeko.stube.examples.calendar]
            [dev.zeko.stube.examples.seaside-todo]
            [dev.zeko.stube.examples.todo]))

;; ---------------------------------------------------------------------------
;; Index page
;; ---------------------------------------------------------------------------
;;
;; The index is itself a stube component — no events, no children —
;; rendered once on first SSE connect.  Treating it as a flow keeps
;; the framework's invariants intact (every URL serves the same
;; shell + SSE bootstrap) and means we don't need a separate
;; static-page mechanism in the server.

(def ^:private demos
  ;; [path title blurb introduced-in]
  [["/guess"        "Guess the number"
    "Linear flow with `defflow` + `s/await`."
    "slice 1"]
   ["/multicounter" "Multicounter"
    "Three independent embedded counters; morph-by-id keeps siblings intact."
    "slice 2"]
   ["/wizard"       "Wizard with Back"
    "Multi-step form whose Back button rewinds the conversation."
    "slice 3"]
   ["/calc"         "Calculator"
    "Single component, dense event routing (one handler, ~20 buttons)."
    "tier 1"]
   ["/dialogs"      "Confirm / Prompt / Choose"
    "Reusable modal dialogs via `:call` / `:answer`."
    "tier 1"]
   ["/tabs"         "Tabbed navigation"
    "Inactive children stay alive in `:conv/instances` while off-screen."
    "tier 1"]
   ["/calendar"     "Mini calendar picker"
    "Per-cell click routing in a non-trivial single-component grid."
    "tier 1"]
   ["/todo"         "Todo list"
    "Dynamic list with slot-local in-place edit via `:call-in-slot`."
    "tier 1"]
   ["/seaside-todo" "Seaside book ToDo"
    "The HPI tutorial app: login/register, filters, task editor, report, and mirror notes."
    "book"]])

(s/defcomponent :demo/index
  :render
  (fn [self]
    [:section {:id    (:instance/id self)
               :style "max-width:42rem; margin:2rem auto; padding:1rem;
                       font-family:system-ui, sans-serif; color:#222;"}
     [:h1 {:style "margin-top:0;"} "stube examples"]
     [:p {:style "color:#555;"}
      "Each link below is an independent stube application — a fresh "
      "conversation, its own SSE stream, mounted at its own URL.  "
      "See "
      [:a {:href "https://github.com/SeasideSt/Seaside" :target "_blank"}
       "Seaside"]
      " for the originals these were ported from, and "
      [:code "seaside-examples.md"]
      " in the repo for the full catalogue."]
     [:ul {:style "list-style:none; padding:0; margin:1.5rem 0 0;"}
      (for [[path title blurb tag] demos]
        [:li {:key   path
              :style "padding:0.75rem 0; border-top:1px solid #eee;"}
         [:div {:style "display:flex; align-items:baseline; gap:0.75rem;
                        flex-wrap:wrap;"}
          [:a {:href  path
               :style "font-size:1.1rem; font-weight:bold; color:#36c;
                       text-decoration:none;"}
           title]
          [:code {:style "color:#888; font-size:0.85rem;"} path]
          [:span {:style "margin-left:auto; font-size:0.75rem; color:#999;
                          background:#f0f0f0; padding:0.1rem 0.4rem;
                          border-radius:0.2rem;"}
           tag]]
         [:div {:style "color:#555; margin-top:0.25rem;"} blurb]])]
     [:p {:style "color:#888; font-size:0.85rem; margin-top:2rem;"}
      "Tip: open the browser dev tools' Network panel and watch the SSE "
      "stream while you click around."]]))

(s/mount! "/" :demo/index)

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main [& _args]
  (s/start! {:port 8080})
  (println "stube examples up — visit http://localhost:8080/ for the index")
  @(promise))

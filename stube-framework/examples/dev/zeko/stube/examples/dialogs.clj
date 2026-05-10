(ns dev.zeko.stube.examples.dialogs
  "Stock modal dialogs exercised as Seaside-style convenience helpers.

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/dialogs>.

  ──────────────────────────────────────────────────────────────────────
  Seaside analogue
  ──────────────────────────────────────────────────────────────────────

  Maps directly onto:

      WAYesOrNoDialog   ↔  :ui/confirm / `s/confirm`
      WAInputDialog     ↔  :ui/prompt  / `s/prompt`
      WAChoiceDialog    ↔  :ui/choose  / `s/choose`

  The Seaside book introduces these as the canonical `call:`/`answer:`
  example: `confirm:`, `request:`, `chooseFrom:caption:` are convenience
  methods on `WAComponent` that internally `call:` a tiny dialog
  component and block on the answer.  `dev.zeko.stube.core` now ships the same
  verbs as thin wrappers over stock `dev.zeko.stube.ui` components."
  (:require [dev.zeko.stube.core :as s]))

(s/defflow :demo/dialogs []
  ;; A tiny script that exercises all stock dialogs end to end.
  (if-not (s/await (s/confirm "Ready to play?"))
    (s/await (s/info "Maybe next time."))
    (let [name (s/await (s/prompt "What's your name?" "Ada"))]
      (if (= name s/cancel)
        (s/await (s/info "Cancelled."))
        (let [pick (s/await (s/choose ["red" "green" "blue"]
                                      (str "Pick a colour, " name ":")))]
          (if (= pick s/cancel)
            (s/await (s/info (str "OK " name ", maybe later.")))
            (s/await (s/info (str "Hello " name " — your colour is " pick "."))))))))
  {:done true})

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/dialogs" :demo/dialogs)

(defn -main [& _args]
  (s/start! {:port 8080})
  @(promise))

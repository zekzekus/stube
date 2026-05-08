(ns stube.examples.guess
  "Slice-0 demo: Seaside's classic \"guess the number\" game, written
  with hand-rolled task components (no `defflow` macro yet).

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/guess>.

  The flow is:

      :demo/guess (task)
        └─ :on-start ─► :call :demo/prompt   ─► :on-guess
        └─ :on-guess ─► (re)call :demo/prompt or :demo/info
        └─ :on-done  ─► [:end]"
  (:require [stube.core :as s]))

;; ---------------------------------------------------------------------------
;; UI building blocks
;; ---------------------------------------------------------------------------

;; A reusable input that asks for a number and answers it back to the
;; caller.  `:keep #{:answer}` pulls the user's typed value into `self`
;; on every event, so we can read `(:answer self)` directly inside
;; `:handle`.
(s/defcomponent :demo/prompt
  :init   (fn [{:keys [text]}]
            {:text text :answer ""})

  :keep   #{:answer}

  :render (fn [self]
            [:form (merge {:id    (:instance/id self)
                           :class "stube-prompt"}
                          (s/on self :submit))
             [:p (:text self)]
             [:input (merge {:type "number" :name "answer" :autofocus true}
                            (s/bind :answer))]
             [:button {:type "submit"} "Guess"]])

  :handle (fn [self _evt]
            (let [parsed (parse-long (str (:answer self)))]
              (if parsed
                ;; Answer the parent (the wizard) with the parsed number.
                [self [[:answer parsed]]]
                ;; Bad input: re-render with an error message.  Returning
                ;; the same self with no effects causes the kernel to
                ;; auto-render this instance (see `dispatch` semantics).
                [(assoc self :text "Please enter a number.") []]))))

;; A passive notification with a "Continue" button that answers back to
;; the parent so the wizard knows to move on.
(s/defcomponent :demo/info
  :init   (fn [{:keys [text]}]
            {:text text})

  :render (fn [self]
            [:div (merge {:id    (:instance/id self)
                          :class "stube-info"}
                         (s/on self :ack))
             [:p (:text self)]
             [:button {:type "button"} "Continue"]])

  :handle (fn [self _evt]
            [self [[:answer :ack]]]))

;; ---------------------------------------------------------------------------
;; The wizard task
;; ---------------------------------------------------------------------------
;;
;; This component has no UI of its own.  It uses `:start` to launch the
;; first prompt as soon as the conversation boots, then loops with
;; resume keys until the player wins.

(s/defcomponent :demo/guess
  :init  (fn [_]
           {:target   (inc (rand-int 100))   ; 1..100 inclusive
            :attempts 0})

  ;; Kicked off automatically when the kernel pushes us onto the stack.
  :start (fn [self]
           [self [[:call (s/embed :demo/prompt {:text "Pick a number from 1 to 100."})
                   :resume :on-guess]]])

  :on-guess
  (fn [self n]
    (let [self' (update self :attempts inc)
          {:keys [target attempts]} self']
      (cond
        (< n target)
        [self' [[:call (s/embed :demo/prompt
                                {:text (str "Too low — try again. (Attempt " attempts ")")})
                 :resume :on-guess]]]

        (> n target)
        [self' [[:call (s/embed :demo/prompt
                                {:text (str "Too high — try again. (Attempt " attempts ")")})
                 :resume :on-guess]]]

        :else
        [self' [[:call (s/embed :demo/info
                                {:text (str "🎉 Got it! "
                                            target " in " attempts
                                            (if (= 1 attempts) " attempt." " attempts."))})
                 :resume :on-done]]])))

  :on-done
  (fn [self _]
    [self [[:end {:winner true :target (:target self) :attempts (:attempts self)}]]]))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/guess" :demo/guess)

(defn -main [& _args]
  (s/start! {:port 8080})
  ;; Block forever; ^C stops the JVM.
  @(promise))

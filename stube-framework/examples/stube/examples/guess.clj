(ns stube.examples.guess
  "Slice-1 demo: Seaside's classic \"guess the number\" game, written as
  a [[stube.core/defflow]].

  Run from the project root:

      clojure -M:examples

  Then visit <http://localhost:8080/guess>.

  Compare with `git log -p` to see the slice-0 hand-rolled task version
  this replaces.  The flow body now reads as a straight-line script:
  pick a target, loop asking for guesses until one is correct, then show
  a victory message.  No state-machine boilerplate, no resume keys, no
  `:start` hook.  Everything between `await`s is ordinary Clojure."
  (:require [stube.core :as s]))

;; ---------------------------------------------------------------------------
;; UI building blocks (unchanged from slice 0)
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
;; The flow
;; ---------------------------------------------------------------------------
;;
;; This is the slice-1 highlight.  The body looks like a script you'd
;; write to play the game by hand, with `s/await` standing in for "now
;; show this UI and wait for the user".  The `defflow` macro compiles
;; it down to a regular component that:
;;
;;   * holds a cloroutine continuation as instance state,
;;   * uses :start to take the first step,
;;   * routes every child :answer through one resume key.
;;
;; The recur point sits *outside* the `await` call (cloroutine restriction):
;; the await runs first, its result is bound to `n`, and only then we
;; recur.

(s/defflow :demo/guess []
  (let [target (inc (rand-int 100))]                  ; 1..100 inclusive
    (loop [attempts 1
           prompt   "Pick a number from 1 to 100."]
      (let [n (s/await (s/embed :demo/prompt {:text prompt}))]
        (cond
          (< n target)
          (recur (inc attempts)
                 (str "Too low — try again. (Attempt " attempts ")"))

          (> n target)
          (recur (inc attempts)
                 (str "Too high — try again. (Attempt " attempts ")"))

          :else
          (do
            (s/await
              (s/embed :demo/info
                       {:text (str "🎉 Got it! "
                                   target " in " attempts
                                   (if (= 1 attempts) " attempt." " attempts."))}))
            ;; The body's final value becomes the flow's :answer.  Since
            ;; this is the root flow, the kernel turns that into [:end …]
            ;; and the conversation closes.
            {:winner true :target target :attempts attempts}))))))

;; ---------------------------------------------------------------------------
;; Wiring
;; ---------------------------------------------------------------------------

(s/mount! "/guess" :demo/guess)

(defn -main [& _args]
  (s/start! {:port 8080})
  ;; Block forever; ^C stops the JVM.
  @(promise))

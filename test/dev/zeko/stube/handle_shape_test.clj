(ns dev.zeko.stube.handle-shape-test
  "The kernel accepts loose handler/lifecycle return shapes via
  [[dev.zeko.stube.lifecycle/coerce-return]]:

      nil          → no-op
      <map>        → new self, no effects
      [<map> <v>]  → canonical pair (existing behaviour)
      <vec>        → effects only, same self

  These tests pin the four cases at the kernel boundary so future
  refactors don't accidentally narrow what handlers are allowed to
  return."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.core         :as s]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.lifecycle    :as lifecycle]
            [dev.zeko.stube.registry     :as registry]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

(defn- boot [flow-id]
  (kernel/run-effects (conv/new-conversation) (kernel/boot flow-id)))

(defn- dispatch-event [c event]
  (kernel/dispatch c {:instance-id (conv/top-id c)
                      :event       event
                      :signals     {}}))

(deftest handler-may-return-bare-map
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [self] [:div {:id (:instance/id self)}
                                   (str (:n self))])
     :component/init   (fn [_] {:n 0})
     :component/handle (fn [self _]
                         ;; Bare map: state changed, no effects.
                         (update self :n inc))})
  (let [[c0]    (boot :t/leaf)
        [c1 _]  (dispatch-event c0 :bump)
        [c2 _]  (dispatch-event c1 :bump)]
    (is (= 2 (:n (conv/top-instance c2))) "bare-map return updates state")))

(deftest handler-may-return-bare-effect-vector
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [self] [:div {:id (:instance/id self)}])
     :component/handle (fn [_self _]
                         ;; Bare effect vector: no self change.
                         [(s/answer :done)])})
  (let [[c0]      (boot :t/leaf)
        [c1 _]    (dispatch-event c0 :go)]
    (is (:conv/ended? c1) ":answer at root terminates the conversation")))

(deftest handler-may-return-nil
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [self] [:div {:id (:instance/id self)}])
     :component/handle (fn [_self _ev] nil)})
  (let [[c0]   (boot :t/leaf)
        [c1 _] (dispatch-event c0 :ignored)]
    (is (not (:conv/ended? c1)) "nil return is a clean no-op")))

(deftest canonical-pair-still-works
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [self] [:div {:id (:instance/id self)}
                                   (str (:n self))])
     :component/init   (fn [_] {:n 0})
     :component/handle (fn [self _]
                         [(update self :n inc) []])})
  (let [[c0]   (boot :t/leaf)
        [c1 _] (dispatch-event c0 :bump)]
    (is (= 1 (:n (conv/top-instance c1))) "existing [self' effects] form unchanged")))

(deftest bare-unwrapped-effect-throws-guiding-error
  ;; `(s/answer :ok)` is the vector `[:answer :ok]`; returning it
  ;; unwrapped (instead of `[(s/answer :ok)]`) used to fold silently
  ;; into two bogus ops. coerce-return — the shared kernel boundary —
  ;; now throws with guidance.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unwrapped effect"
                        (lifecycle/coerce-return {} (s/answer :ok))))
  ;; The valid shapes are unaffected: a wrapped single effect, a
  ;; canonical pair, a bare map, and nil all pass straight through.
  (is (= [{} [[:answer :ok]]] (lifecycle/coerce-return {} [(s/answer :ok)])))
  (is (= [{:n 1} []]          (lifecycle/coerce-return {} {:n 1})))
  (is (= [{} []]              (lifecycle/coerce-return {} nil)))
  ;; And it still works end-to-end through dispatch.
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [self] [:div {:id (:instance/id self)}])
     :component/handle (fn [_self _ev] [(s/answer :ok)])})
  (let [[c0]   (boot :t/leaf)
        [c1 _] (dispatch-event c0 :go)]
    (is (:conv/ended? c1) "wrapped single effect still works")))

(deftest lifecycle-hooks-accept-bare-map-too
  ;; coerce-return is shared between handlers and lifecycle hooks.
  ;; A :start hook returning a bare map should update the instance
  ;; with no effects.
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [self] [:div {:id (:instance/id self)}
                                   (str (:label self))])
     :component/init   (fn [_] {:label "init"})
     :start            (fn [self] (assoc self :label "started"))})
  (let [[c _] (boot :t/leaf)]
    (is (= "started" (:label (conv/top-instance c)))
        "bare-map :start return propagates to the instance")))

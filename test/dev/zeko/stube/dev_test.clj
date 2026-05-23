(ns dev.zeko.stube.dev-test
  "Component-state schema validation (S-9): dev-mode handlers that
  return ill-shaped state throw a clear Malli-explained error; the
  same code path is a no-op when dev mode is off, with zero hard
  dependency on Malli."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.dev          :as dev]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

(defn- run-boot [flow-id]
  (kernel/run-effects (conv/new-conversation) (kernel/boot flow-id)))

(defn- dispatch [c event]
  (kernel/dispatch c {:instance-id (peek (:conv/stack c))
                      :event       event
                      :signals     {}}))

(deftest production-mode-validate-is-a-noop
  ;; *enabled?* defaults to nil ⇒ falls back to system property which is
  ;; unset under `clojure -X:test`, so this asserts the default state.
  (is (false? (dev/enabled?))))

(deftest dev-mode-throws-on-bad-handler-return
  (binding [dev/*enabled?* true]
    (registry/register!
      {:component/id     :t/typed
       :component/state  [:map [:n :int]]
       :component/init   (fn [_] {:n 0})
       :component/render (fn [self] [:div {:id (:instance/id self)} (:n self)])
       :component/handle (fn [self _]
                           ;; Break the schema on purpose: :n becomes a string.
                           (assoc self :n "not-an-int"))})
    (let [[c0 _]    (run-boot :t/typed)
          thrown?   (atom nil)]
      (try
        (dispatch c0 :go)
        (catch clojure.lang.ExceptionInfo e
          (reset! thrown? e)))
      (let [e @thrown?]
        (is (some? e) "handler return that breaks the schema throws")
        (is (re-find #":component/state" (ex-message e))
            "error mentions :component/state")
        (let [data (ex-data e)]
          (is (= :t/typed (:stube.dev/component data)))
          (is (= :handle  (:stube.dev/phase data)))
          (is (some? (:stube.dev/explain data))
              "carries the Malli explainer output"))))))

(deftest dev-mode-accepts-well-shaped-handler-return
  (binding [dev/*enabled?* true]
    (registry/register!
      {:component/id     :t/typed
       :component/state  [:map [:n :int]]
       :component/init   (fn [_] {:n 0})
       :component/render (fn [self] [:div {:id (:instance/id self)} (:n self)])
       :component/handle (fn [self _] (update self :n inc))})
    (let [[c0 _]   (run-boot :t/typed)
          [c1 _]   (dispatch c0 :go)]
      (is (= 1 (:n (conv/instance c1 (peek (:conv/stack c1)))))))))

(deftest validate-ignores-instance-keys
  ;; A schema describing only the user state must not be tripped by
  ;; the :instance/* keys the kernel sticks on every self.
  (binding [dev/*enabled?* true]
    (registry/register!
      {:component/id     :t/typed
       :component/state  [:map [:n :int]]
       :component/init   (fn [_] {:n 0})
       :component/render (fn [self] [:div {:id (:instance/id self)} (:n self)])
       :component/handle (fn [self _]
                           ;; Touch only :n; don't strip :instance/* keys.
                           (update self :n inc))})
    (let [[c0 _]   (run-boot :t/typed)
          [c1 _]   (dispatch c0 :go)]
      (is (= 1 (:n (conv/instance c1 (peek (:conv/stack c1)))))))))

(deftest user-state-helper-strips-the-right-keys
  (let [inst {:instance/id   "ix-1"
              :instance/type :t/x
              :instance/rendered? true
              :instance/children {}
              :stube/context {:db ::stub}
              :n 7
              :draft "hi"}]
    (is (= {:n 7 :draft "hi"} (dev/user-state inst)))))

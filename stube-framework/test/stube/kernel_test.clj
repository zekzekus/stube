(ns stube.kernel-test
  "End-to-end tests of the kernel against hand-built component
  definitions.  Renders go through `stube.render/html`, which needs the
  cid dynamic var bound for any component that calls `(s/on …)`.  None
  of the test components do, so the var stays unbound."
  (:require [clojure.test       :refer [deftest is testing use-fixtures]]
            [stube.conversation :as conv]
            [stube.kernel       :as kernel]
            [stube.registry     :as registry]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

(defn- elements-fragments [frags]
  (filter #(= :elements (:fragment/kind %)) frags))

(defn- run-boot
  "Run the boot effects for `flow-id` against a fresh conversation.
  Returns `[conv fragments]`."
  [flow-id]
  (kernel/run-effects (conv/new-conversation) (kernel/boot flow-id)))

;; ---------------------------------------------------------------------------
;; :call & :answer
;; ---------------------------------------------------------------------------

(deftest call-pushes-and-renders
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [self] [:div {:id (:instance/id self)} "leaf"])})
  (let [[c frags] (run-boot :t/leaf)]
    (is (= 1 (count (:conv/stack c))) "single frame on the stack")
    (is (= 1 (count (elements-fragments frags))))
    (let [{:fragment/keys [html opts]} (first (elements-fragments frags))]
      (is (re-find #"leaf" html))
      (is (= "#root" (:selector opts))   "render slots into #root")
      (is (= :inner   (:patch-mode opts))))
    (testing "every render in slice 0 targets #root with inner mode"
      (let [[_ frag] (#'kernel/render-frame c (conv/top-id c))]
        (is (= "#root" (:selector (:fragment/opts frag))))
        (is (= :inner  (:patch-mode (:fragment/opts frag))))))))

(deftest answer-from-root-ends-conversation
  (registry/register!
    {:component/id   :t/leaf
     :component/handle (fn [self _] [self [[:answer :ok]]])})
  (let [[c0]      (run-boot :t/leaf)
        iid       (conv/top-id c0)
        [c1 frags] (kernel/dispatch c0 {:instance-id iid :event :go :signals {}})]
    (is (true? (:conv/ended? c1)))
    (is (some #(= :close (:fragment/kind %)) frags))))

(deftest call-resume-flows-value-back-to-parent
  (registry/register!
    {:component/id     :t/child
     :component/render (fn [s] [:div {:id (:instance/id s)} "child"])
     :component/handle (fn [s _] [s [[:answer 42]]])})
  (registry/register!
    {:component/id     :t/parent
     :component/init   (constantly {:received nil})
     :component/render (fn [s] [:div {:id (:instance/id s)} "parent"])
     :component/handle (fn [s _] [s [[:call (conv/embed :t/child) :resume :on-child]]])
     :on-child         (fn [s v] [(assoc s :received v) []])})
  (let [[c0]   (run-boot :t/parent)
        parent-iid (conv/top-id c0)
        ;; Trigger parent → :call → child instance pushed.
        [c1 _] (kernel/dispatch c0 {:instance-id parent-iid :event :go :signals {}})
        child-iid (conv/top-id c1)
        ;; Trigger child → :answer 42 → parent receives, child popped.
        [c2 frags] (kernel/dispatch c1 {:instance-id child-iid :event :ack :signals {}})]
    (is (= [parent-iid] (:conv/stack c2)) "child popped, parent on top")
    (is (= 42 (:received (conv/instance c2 parent-iid))))
    (is (seq (elements-fragments frags))
        "parent re-renders after receiving the answer")))

;; ---------------------------------------------------------------------------
;; :start
;; ---------------------------------------------------------------------------

(deftest start-fires-on-instantiation
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [s] [:div {:id (:instance/id s)} "leaf"])
     :component/handle (fn [s _] [s [[:answer :done]]])})
  (registry/register!
    {:component/id :t/task
     :start        (fn [s] [s [[:call (conv/embed :t/leaf) :resume :on-leaf]]])
     :on-leaf      (fn [s _] [s [[:answer :task-done]]])})
  (let [[c0 frags] (run-boot :t/task)]
    (is (= 2 (count (:conv/stack c0))) "task and leaf both on the stack")
    (is (= 1 (count (elements-fragments frags)))
        "only the leaf renders; task placeholder was suppressed")
    (is (re-find #"leaf" (:fragment/html (first (elements-fragments frags)))))))

;; ---------------------------------------------------------------------------
;; State updates without effects → auto re-render
;; ---------------------------------------------------------------------------

(deftest handler-state-update-triggers-rerender
  (registry/register!
    {:component/id     :t/counter
     :component/init   (constantly {:n 0})
     :component/render (fn [s] [:div {:id (:instance/id s)} "n=" (:n s)])
     :component/handle (fn [s _] [(update s :n inc) []])})
  (let [[c0]   (run-boot :t/counter)
        iid    (conv/top-id c0)
        [c1 f] (kernel/dispatch c0 {:instance-id iid :event :tick :signals {}})]
    (is (= 1 (:n (conv/instance c1 iid))))
    (is (re-find #"n=1" (:fragment/html (first (elements-fragments f)))))))

;; ---------------------------------------------------------------------------
;; Signals + :keep
;; ---------------------------------------------------------------------------

(deftest kept-signals-merge-into-self
  (registry/register!
    {:component/id     :t/echo
     :component/init   (constantly {:msg ""})
     :component/keep   #{:msg}
     :component/render (fn [s] [:div {:id (:instance/id s)} (:msg s)])
     :component/handle (fn [s _] [s [[:answer (:msg s)]]])})
  (let [[c0]   (run-boot :t/echo)
        iid    (conv/top-id c0)
        [c1 _] (kernel/dispatch c0 {:instance-id iid
                                    :event       :go
                                    :signals     {:msg "hello"}})]
    (is (true? (:conv/ended? c1))
        "leaf answered, conversation ended")))

;; ---------------------------------------------------------------------------
;; Side effects: :patch, :patch-signals, :execute-script
;; ---------------------------------------------------------------------------

(deftest side-effects-emit-fragments-without-changing-stack
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [s] [:div {:id (:instance/id s)}])
     :component/handle (fn [s _]
                         [s [[:patch [:div#toast "hi"]]
                             [:patch-signals {:loading false}]
                             [:execute-script "console.log('x')"]]])})
  (let [[c0]   (run-boot :t/leaf)
        iid    (conv/top-id c0)
        [c1 f] (kernel/dispatch c0 {:instance-id iid :event :go :signals {}})]
    (is (= [iid] (:conv/stack c1)) "stack unchanged")
    (is (some #(= :elements (:fragment/kind %)) f))
    (is (some #(= :signals  (:fragment/kind %)) f))
    (is (some #(= :script   (:fragment/kind %)) f))))

;; ---------------------------------------------------------------------------
;; History
;; ---------------------------------------------------------------------------

(deftest dispatch-snapshots-prior-conversation
  (registry/register!
    {:component/id     :t/c
     :component/init   (constantly {:n 0})
     :component/render (fn [s] [:div {:id (:instance/id s)}])
     :component/handle (fn [s _] [(update s :n inc) []])})
  (let [[c0]   (run-boot :t/c)
        iid    (conv/top-id c0)
        [c1 _] (kernel/dispatch c0 {:instance-id iid :event :tick :signals {}})
        [c2 _] (kernel/dispatch c1 {:instance-id iid :event :tick :signals {}})]
    (is (= 2 (count (:conv/history c2)))
        "two prior conversations on the history stack")))

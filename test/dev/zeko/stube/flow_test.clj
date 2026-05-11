(ns dev.zeko.stube.flow-test
  "Tests for the slice-1 `defflow` macro and its cloroutine-backed
  runtime.  Every test drives the kernel directly with `dispatch`, so
  there is no HTTP layer involved.

  We register a couple of leaf prompt components by hand to play the
  role of children that real flows would `await` against."
  (:require [clojure.test       :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.core         :as s]
            [dev.zeko.stube.flow         :as flow]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

;; ---------------------------------------------------------------------------
;; Tiny leaf component: answers whatever we tell it to via :signals.
;; ---------------------------------------------------------------------------

(defn- register-echo!
  "Register a leaf component named `id` whose `:handle` immediately
  answers its parent with the value held under `:answer-with` in the
  event signals."
  [id]
  (registry/register!
    {:component/id     id
     :component/keep   #{:answer-with}
     :component/render (fn [self] [:div {:id (:instance/id self)} "echo"])
     :component/handle (fn [self _evt]
                         [self [[:answer (:answer-with self)]]])}))

(defn- run-boot [flow-id]
  (kernel/run-effects (conv/new-conversation) (kernel/boot flow-id)))

;; ---------------------------------------------------------------------------
;; Macro registration shape
;; ---------------------------------------------------------------------------

(deftest defflow-registers-a-component-with-the-expected-shape
  (s/defflow :t/empty []
    :ignored-final)
  (let [cdef (registry/lookup! :t/empty)]
    (is (= :t/empty (:component/id cdef)))
    (is (fn? (:component/init cdef)))
    (is (fn? (:start cdef))                    "flows always provide :start")
    (is (fn? (get cdef flow/resume-key))       "and the single resume hook")))

;; ---------------------------------------------------------------------------
;; A flow with no `await`s collapses to one [:answer …] effect.
;; ---------------------------------------------------------------------------

(deftest empty-flow-answers-immediately-from-start
  (s/defflow :t/no-awaits []
    {:result :no-await})
  (let [[c frags] (run-boot :t/no-awaits)]
    (is (true? (:conv/ended? c))
        "root flow ended via [:answer …] → :end")
    (is (some #(= :close (:fragment/kind %)) frags))))

;; ---------------------------------------------------------------------------
;; Single-await flow.
;; ---------------------------------------------------------------------------

(deftest single-await-yields-then-resumes
  (register-echo! :t/leaf)
  (s/defflow :t/single []
    (let [v (s/await (s/embed :t/leaf))]
      {:got v}))
  (let [[c0]      (run-boot :t/single)
        ;; After boot the flow has yielded its first await; the leaf is
        ;; the top frame on the stack.
        leaf-iid  (conv/top-id c0)
        leaf      (conv/instance c0 leaf-iid)
        _         (is (= :t/leaf (:instance/type leaf)))
        ;; Drive the leaf to answer 99 → flow resumes → final value
        ;; {:got 99} → root flow answers → conversation ends.
        [c1 frags] (kernel/dispatch c0 {:instance-id leaf-iid
                                        :event       :go
                                        :signals     {:answer-with 99}})]
    (is (true? (:conv/ended? c1)))
    (is (some #(= :close (:fragment/kind %)) frags))))

;; ---------------------------------------------------------------------------
;; Multi-step flow threads each answer forward.
;; ---------------------------------------------------------------------------

(deftest multi-step-flow-sequences-children
  (register-echo! :t/leaf)
  (s/defflow :t/three []
    (let [a (s/await (s/embed :t/leaf))
          b (s/await (s/embed :t/leaf))
          c (s/await (s/embed :t/leaf))]
      [a b c]))
  (let [[c0]   (run-boot :t/three)
        ;; After boot, only the *first* leaf is on the stack — that's
        ;; the central guarantee of a coroutine-backed flow: only one
        ;; await is in flight at a time.
        _      (is (= 2 (count (:conv/stack c0)))
                   "flow + first leaf")
        l1     (conv/top-id c0)
        [c1 _] (kernel/dispatch c0 {:instance-id l1 :event :go :signals {:answer-with 1}})
        l2     (conv/top-id c1)
        _      (is (not= l1 l2) "fresh leaf id for second await")
        [c2 _] (kernel/dispatch c1 {:instance-id l2 :event :go :signals {:answer-with 2}})
        l3     (conv/top-id c2)
        [c3 _] (kernel/dispatch c2 {:instance-id l3 :event :go :signals {:answer-with 3}})]
    (is (true? (:conv/ended? c3))
        "root flow's final value triggers :end")))

;; ---------------------------------------------------------------------------
;; Flow as a child: its final value flows up to a parent's resume.
;; ---------------------------------------------------------------------------

(deftest flow-final-value-becomes-parents-answer
  (register-echo! :t/leaf)
  (s/defflow :t/inner []
    (let [v (s/await (s/embed :t/leaf))]
      (* 10 v)))
  (registry/register!
    {:component/id     :t/parent
     :component/init   (constantly {:received nil})
     :component/render (fn [s] [:div {:id (:instance/id s)} "parent"])
     :component/handle (fn [s _] [s [[:call (conv/embed :t/inner) :resume :on-inner]]])
     :on-inner         (fn [s v] [(assoc s :received v) []])})
  (let [[c0]      (run-boot :t/parent)
        parent    (conv/top-id c0)
        ;; Parent → :call inner-flow → flow's :start runs → first await
        ;; → leaf is now on top.
        [c1 _]    (kernel/dispatch c0 {:instance-id parent :event :go :signals {}})
        leaf      (conv/top-id c1)
        _         (is (= :t/leaf (:instance/type (conv/instance c1 leaf))))
        [c2 _]    (kernel/dispatch c1 {:instance-id leaf
                                       :event       :go
                                       :signals     {:answer-with 7}})]
    (is (= [parent] (:conv/stack c2))
        "leaf and inner-flow both popped, parent on top")
    (is (= 70 (:received (conv/instance c2 parent)))
        "parent received the flow's final-value answer")))

;; ---------------------------------------------------------------------------
;; Loop + recur across awaits (the classic guess-game shape).
;; ---------------------------------------------------------------------------

(deftest loop-recur-across-awaits-works
  (register-echo! :t/leaf)
  (s/defflow :t/until-zero []
    (loop [seen []]
      (let [n (s/await (s/embed :t/leaf))]
        (if (zero? n)
          {:trail (conj seen n)}
          (recur (conj seen n))))))
  (let [step (fn [conv answer]
               (kernel/dispatch conv
                 {:instance-id (conv/top-id conv)
                  :event       :go
                  :signals     {:answer-with answer}}))
        [c0]   (run-boot :t/until-zero)
        c1     (first (step c0 1))
        c2     (first (step c1 2))
        c3     (first (step c2 0))]
    (is (true? (:conv/ended? c3))
        "loop terminated via the body's final value → root :end")))

;; ---------------------------------------------------------------------------
;; Bindings destructure the embed args.
;; ---------------------------------------------------------------------------

(deftest defflow-bindings-destructure-args
  (register-echo! :t/leaf)
  (s/defflow :t/with-args [{:keys [factor]}]
    (let [v (s/await (s/embed :t/leaf))]
      (* factor v)))
  (registry/register!
    {:component/id     :t/parent
     :component/init   (constantly {:got nil})
     :component/render (fn [s] [:div {:id (:instance/id s)}])
     :component/handle (fn [s _]
                         [s [[:call (conv/embed :t/with-args {:factor 5})
                              :resume :on-inner]]])
     :on-inner         (fn [s v] [(assoc s :got v) []])})
  (let [[c0]   (run-boot :t/parent)
        parent (conv/top-id c0)
        [c1 _] (kernel/dispatch c0 {:instance-id parent :event :go :signals {}})
        leaf   (conv/top-id c1)
        [c2 _] (kernel/dispatch c1 {:instance-id leaf
                                    :event       :go
                                    :signals     {:answer-with 8}})]
    (is (= 40 (:got (conv/instance c2 parent)))
        "5 (binding) × 8 (await result) = 40")))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(deftest defflow-validates-id-and-bindings
  ;; Errors thrown by a macro at expansion time are wrapped by the
  ;; compiler; we just want to know *something* blew up.
  (testing "id must be a namespaced keyword"
    (is (thrown? Throwable
                 (macroexpand '(dev.zeko.stube.core/defflow :unqualified [] :ok)))))
  (testing "bindings must be a vector with at most one entry"
    (is (thrown? Throwable
                 (macroexpand '(dev.zeko.stube.core/defflow :t/x "not-a-vec" :ok))))
    (is (thrown? Throwable
                 (macroexpand '(dev.zeko.stube.core/defflow :t/x [a b] :ok))))))

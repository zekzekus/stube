(ns stube.back-test
  "Slice-3 tests for the `[:back]` effect.  Walks a conversation
  forward via `dispatch` (which `snapshot`s on each step), then asks
  the kernel to walk it back and asserts the restored value matches
  the prior snapshot bit-for-bit (modulo `:conv/touched`)."
  (:require [clojure.string     :as str]
            [clojure.test       :refer [deftest is testing use-fixtures]]
            [stube.conversation :as conv]
            [stube.kernel       :as kernel]
            [stube.registry     :as registry]
            [stube.render       :as render]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

(defn- elements-fragments [frags]
  (filter #(= :elements (:fragment/kind %)) frags))

(defn- run-boot [flow-id]
  (kernel/run-effects (conv/new-conversation) (kernel/boot flow-id)))

;; ---------------------------------------------------------------------------
;; Walking back through a counter
;; ---------------------------------------------------------------------------

(deftest back-restores-previous-state
  (registry/register!
    {:component/id     :t/counter
     :component/init   (constantly {:n 0})
     :component/render (fn [s] [:div {:id (:instance/id s)} "n=" (:n s)])
     :component/handle (fn [s _] [(update s :n inc) []])})
  (let [[c0]      (run-boot :t/counter)
        iid       (conv/top-id c0)
        click     (fn [c] (first (kernel/dispatch c {:instance-id iid
                                                     :event :tick
                                                     :signals {}})))
        c1        (click c0)        ; n = 1
        c2        (click c1)        ; n = 2
        c3        (click c2)        ; n = 3
        ;; Now walk back twice.
        [c4 fr1]  (kernel/run-effects c3 [[:back]])
        [c5 fr2]  (kernel/run-effects c4 [[:back]])]
    (is (= 3 (:n (conv/instance c3 iid))) "sanity: forward walk worked")

    (testing "first :back restores the n=2 state"
      (is (= 2 (:n (conv/instance c4 iid))))
      (let [{:fragment/keys [html opts]} (first (elements-fragments fr1))]
        (is (str/includes? html "n=2"))
        ;; Restored renders use the shell-replacing strategy.
        (is (= "#root" (:selector opts)))
        (is (= :inner  (:patch-mode opts)))))

    (testing "second :back restores n=1"
      (is (= 1 (:n (conv/instance c5 iid))))
      (let [html (:fragment/html (first (elements-fragments fr2)))]
        (is (str/includes? html "n=1"))))))

;; ---------------------------------------------------------------------------
;; :back on an empty history is a no-op
;; ---------------------------------------------------------------------------

(deftest back-with-no-history-is-noop
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [s] [:div {:id (:instance/id s)}])})
  (let [[c0]    (run-boot :t/leaf)
        [c1 f]  (kernel/run-effects c0 [[:back]])]
    (is (= c0 c1) "conversation is untouched")
    (is (empty? f) "no fragments emitted")))

;; ---------------------------------------------------------------------------
;; :back across a :call/:answer pair
;; ---------------------------------------------------------------------------

(deftest back-across-call-answer
  (registry/register!
    {:component/id     :t/child
     :component/render (fn [s] [:span {:id (:instance/id s)} "child"])
     :component/handle (fn [s _] [s [[:answer :ok]]])})
  (registry/register!
    {:component/id     :t/parent
     :component/init   (constantly {:got nil})
     :component/render (fn [s] [:div {:id (:instance/id s)} "parent"])
     :component/handle (fn [s _]
                         [s [[:call (conv/embed :t/child) :resume :on-c]]])
     :on-c             (fn [s v] [(assoc s :got v) []])})
  (let [[c0]    (run-boot :t/parent)
        parent  (conv/top-id c0)
        ;; Click parent → child pushed.
        [c1 _]  (kernel/dispatch c0 {:instance-id parent :event :go :signals {}})
        child   (conv/top-id c1)
        _       (is (= 2 (count (:conv/stack c1))) "child on top of parent")
        ;; Child answers → parent receives, child popped.
        [c2 _]  (kernel/dispatch c1 {:instance-id child :event :go :signals {}})
        _       (is (= [parent] (:conv/stack c2)))
        _       (is (= :ok (:got (conv/instance c2 parent))))
        ;; Walk back once → child should be visible again.
        [c3 fr] (kernel/run-effects c2 [[:back]])]
    (is (= 2 (count (:conv/stack c3)))
        "stack restored to the call state")
    (is (str/includes? (:fragment/html (first (elements-fragments fr))) "child")
        "the child frame is what re-renders")))

;; ---------------------------------------------------------------------------
;; Regression: a handler that itself returns [:back] must move backward.
;;
;; `dispatch` snapshots the conversation before running a handler so the
;; back button can rewind to the pre-event state.  But if the handler
;; itself emits `[:back]`, that auto-snapshot would BE the entry `:back`
;; pops — leaving the user on the same state.  The kernel skips the
;; snapshot in that case; this test pins that behaviour.
;; ---------------------------------------------------------------------------

(deftest dispatched-back-walks-history
  (registry/register!
    {:component/id     :t/counter
     :component/init   (constantly {:n 0})
     :component/render (fn [s] [:div {:id (:instance/id s)} "n=" (:n s)])
     :component/handle (fn [s {:keys [event]}]
                         (case event
                           :tick [(update s :n inc) []]
                           :back [s [[:back]]]
                           [s []]))})
  (let [[c0]   (run-boot :t/counter)
        iid    (conv/top-id c0)
        [c1 _] (kernel/dispatch c0 {:instance-id iid :event :tick :signals {}})
        [c2 _] (kernel/dispatch c1 {:instance-id iid :event :tick :signals {}})
        ;; Click "back" through dispatch — same path the http layer uses.
        [c3 _] (kernel/dispatch c2 {:instance-id iid :event :back :signals {}})]
    (is (= 1 (:n (conv/instance c3 iid)))
        "dispatched [:back] restores the previous step (n=1), not the current one")))

;; ---------------------------------------------------------------------------
;; :back on a snapshot whose top frame already had children re-renders
;; the whole subtree (parent + inlined children).
;; ---------------------------------------------------------------------------

(deftest back-rerenders-children-too
  (registry/register!
    {:component/id     :t/leaf
     :component/init   (fn [{:keys [tag]}] {:tag tag})
     :component/render (fn [s] [:span {:id (:instance/id s)} (str (:tag s))])})
  (registry/register!
    {:component/id     :t/parent
     :component/init   (constantly {:n 0})
     :children         {:slot/c (conv/embed :t/leaf {:tag "leaf"})}
     :component/render (fn [self]
                         [:div {:id (:instance/id self)}
                          "n=" (:n self) " "
                          (stube.render/render-slot self :slot/c)])
     :component/handle (fn [s _] [(update s :n inc) []])})
  (let [[c0]    (run-boot :t/parent)
        iid     (conv/top-id c0)
        [c1 _]  (kernel/dispatch c0 {:instance-id iid :event :tick :signals {}})
        [_ fr]  (kernel/run-effects c1 [[:back]])
        html    (:fragment/html (first (elements-fragments fr)))]
    (is (str/includes? html "n=0") "restored parent count")
    (is (str/includes? html "leaf") "child still inlined in restored render")))

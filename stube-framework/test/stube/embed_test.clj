(ns stube.embed-test
  "Slice-2 tests: embedded children, slot rendering, the morph-by-id
  patching strategy, and the `decorate` helper.

  All assertions drive the kernel directly — the http layer is not in
  the loop, so any failure here is a logic bug, not a transport one."
  (:require [clojure.string     :as str]
            [clojure.test       :refer [deftest is testing use-fixtures]]
            [stube.conversation :as conv]
            [stube.core         :as s]
            [stube.kernel       :as kernel]
            [stube.registry     :as registry]
            [stube.render       :as render]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

(defn- elements-fragments [frags]
  (filter #(= :elements (:fragment/kind %)) frags))

(defn- run-boot [flow-id]
  (kernel/run-effects (conv/new-conversation) (kernel/boot flow-id)))

;; ---------------------------------------------------------------------------
;; Eager child instantiation
;; ---------------------------------------------------------------------------

(deftest children-are-eagerly-instantiated-on-call
  (registry/register!
    {:component/id     :t/leaf-a
     :component/render (fn [s] [:span {:id (:instance/id s)} "A"])})
  (registry/register!
    {:component/id     :t/leaf-b
     :component/render (fn [s] [:span {:id (:instance/id s)} "B"])})
  (registry/register!
    {:component/id     :t/parent
     :children         {:slot/a (conv/embed :t/leaf-a)
                        :slot/b (conv/embed :t/leaf-b)}
     :component/render (fn [self]
                         [:div {:id (:instance/id self)}
                          [:section.a (s/render-slot self :slot/a)]
                          [:section.b (s/render-slot self :slot/b)]])})
  (let [[c frags] (run-boot :t/parent)
        parent    (conv/top-instance c)
        children  (:instance/children parent)]
    (is (= #{:slot/a :slot/b} (set (keys children)))
        "both slots got fresh child instances")
    (let [child-a (conv/instance c (:slot/a children))
          child-b (conv/instance c (:slot/b children))]
      (is (= :t/leaf-a (:instance/type child-a)))
      (is (= :t/leaf-b (:instance/type child-b)))
      (is (= (:instance/id parent) (:instance/parent child-a))
          "child knows its embedding parent")
      (is (= (:instance/id parent) (:instance/parent child-b))))
    (testing "the parent's first render inlines both children's HTML"
      (let [html (:fragment/html (first (elements-fragments frags)))]
        (is (str/includes? html ">A<"))
        (is (str/includes? html ">B<"))))))

;; ---------------------------------------------------------------------------
;; Children spec as a function of self-state
;; ---------------------------------------------------------------------------

(deftest children-spec-as-function
  (registry/register!
    {:component/id     :t/leaf
     :component/init   (fn [{:keys [tag]}] {:tag tag})
     :component/render (fn [s] [:span {:id (:instance/id s)} (:tag s)])})
  (registry/register!
    {:component/id     :t/parent
     :component/init   (constantly {:tags ["x" "y" "z"]})
     :children         (fn [self]
                         (into {} (map-indexed
                                   (fn [i tag]
                                     [(keyword "slot" (str "n" i))
                                      (conv/embed :t/leaf {:tag tag})])
                                   (:tags self))))
     :component/render (fn [self]
                         [:div {:id (:instance/id self)}
                          (s/render-slot self :slot/n0)
                          (s/render-slot self :slot/n1)
                          (s/render-slot self :slot/n2)])})
  (let [[c frags] (run-boot :t/parent)
        html      (:fragment/html (first (elements-fragments frags)))]
    (is (= 3 (count (:instance/children (conv/top-instance c)))))
    (is (every? #(str/includes? html %) ["x" "y" "z"]))))

;; ---------------------------------------------------------------------------
;; Patching strategy: first render → #root inner; later → morph-by-id
;; ---------------------------------------------------------------------------

(deftest first-render-uses-root-then-switches-to-morph-by-id
  (registry/register!
    {:component/id     :t/counter
     :component/init   (constantly {:n 0})
     :component/render (fn [s] [:div {:id (:instance/id s)} "n=" (:n s)])
     :component/handle (fn [s _] [(update s :n inc) []])})
  (let [[c0 frags0] (run-boot :t/counter)
        opts0       (:fragment/opts (first (elements-fragments frags0)))
        iid         (conv/top-id c0)
        [_  frags1] (kernel/dispatch c0 {:instance-id iid :event :go :signals {}})
        opts1       (:fragment/opts (first (elements-fragments frags1)))]
    (is (= {:selector "#root" :patch-mode :inner} opts0)
        "very first render replaces the shell")
    (is (= {} opts1)
        "every later render lets Datastar morph by id")))

;; ---------------------------------------------------------------------------
;; Embedded child handles its own events and re-renders by id
;; ---------------------------------------------------------------------------

(deftest child-event-rerenders-just-the-child
  (registry/register!
    {:component/id     :t/counter
     :component/init   (constantly {:n 0})
     :component/render (fn [s] [:span {:id (:instance/id s)} "n=" (:n s)])
     :component/handle (fn [s _] [(update s :n inc) []])})
  (registry/register!
    {:component/id     :t/parent
     :children         {:slot/c (conv/embed :t/counter)}
     :component/render (fn [self]
                         [:div {:id (:instance/id self)}
                          "shell "
                          (s/render-slot self :slot/c)])})
  (let [[c0]      (run-boot :t/parent)
        parent    (conv/top-instance c0)
        child-iid (get-in parent [:instance/children :slot/c])
        ;; The parent's first render put the child into the DOM →
        ;; the child should already be marked rendered.
        _         (is (true? (:instance/rendered? (conv/instance c0 child-iid)))
                      "parent's render marks its embedded children as rendered too")
        [c1 fr]   (kernel/dispatch c0 {:instance-id child-iid
                                       :event       :tick
                                       :signals     {}})
        frag      (first (elements-fragments fr))]
    (is (= 1 (:n (conv/instance c1 child-iid))) "child state advanced")
    (is (str/includes? (:fragment/html frag) "n=1")
        "morphed HTML reflects the new state")
    (is (nil? (:selector (:fragment/opts frag)))
        "child re-renders via morph-by-id, not #root inner")))

;; ---------------------------------------------------------------------------
;; Slot-local call/answer
;; ---------------------------------------------------------------------------

(deftest call-in-slot-swaps-one-child-and-resumes-parent
  (registry/register!
    {:component/id     :t/label
     :component/render (fn [s] [:span {:id (:instance/id s)} "label"])})
  (registry/register!
    {:component/id     :t/editor
     :component/render (fn [s] [:form {:id (:instance/id s)} "editor"])
     :component/handle (fn [s _] [s [[:answer "saved"]]])})
  (registry/register!
    {:component/id     :t/parent
     :component/init   (constantly {:received nil})
     :children         {:slot/body (conv/embed :t/label)}
     :component/render (fn [self]
                         [:div {:id (:instance/id self)}
                          [:strong (str "received=" (:received self))]
                          (s/render-slot self :slot/body)])
     :component/handle (fn [s _]
                         [s [[:call-in-slot :slot/body (conv/embed :t/editor)
                              :resume :on-edit]]])
     :on-edit          (fn [s value] [(assoc s :received value) []])})
  (let [[c0]      (run-boot :t/parent)
        parent    (conv/top-id c0)
        old-child (get-in (conv/instance c0 parent)
                          [:instance/children :slot/body])
        [c1 fr1]  (kernel/dispatch c0 {:instance-id parent
                                       :event       :edit
                                       :signals     {}})
        new-child (get-in (conv/instance c1 parent)
                          [:instance/children :slot/body])
        frag1     (first (elements-fragments fr1))]
    (is (= [parent] (:conv/stack c1))
        "slot-local calls do not push a conversation frame")
    (is (not= old-child new-child) "slot now points at the editor")
    (is (= old-child (:instance/previous (conv/instance c1 new-child)))
        "temporary child remembers the prior slot occupant")
    (is (= {:selector (str "#" old-child) :patch-mode :outer}
           (:fragment/opts frag1))
        "the first patch replaces only the old child root")
    (is (str/includes? (:fragment/html frag1) "editor"))
    (is (some? (conv/instance c1 old-child))
        "previous occupant is kept so answering can restore it")

    (let [[c2 fr2] (kernel/dispatch c1 {:instance-id new-child
                                        :event       :save
                                        :signals     {}})
          parent'  (conv/instance c2 parent)
          html2    (:fragment/html (first (elements-fragments fr2)))]
      (is (= [parent] (:conv/stack c2)) "stack is still unchanged")
      (is (= old-child (get-in parent' [:instance/children :slot/body]))
          "answer restores the previous child into the slot")
      (is (= "saved" (:received parent'))
          "parent resume key receives the editor answer")
      (is (nil? (conv/instance c2 new-child))
          "temporary editor is removed after answering")
      (is (str/includes? html2 "received=saved"))
      (is (str/includes? html2 "label")))))

;; ---------------------------------------------------------------------------
;; render-slot validates inputs
;; ---------------------------------------------------------------------------

(deftest render-slot-throws-on-unknown-slot
  (registry/register!
    {:component/id :t/leaf
     :component/render (fn [s] [:span {:id (:instance/id s)} "x"])})
  (registry/register!
    {:component/id :t/parent
     :children     {:slot/known (conv/embed :t/leaf)}
     :component/render (fn [self]
                         [:div {:id (:instance/id self)}
                          (s/render-slot self :slot/missing)])})
  (is (thrown? clojure.lang.ExceptionInfo
               (run-boot :t/parent))))

(deftest render-slot-needs-conv-bound
  (let [self {:instance/id "ix-x" :instance/children {:slot/x "ix-y"}}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (s/render-slot self :slot/x)))))

;; ---------------------------------------------------------------------------
;; pop-top removes embedded descendants too
;; ---------------------------------------------------------------------------

(deftest answer-pops-children-with-the-parent
  (registry/register!
    {:component/id     :t/leaf
     :component/render (fn [s] [:span {:id (:instance/id s)}])})
  (registry/register!
    {:component/id     :t/inner-parent
     :children         {:slot/x (conv/embed :t/leaf)}
     :component/render (fn [self]
                         [:div {:id (:instance/id self)}
                          (s/render-slot self :slot/x)])
     :component/handle (fn [s _] [s [[:answer :ok]]])})
  (registry/register!
    {:component/id     :t/outer
     :component/render (fn [s] [:div {:id (:instance/id s)} "outer"])
     :component/handle (fn [s _]
                         [s [[:call (conv/embed :t/inner-parent) :resume :on-x]]])
     :on-x             (fn [s _] [s []])})
  (let [[c0]    (run-boot :t/outer)
        outer   (conv/top-id c0)
        ;; Push :t/inner-parent (which auto-instantiates a leaf child).
        [c1 _]  (kernel/dispatch c0 {:instance-id outer :event :go :signals {}})
        inner   (conv/top-id c1)
        leaf    (get-in (conv/instance c1 inner) [:instance/children :slot/x])
        ;; Now answer to the outer parent — both inner and its leaf
        ;; child should disappear from the conversation.
        [c2 _]  (kernel/dispatch c1 {:instance-id inner :event :go :signals {}})]
    (is (some? (conv/instance c1 leaf)) "leaf existed before the answer")
    (is (nil? (conv/instance c2 inner)) "inner parent gone")
    (is (nil? (conv/instance c2 leaf))  "embedded leaf gone with it")))

;; ---------------------------------------------------------------------------
;; decorate
;; ---------------------------------------------------------------------------

(deftest decorate-overrides-with-map
  (let [base   {:component/id    :t/base
                :component/init  (constantly {:n 0})
                :component/render (fn [s] [:div "base"])}
        deco   (s/decorate base
                           {:component/id     :t/deco
                            :component/render (fn [s] [:div "decorated"])})]
    (is (= :t/deco (:component/id deco)))
    (is (= [:div "decorated"] ((:component/render deco) {})))
    (is (fn? (:component/init deco)) "non-overridden keys preserved")))

(deftest decorate-overrides-with-fn
  (let [base   {:component/id     :t/base
                :component/render (fn [s] [:p "inner"])}
        deco   (s/decorate base
                 (fn [b]
                   {:component/id     :t/deco
                    :component/render (fn [self]
                                        [:section.banner
                                         [:header "Hi"]
                                         ((:component/render b) self)])}))]
    (is (= [:section.banner [:header "Hi"] [:p "inner"]]
           ((:component/render deco) {})))))

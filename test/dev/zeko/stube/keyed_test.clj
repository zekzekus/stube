(ns dev.zeko.stube.keyed-test
  "Keyed-children diff (S-7): the kernel emits per-child fragments
  instead of re-rendering the parent."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.core         :as s]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- register-counter! []
  (registry/register!
    {:component/id     :t/counter
     :component/init   (fn [{:keys [start]}] {:n (or start 0)})
     :component/render (fn [self] [:div {:id (:instance/id self)} (:n self)])}))

(defn- register-parent-with-initial! [initial]
  (registry/register!
    {:component/id     :t/parent
     :start            (fn [self]
                         [self [(s/set-keyed-children :slot/cols initial)]])
     :component/render (fn [self]
                         [:section {:id (:instance/id self)}
                          (s/keyed-children self :slot/cols)])}))

(defn- boot [flow-id]
  (kernel/run-effects (conv/new-conversation) (kernel/boot flow-id)))

(defn- top-iid [c]
  (peek (:conv/stack c)))

(defn- slot-state [c slot]
  (get-in (conv/instance c (top-iid c)) [:instance/keyed-slots slot]))

(defn- patch-modes [frags]
  (mapv (fn [f] (get-in f [:fragment/opts :patch-mode])) frags))

(defn- dispatch-set [c pairs]
  (kernel/dispatch c {:instance-id (top-iid c)
                      :event       :set
                      :payload     pairs
                      :signals     {}}))

(defn- register-stateful-parent! []
  ;; Parent that lets :handle take new key-embed pairs and reconcile.
  (registry/register!
    {:component/id     :t/parent
     :start            (fn [self]
                         [self [(s/set-keyed-children :slot/cols
                                                      [[:c1 (s/embed :t/counter {:start 1})]
                                                       [:c2 (s/embed :t/counter {:start 2})]])]])
     :component/render (fn [self]
                         [:section {:id (:instance/id self)}
                          (s/keyed-children self :slot/cols)])
     :component/handle (fn [self {:keys [event payload]}]
                         (case event
                           :set [self [(s/set-keyed-children :slot/cols payload)]]
                           :replace-self {:replaced? true}
                           [self []]))}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest initial-render-instantiates-children-and-emits-one-container
  (register-counter!)
  (register-parent-with-initial!
    [[:a (s/embed :t/counter {:start 1})]
     [:b (s/embed :t/counter {:start 2})]])
  (let [[c frags] (boot :t/parent)
        slot      (slot-state c :slot/cols)
        elements  (filter #(= :elements (:fragment/kind %)) frags)]
    (is (= [:a :b] (:order slot)))
    (is (= 2 (count (:children slot))))
    (is (every? (fn [k] (some? (get-in slot [:children k :iid]))) [:a :b])
        "every key has a freshly-minted iid")
    (testing "no per-child diff fragments are emitted on the first render"
      (is (= 1 (count elements))
          "single parent-shaped :elements fragment lands the container in one shot")
      (is (str/includes? (:fragment/html (first elements))
                         (str (top-iid c) "--cols"))
          "it carries the container id"))))

(deftest add-key-after-mount-emits-only-an-append
  (register-counter!)
  (register-stateful-parent!)
  (let [[c0 _] (boot :t/parent)
        [c1 frags]
        (dispatch-set c0
          [[:c1 (s/embed :t/counter {:start 1})]
           [:c2 (s/embed :t/counter {:start 2})]
           [:c3 (s/embed :t/counter {:start 99})]])]
    (testing "only one :elements fragment, with :patch-mode :append"
      (is (= [:append] (patch-modes frags))))
    (testing "container id remains stable; the new child's html is in the patch"
      (let [html (:fragment/html (first frags))
            new-iid (get-in (slot-state c1 :slot/cols) [:children :c3 :iid])]
        (is (str/includes? html new-iid))))
    (testing "previously mounted children keep their iids"
      (is (= (get-in (slot-state c0 :slot/cols) [:children :c1 :iid])
             (get-in (slot-state c1 :slot/cols) [:children :c1 :iid])))
      (is (= (get-in (slot-state c0 :slot/cols) [:children :c2 :iid])
             (get-in (slot-state c1 :slot/cols) [:children :c2 :iid]))))))

(deftest remove-key-emits-only-a-remove
  (register-counter!)
  (register-stateful-parent!)
  (let [[c0 _]    (boot :t/parent)
        gone-iid  (get-in (slot-state c0 :slot/cols) [:children :c1 :iid])
        [c1 frags]
        (dispatch-set c0
          [[:c2 (s/embed :t/counter {:start 2})]])]
    (is (= [:remove] (patch-modes frags)))
    (is (= (str "#" gone-iid)
           (get-in (first frags) [:fragment/opts :selector])))
    (is (nil? (conv/instance c1 gone-iid))
        "removed child is also gone from :conv/instances")
    (is (= [:c2] (:order (slot-state c1 :slot/cols))))))

(deftest changed-args-with-same-key-emits-replace-not-append
  (register-counter!)
  (register-stateful-parent!)
  (let [[c0 _]    (boot :t/parent)
        c1-iid    (get-in (slot-state c0 :slot/cols) [:children :c1 :iid])
        [c1 frags]
        (dispatch-set c0
          [[:c1 (s/embed :t/counter {:start 42})]
           [:c2 (s/embed :t/counter {:start 2})]])]
    (is (= [:outer] (patch-modes frags))
        "same key, new args ⇒ one :outer patch")
    (is (= (str "#" c1-iid)
           (get-in (first frags) [:fragment/opts :selector]))
        "the patch targets the same iid (id is preserved across :init)")
    (is (= c1-iid (get-in (slot-state c1 :slot/cols) [:children :c1 :iid])))
    (is (= 42 (:n (conv/instance c1 c1-iid)))
        ":init ran with the new args")))

(deftest reorder-only-emits-one-container-outer-patch
  (register-counter!)
  (register-stateful-parent!)
  (let [[c0 _] (boot :t/parent)
        [_c1 frags]
        (dispatch-set c0
          [[:c2 (s/embed :t/counter {:start 2})]
           [:c1 (s/embed :t/counter {:start 1})]])]
    (is (= [:outer] (patch-modes frags))
        "reorder with no add/remove/change-args ⇒ one container :outer patch")
    (is (str/includes? (get-in (first frags) [:fragment/opts :selector])
                       "--cols")
        "the patch targets the container, not any individual child")))

(deftest add-then-remove-yields-a-clean-diff-trace
  (register-counter!)
  (register-stateful-parent!)
  (let [[c0 _]    (boot :t/parent)
        [c1 add-frags]
        (dispatch-set c0
          [[:c1 (s/embed :t/counter {:start 1})]
           [:c2 (s/embed :t/counter {:start 2})]
           [:c3 (s/embed :t/counter {:start 3})]])
        [_c2 rm-frags]
        (dispatch-set c1
          [[:c1 (s/embed :t/counter {:start 1})]
           [:c2 (s/embed :t/counter {:start 2})]])]
    (is (= [:append] (patch-modes add-frags)))
    (is (= [:remove] (patch-modes rm-frags)))))

(deftest keyed-slot-state-is-framework-metadata
  (register-counter!)
  (register-stateful-parent!)
  (let [[c0 _] (boot :t/parent)
        parent (top-iid c0)
        [c1 _] (kernel/dispatch c0 {:instance-id parent
                                    :event       :replace-self
                                    :signals     {}})]
    (is (some? (:instance/keyed-slots (conv/instance c1 parent)))
        "handlers returning fresh maps must not accidentally drop keyed children")))

(deftest keyed-children-receive-conversation-context
  (registry/register!
    {:component/id     :t/context-child
     :component/render (fn [self]
                         [:div {:id (:instance/id self)}
                          (:label (s/context self))])})
  (registry/register!
    {:component/id     :t/context-parent
     :start            (fn [self]
                         [self [(s/set-keyed-children
                                  :slot/ctx
                                  [[:a (s/embed :t/context-child)]])]])
     :component/render (fn [self]
                         [:section {:id (:instance/id self)}
                          (s/keyed-children self :slot/ctx)])})
  (let [[c frags] (kernel/run-effects
                    (assoc (conv/new-conversation)
                           :conv/context {:label "from-context"})
                    (kernel/boot :t/context-parent))
        child-iid (get-in (slot-state c :slot/ctx) [:children :a :iid])]
    (is (= {:label "from-context"}
           (s/context (conv/instance c child-iid))))
    (is (str/includes? (:fragment/html (first frags)) "from-context"))))

;; ---------------------------------------------------------------------------
;; :rerender-parent? option
;; ---------------------------------------------------------------------------

(defn- register-parent-with-topbar! []
  ;; A parent whose hiccup outside the keyed-slot depends on the
  ;; just-reconciled state (a topbar that counts the open columns).
  ;; Without :rerender-parent?, the topbar text would stay stale
  ;; because the kernel skips the parent's auto-render when the
  ;; reconcile already emitted :elements fragments.
  (registry/register!
    {:component/id     :t/topbar-parent
     :start            (fn [self]
                         [self [(s/set-keyed-children :slot/cols
                                                      [[:c1 (s/embed :t/counter {:start 1})]
                                                       [:c2 (s/embed :t/counter {:start 2})]])]])
     :component/render (fn [self]
                         (let [slot (get-in self [:instance/keyed-slots :slot/cols])
                               n    (count (:order slot))]
                           [:section {:id (:instance/id self)}
                            [:header {:class "topbar"} (str n " open")]
                            (s/keyed-children self :slot/cols)]))
     :component/handle (fn [self {:keys [event payload]}]
                         (case event
                           :set       [self [(s/set-keyed-children :slot/cols payload)]]
                           :set+redraw [self [(s/set-keyed-children :slot/cols payload
                                                                    {:rerender-parent? true})]]
                           [self []]))}))

(deftest set-keyed-children-rerender-parent-emits-extra-parent-fragment
  (register-counter!)
  (register-parent-with-topbar!)
  (let [[c0 _]   (boot :t/topbar-parent)
        new-pairs [[:c1 (s/embed :t/counter {:start 1})]
                   [:c2 (s/embed :t/counter {:start 2})]
                   [:c3 (s/embed :t/counter {:start 3})]]
        [_c1 default-frags]
        (kernel/dispatch c0 {:instance-id (top-iid c0)
                             :event       :set
                             :payload     new-pairs
                             :signals     {}})
        [c2 redraw-frags]
        (kernel/dispatch c0 {:instance-id (top-iid c0)
                             :event       :set+redraw
                             :payload     new-pairs
                             :signals     {}})
        elements (filter #(= :elements (:fragment/kind %)) redraw-frags)
        parent-iid (top-iid c2)]
    (testing "default (no opts) keeps the current behaviour — only the per-child diff"
      (is (= [:append] (patch-modes default-frags))))
    (testing ":rerender-parent? true adds a fragment for the parent itself"
      (is (= 2 (count elements))
          "one for the keyed-children append, one for the parent re-render")
      (is (some (fn [f] (= parent-iid (-> (re-find #"id=\"([^\"]+)\""
                                                   (:fragment/html f))
                                          second)))
                elements)
          "the second fragment carries the parent's id")
      (is (some (fn [f] (str/includes? (:fragment/html f) "3 open"))
                elements)
          "the parent's topbar reflects the reconciled state, not the stale count"))))

(deftest set-keyed-children-rerender-parent-noop-on-first-render
  (register-counter!)
  ;; First render path: :start emits the reconcile before the parent has
  ;; ever painted, so the kernel's normal render-frame on the way out
  ;; picks up the populated state in one shot.  The option must not
  ;; double-render.
  (registry/register!
    {:component/id     :t/first-render-parent
     :start            (fn [self]
                         [self [(s/set-keyed-children :slot/cols
                                                      [[:c1 (s/embed :t/counter {:start 1})]]
                                                      {:rerender-parent? true})]])
     :component/render (fn [self]
                         [:section {:id (:instance/id self)}
                          (s/keyed-children self :slot/cols)])})
  (let [[_c frags] (boot :t/first-render-parent)
        elements   (filter #(= :elements (:fragment/kind %)) frags)]
    (is (= 1 (count elements))
        "first render still produces one fragment, not two")))

(deftest changed-keyed-embed-rebuilds-subtree-and-preserves-root-iid
  (registry/register!
    {:component/id     :t/leaf-a
     :component/render (fn [self] [:em {:id (:instance/id self)} "a"])})
  (registry/register!
    {:component/id     :t/leaf-b
     :component/render (fn [self] [:strong {:id (:instance/id self)} "b"])})
  (registry/register!
    {:component/id     :t/wrapper
     :component/init   (fn [{:keys [kind]}] {:kind kind})
     :children         (fn [self]
                         {:slot/leaf (s/embed (case (:kind self)
                                                :a :t/leaf-a
                                                :b :t/leaf-b))})
     :component/render (fn [self]
                         [:div {:id (:instance/id self)}
                          (s/render-slot self :slot/leaf)])})
  (registry/register!
    {:component/id     :t/rebuild-parent
     :start            (fn [self]
                         [self [(s/set-keyed-children
                                  :slot/items
                                  [[:same (s/embed :t/wrapper {:kind :a})]])]])
     :component/render (fn [self]
                         [:section {:id (:instance/id self)}
                          (s/keyed-children self :slot/items)])
     :component/handle (fn [self _]
                         [self [(s/set-keyed-children
                                  :slot/items
                                  [[:same (s/embed :t/wrapper {:kind :b})]])]])})
  (let [[c0 _]     (boot :t/rebuild-parent)
        old-iid    (get-in (slot-state c0 :slot/items) [:children :same :iid])
        old-leaf   (get-in (conv/instance c0 old-iid) [:instance/children :slot/leaf])
        [c1 frags] (kernel/dispatch c0 {:instance-id (top-iid c0)
                                        :event       :change
                                        :signals     {}})
        new-iid    (get-in (slot-state c1 :slot/items) [:children :same :iid])
        new-leaf   (get-in (conv/instance c1 new-iid) [:instance/children :slot/leaf])]
    (is (= old-iid new-iid) "stable key preserves the child root iid")
    (is (not= old-leaf new-leaf) "descendants are rebuilt from the new embed")
    (is (nil? (conv/instance c1 old-leaf)) "old descendants are removed")
    (is (= :t/leaf-b (:instance/type (conv/instance c1 new-leaf))))
    (is (= [:outer] (patch-modes frags)))))

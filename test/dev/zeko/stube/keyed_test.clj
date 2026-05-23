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

(ns dev.zeko.stube.halos-test
  "Slice-1 / slice-2 coverage for the dev halos overlay."
  (:require [clojure.string              :as str]
            [clojure.test                :refer [deftest is use-fixtures]]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.core         :as s]
            [dev.zeko.stube.halos        :as halos]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render]))

;; ---------------------------------------------------------------------------
;; Test fixtures and components
;; ---------------------------------------------------------------------------
;;
;; Other test namespaces use `:each` fixtures that wipe the registry,
;; so we re-register the local components inside each test run.

(defn- register-components! []
  (s/defcomponent :halos-test/leaf
    :render (fn [self] [:div {:id (:instance/id self)} "leaf"]))
  (s/defcomponent :halos-test/branch
    :children {:slot/a (s/embed :halos-test/leaf)}
    :render   (fn [self]
                [:section {:id (:instance/id self)}
                 (s/render-slot self :slot/a)])))

(use-fixtures :each
  (fn [t]
    (registry/clear!)
    (register-components!)
    (t)
    (registry/clear!)))

(def ^:private fake-inst
  {:instance/id "ix-abc" :instance/type :demo/foo})

;; ---------------------------------------------------------------------------
;; decorate-root
;; ---------------------------------------------------------------------------

(deftest decorate-root-merges-into-existing-attrs
  (let [out (halos/decorate-root [:div {:id "ix-abc" :class "foo"} [:span "hi"]] fake-inst)]
    (is (vector? out))
    (is (= :div (first out)))
    (let [attrs (second out)]
      (is (= "ix-abc"     (:data-stube-iid attrs)))
      (is (= ":demo/foo"  (:data-stube-type attrs)))
      (is (= "foo"        (:class attrs)))
      (is (= "ix-abc"     (:id attrs))))))

(deftest decorate-root-injects-attrs-when-missing
  (let [out (halos/decorate-root [:section [:p "no attrs"]] fake-inst)]
    (is (= :section (first out)))
    (is (= {:data-stube-iid  "ix-abc"
            :data-stube-type ":demo/foo"}
           (second out)))))

(deftest decorate-root-leaves-non-element-shapes-alone
  (is (= "hi" (halos/decorate-root "hi"  fake-inst)))
  (is (= nil  (halos/decorate-root nil   fake-inst)))
  (is (= []   (halos/decorate-root []    fake-inst))))

;; ---------------------------------------------------------------------------
;; tree-data
;; ---------------------------------------------------------------------------

(deftest tree-data-mirrors-stack-and-slots
  (binding [render/*cid* "cv-halos-test"]
    (let [[conv _] (kernel/run-effects (conv/new-conversation)
                                       (kernel/boot :halos-test/branch))
          tree     (halos/tree-data conv)]
      (is (= 1 (count tree)) "one root on the stack")
      (let [root (first tree)]
        (is (= :halos-test/branch (:type root)))
        (is (= [:slot/a] (mapv :slot (:slots root))))
        (is (= :halos-test/leaf
               (-> root :slots first :child :type)))))))

;; ---------------------------------------------------------------------------
;; defcomponent captures source meta
;; ---------------------------------------------------------------------------

(deftest where-resolves-via-defcomponent-meta
  (let [src (halos/where :halos-test/branch)]
    (is (map? src))
    (is (string? (:file src)))
    (is (integer? (:line src)))
    (is (str/includes? (:file src) "halos_test"))))

;; ---------------------------------------------------------------------------
;; Kernel renders halo data-attrs only when `:conv/halos?` is set
;; ---------------------------------------------------------------------------

(deftest render-injects-halo-attrs-only-when-flag-on
  (binding [render/*cid* "cv-halos-render"]
    (let [c0       (conv/new-conversation)
          [c1 fr]  (kernel/run-effects c0 (kernel/boot :halos-test/branch))
          html-off (-> fr first :fragment/html)]
      (is (not (str/includes? html-off "data-stube-iid"))
          "halos off → no overlay attrs")
      (let [c1'      (assoc c1 :conv/halos? true)
            iid      (conv/top-id c1')
            [c2 fr2] (kernel/dispatch c1' {:instance-id iid
                                           :event :no-such-thing
                                           :signals {}})
            html-on  (-> fr2 first :fragment/html)]
        (is (str/includes? html-on "data-stube-iid")
            "halos on → outer attr present")
        (is (str/includes? html-on ":halos-test/branch")
            "halos on → outer attr names the component type")
        (let [inst (conv/instance c2 iid)]
          (is (string? (:instance/last-html inst))
              "halos on → kernel caches last-html on the instance"))))))

;; ---------------------------------------------------------------------------
;; panel-hiccup
;; ---------------------------------------------------------------------------

(deftest panel-hiccup-defaults-and-tabs
  (binding [render/*cid* "cv-halos-panel"]
    (let [[conv _] (kernel/run-effects (conv/new-conversation)
                                       (kernel/boot :halos-test/branch))
          html-of  (fn [opts] (render/html (halos/panel-hiccup conv opts)))]
      (is (str/includes? (html-of {}) "Tree"))
      (is (str/includes? (html-of {}) "data-halo-iid")
          "tree tab links every node by iid for the client overlay")
      (is (str/includes? (html-of {:tab :instance}) ":halos-test/branch"))
      (is (str/includes? (html-of {:tab :history}) "history"))
      (is (str/includes? (html-of {:tab :html}) "No render captured")))))

(deftest redraw-top-renders-without-snapshotting
  (binding [render/*cid* "cv-halos-redraw"]
    (let [[c0 _]  (kernel/run-effects (conv/new-conversation)
                                      (kernel/boot :halos-test/branch))
          [c1 fs] (kernel/redraw-top (assoc c0 :conv/halos? true))]
      (is (= 1 (count fs)))
      (is (str/includes? (-> fs first :fragment/html) "data-stube-iid"))
      (is (= (count (:conv/history c0)) (count (:conv/history c1)))
          "redraw-top must not push a history snapshot"))))

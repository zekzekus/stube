(ns dev.zeko.stube.errors-test
  "In-page error frames for throwing :render and :handle."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.errors       :as errors]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

(defn- run-boot [flow-id]
  (kernel/run-effects (conv/new-conversation) (kernel/boot flow-id)))

(defn- error-fragments [frags]
  (filter #(= :error (:fragment/kind %)) frags))

;; ---------------------------------------------------------------------------
;; :handle catch
;; ---------------------------------------------------------------------------

(deftest throwing-handle-yields-error-fragment-and-leaves-conv-unchanged
  (registry/register!
    {:component/id     :t/boom-handle
     :component/init   (fn [_] {:n 0})
     :component/render (fn [self] [:div {:id (:instance/id self)} (:n self)])
     :component/handle (fn [_ _] (throw (ex-info "kaboom" {:cause :test})))})
  (let [[c0]      (run-boot :t/boom-handle)
        iid       (conv/top-id c0)
        [c1 frags] (kernel/dispatch c0 {:instance-id iid
                                        :event       :go
                                        :signals     {}})]
    (testing "conversation is structurally unchanged after a throwing handler"
      (is (= c0 c1)))
    (testing "the produced fragment is tagged :error and targets the instance"
      (let [errs (error-fragments frags)
            {:fragment/keys [html opts]} (first errs)]
        (is (= 1 (count errs)))
        (is (= (str "#" iid) (:selector opts)))
        (is (= :outer (:patch-mode opts)))
        (is (str/includes? html "stube-error"))
        (is (str/includes? html "kaboom"))
        (testing "patched fragment carries cid/iid/phase in an HTML comment"
          (is (str/includes? html (str "iid=" iid)))
          (is (str/includes? html "phase=handle"))
          (is (str/includes? html (str "cid=" (:conv/id c0)))))))))

(deftest on-error-hook-receives-conv-and-throwable
  (registry/register!
    {:component/id     :t/boom-handle
     :component/render (fn [self] [:div {:id (:instance/id self)}])
     :component/handle (fn [_ _] (throw (RuntimeException. "boom")))})
  (let [seen (atom nil)
        [c0] (run-boot :t/boom-handle)
        iid  (conv/top-id c0)]
    (binding [errors/*on-error*
              (fn [conv ^Throwable t]
                (reset! seen {:conv conv
                              :throwable t
                              :iid (:stube.error/iid (ex-data t))
                              :phase (:stube.error/phase (ex-data t))})
                nil)]
      (kernel/dispatch c0 {:instance-id iid :event :go :signals {}}))
    (let [hit @seen]
      (is (= c0 (:conv hit)))
      (is (instance? Throwable (:throwable hit)))
      (is (= iid (:iid hit)))
      (is (= :handle (:phase hit))))))

(deftest on-error-hook-can-return-custom-fragment
  (registry/register!
    {:component/id     :t/boom-handle
     :component/render (fn [self] [:div {:id (:instance/id self)}])
     :component/handle (fn [_ _] (throw (ex-info "nope" {})))})
  (let [custom {:fragment/kind :error
                :fragment/html "<aside id=\"custom-error\">custom</aside>"
                :fragment/opts {:selector "#custom-error" :patch-mode :outer}}
        [c0] (run-boot :t/boom-handle)
        iid  (conv/top-id c0)
        [_ frags]
        (binding [errors/*on-error* (fn [_ _] custom)]
          (kernel/dispatch c0 {:instance-id iid :event :go :signals {}}))]
    (is (= custom (first (error-fragments frags))))))

;; ---------------------------------------------------------------------------
;; :render catch
;; ---------------------------------------------------------------------------

(deftest throwing-render-yields-error-fragment-on-first-render
  (registry/register!
    {:component/id     :t/boom-render
     :component/render (fn [_] (throw (ex-info "render-bad" {})))})
  (let [[_ frags] (run-boot :t/boom-render)
        errs      (error-fragments frags)]
    (is (= 1 (count errs)))
    (is (str/includes? (:fragment/html (first errs)) "render-bad"))
    (is (= {:selector "#root" :patch-mode :inner}
           (:fragment/opts (first errs)))
        "first-render errors patch the shell root because #iid is not in the DOM yet")
    (testing "no normal elements fragment slipped through"
      (is (not-any? #(= :elements (:fragment/kind %)) frags)))))

(ns dev.zeko.stube.registry-test
  (:require [clojure.test   :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.core     :as s]
            [dev.zeko.stube.registry :as registry]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

(deftest registers-and-looks-up
  (let [cdef {:component/id     :test/c
              :component/init   (constantly {})
              :component/render (constantly [:div])
              :component/handle (fn [s _] [s []])}]
    (registry/register! cdef)
    (is (= cdef (registry/lookup :test/c)))
    (is (= cdef (registry/lookup! :test/c)))))

(deftest replaces-existing-on-redefine
  (registry/register! {:component/id :test/c :version 1})
  (registry/register! {:component/id :test/c :version 2})
  (is (= 2 (:version (registry/lookup :test/c)))))

(deftest help-returns-component-docstring
  (registry/register! {:component/id :test/c
                       :component/doc "Useful component."})
  (is (= "Useful component." (registry/help :test/c)))
  (is (nil? (registry/help :test/missing))))

(deftest defcomponent-registers-docstring
  (s/defcomponent :test/doc
    :doc "Defined through dev.zeko.stube.core."
    :render (fn [self] [:div {:id (:instance/id self)}]))
  (is (= "Defined through dev.zeko.stube.core." (s/help :test/doc))))

(deftest rejects-bad-shapes
  (testing "missing :component/id"
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/register! {:component/render (constantly [:div])}))))
  (testing "non-namespaced :component/id"
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/register! {:component/id :foo}))))
  (testing "non-map"
    (is (thrown? clojure.lang.ExceptionInfo
                 (registry/register! "not a map")))))

(deftest lookup!-throws-on-unknown
  (is (thrown? clojure.lang.ExceptionInfo
               (registry/lookup! :nope/missing))))

(deftest rejects-colocated-collisions
  (testing ":render + :component/render in one cdef raises ex-info"
    (let [cdef {:component/id     :test/colliding
                :render           (constantly [:div])
                :component/render (constantly [:span])}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":render and :component/render"
            (registry/register! cdef)))))
  (testing "lifecycle keys generally — sampling a spread"
    (doseq [k [:init :handle :keep :start :url]]
      (let [lifted (keyword "component" (name k))
            cdef   {:component/id :test/colliding-k
                    k             :a
                    lifted        :b}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (registry/register! cdef))
            (str "collision between " k " and " lifted)))))
  (testing "resume keys pass through; no collision"
    ;; :on-foo and :on-error-foo aren't lifted — both forms are legal
    ;; and live alongside each other on the same cdef.
    (is (some? (registry/register!
                 {:component/id   :test/resume-ok
                  :on-foo         (constantly nil)
                  :on-error-foo   (constantly nil)})))))

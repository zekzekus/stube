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

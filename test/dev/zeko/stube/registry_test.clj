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

;; ---------------------------------------------------------------------------
;; S-12: :emit-on-mount lifts to :component/start
;; ---------------------------------------------------------------------------

(deftest emit-on-mount-lifts-to-start
  (let [start-fn (fn [self] [self []])
        cdef     (registry/register!
                   {:component/id :test/eom
                    :emit-on-mount start-fn})]
    (is (= start-fn (:component/start cdef))
        ":emit-on-mount is lifted to :component/start")
    (is (not (contains? cdef :emit-on-mount))
        "original :emit-on-mount key is dropped")
    (is (not (contains? cdef :component/emit-on-mount))
        ":emit-on-mount is not also lifted to its own :component/* slot")))

(deftest emit-on-mount-conflicts-with-explicit-start
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #":emit-on-mount and :start"
        (registry/register!
          {:component/id :test/conflict
           :start         (fn [s] [s []])
           :emit-on-mount (fn [s] [s []])}))
      "Declaring both :emit-on-mount and :start throws at register-time")
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #":emit-on-mount and :start"
        (registry/register!
          {:component/id    :test/conflict-2
           :component/start (fn [s] [s []])
           :emit-on-mount   (fn [s] [s []])}))
      "Collision detection covers the already-lifted :component/start too"))

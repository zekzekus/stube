(ns dev.zeko.stube.conversation-test
  (:require [clojure.test       :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render]))

(use-fixtures :each (fn [t]
                      (registry/clear!)
                      (registry/register!
                        {:component/id   :test/c
                         :component/init (fn [{:keys [x] :or {x 0}}] {:x x})
                         :component/keep #{:y}})
                      (t)
                      (registry/clear!)))

(deftest embed-spec
  (is (conv/embed? (conv/embed :test/c)))
  (is (conv/embed? (conv/embed :test/c {:x 1})))
  (is (= :test/c (-> (conv/embed :test/c) :embed/type)))
  (is (= {:x 1} (-> (conv/embed :test/c {:x 1}) :embed/args))))

(deftest instantiate-bakes-in-state-and-meta
  (let [cdef (registry/lookup :test/c)
        inst (conv/instantiate cdef (conv/embed :test/c {:x 7}) "ix-parent" :on-foo)]
    (is (= "ix-parent" (:instance/parent inst)))
    (is (= :on-foo     (:instance/resume inst)))
    (is (= :test/c     (:instance/type inst)))
    (is (false?        (:instance/rendered? inst)))
    (is (= 7           (:x inst)))
    (is (string?       (:instance/id inst)))))

(deftest stack-push-and-pop
  (let [cdef (registry/lookup :test/c)
        c0   (conv/new-conversation)
        i1   (conv/instantiate cdef (conv/embed :test/c) nil nil)
        i2   (conv/instantiate cdef (conv/embed :test/c) (:instance/id i1) :on-x)
        c1   (-> c0 (conv/push-instance i1) (conv/push-instance i2))
        [c2 popped] (conv/pop-top c1)]
    (is (= [(:instance/id i1) (:instance/id i2)] (:conv/stack c1)))
    (is (= (:instance/id i2) popped))
    (is (= [(:instance/id i1)] (:conv/stack c2)))
    (is (nil? (conv/instance c2 popped)) "popped instance is forgotten")))

(deftest snapshot-preserves-prior-state-without-quadratic-history
  (let [c0 (conv/new-conversation)
        c1 (-> c0 (assoc :marker 1) conv/snapshot (assoc :marker 2))
        c2 (-> c1 conv/snapshot (assoc :marker 3))]
    (is (= 1 (:marker (first (:conv/history c1)))))
    (is (= 2 (:marker (last  (:conv/history c2)))))
    (testing "snapshots themselves carry no history"
      (is (every? #(= [] (:conv/history %)) (:conv/history c2))))))

(deftest preserve-meta-protects-instance-keys
  (let [cdef (registry/lookup :test/c)
        old  (conv/instantiate cdef (conv/embed :test/c) nil nil)
        new  {:x 99 :instance/parent "evil" :instance/resume :evil}]
    (is (= (select-keys old conv/instance-meta-keys)
           (select-keys (conv/preserve-meta old new) conv/instance-meta-keys))
        "metadata wins over user-supplied values")
    (is (= 99 (:x (conv/preserve-meta old new))))))

(deftest merge-kept-signals
  (is (= {:a 1} (conv/merge-kept-signals {:a 1} {:b 2} #{}))
      "with no kept keys, signals are ignored")
  (is (= {:a 1 :b 2}
         (conv/merge-kept-signals {:a 1} {:b 2 :c 3} #{:b})))
  (is (= {:a 9}
         (conv/merge-kept-signals {:a 1} {:a 9} #{:a}))
      "kept signals overwrite existing keys")
  (let [inst {:instance/id "ix-1" :answer "old"}]
    (is (= :answer-ix-1 (conv/local-signal inst :answer)))
    (is (= {:instance/id "ix-1" :answer "local"}
           (conv/merge-kept-signals inst
                                    {:answer "global"
                                     :answer-ix-1 "local"}
                                    #{:answer}))
        "local signal keys map back to their logical kept key and win")))

(deftest merge-kept-signals-honours-camel-signal-case
  (testing "under :signal-case :camel, local-bound signals arrive camelized"
    (binding [render/*signal-case* :camel]
      (let [inst {:instance/id "ix-1" :edit-title "old"}]
        ;; Datastar stores the local-wire key as `editTitleIx-1` — its
        ;; camel transform keeps the dash before the instance-id digit.
        ;; The lookup must use the same form or the kept signal silently
        ;; fails to round-trip (kasten edit-form "title comes back empty").
        (is (= {:instance/id "ix-1" :edit-title "new"}
               (conv/merge-kept-signals inst
                                        {:editTitleIx-1 "new"}
                                        #{:edit-title}))
            "browser sends camelCased local-wire key; lifts back to the logical kept key"))))
  (testing "under camel, the global wire key is also camel"
    (binding [render/*signal-case* :camel]
      (is (= {:edit-title "v"}
             (conv/merge-kept-signals {} {:editTitle "v"} #{:edit-title})))))
  (testing "camel mode still accepts kebab payloads (per-call :case :kebab opt path)"
    (binding [render/*signal-case* :camel]
      (let [inst {:instance/id "ix-1"}]
        (is (= {:instance/id "ix-1" :edit-title "kebab-local"}
               (conv/merge-kept-signals inst
                                        {:edit-title-ix-1 "kebab-local"}
                                        #{:edit-title}))))))
  (testing "kebab mode is unchanged"
    (binding [render/*signal-case* :kebab]
      (is (= {:instance/id "ix-1" :answer "local"}
             (conv/merge-kept-signals {:instance/id "ix-1"}
                                      {:answer-ix-1 "local"}
                                      #{:answer}))))))

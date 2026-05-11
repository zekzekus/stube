(ns dev.zeko.stube.ui-test
  (:require [clojure.test       :refer [deftest is use-fixtures]]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.core         :as s]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

(deftest core-helpers-refresh-stock-ui-components
  (is (= :ui/confirm (:embed/type (s/confirm "Ready?"))))
  (is (some? (registry/lookup :ui/confirm)))
  (is (= :ui/prompt (:embed/type (s/prompt "Name" "Ada"))))
  (is (= :ui/choose (:embed/type (s/choose ["red" "blue"] "Pick"))))
  (is (= :ui/info (:embed/type (s/info "Done")))))

(deftest stock-prompt-answers-local-bound-value
  (binding [render/*cid* "test-cid"]
    (let [[c0] (kernel/run-effects (conv/new-conversation)
                                   [[:call (s/prompt "Name" "Ada")]])
          iid  (conv/top-id c0)
          self (conv/instance c0 iid)
          sig  (s/local-signal self :value)
          [c1 _] (kernel/dispatch c0 {:instance-id iid
                                      :event       :submit
                                      :signals     {sig "Grace"}})]
      (is (true? (:conv/ended? c1))))))

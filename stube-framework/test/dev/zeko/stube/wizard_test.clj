(ns dev.zeko.stube.wizard-test
  "Slice-3 regression: the wizard example's child→parent ::back pattern.

  The wizard uses per-iid Datastar signals, so each new step instance
  has a fresh signal name the browser hasn't seen.  When the parent
  re-`:call`s a previous step, it must pre-fill the input via a server
  side `:initial` value rendered as `value=\"…\"` so Datastar's bind
  initialiser can seed the new signal from the input.

  This test pins:

    1. typed values survive a round-trip through `::back` and re-entry,
    2. each re-rendered step has the prior value baked into its `value`
       attribute,
    3. the summary at the end of a back-then-forward walk shows the
       correct name and colour."
  (:require [clojure.string     :as str]
            [clojure.test       :refer [deftest is testing use-fixtures]]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render]))

(use-fixtures :each
  (fn [t]
    (registry/clear!)
    ;; Loading the example registers :demo/wizard, :demo/wizard-step,
    ;; :demo/wizard-summary into the now-empty registry.
    (require 'dev.zeko.stube.examples.wizard :reload)
    (t)
    (registry/clear!)))

(defn- elements [frags]
  (filter #(= :elements (:fragment/kind %)) frags))

(defn- last-html [frags]
  (:fragment/html (last (elements frags))))

(defn- answer-sig [iid]
  (keyword (str "answer-" iid)))

(defn- submit [conv iid value]
  (kernel/dispatch conv {:instance-id iid
                         :event       :submit
                         :signals     {(answer-sig iid) value}}))

(defn- back-click [conv iid]
  (kernel/dispatch conv {:instance-id iid
                         :event       :back-click
                         :signals     {}}))

(deftest wizard-back-then-forward-preserves-values
  (binding [render/*cid* "test-cid"]
    (let [[c1]            (kernel/run-effects (conv/new-conversation)
                                              (kernel/boot :demo/wizard))
          step1           (conv/top-id c1)
          [c2]            (submit c1 step1 "Alice")
          step2           (conv/top-id c2)
          [c3]            (submit c2 step2 "blue")
          summary         (conv/top-id c3)]

      (testing "summary on the happy path shows typed values"
        (let [inst (conv/instance c3 summary)]
          (is (= "Alice" (:name inst)))
          (is (= "blue"  (:colour inst)))))

      (testing "back from summary pre-fills the colour step"
        (let [[c4 fr] (back-click c3 summary)
              new-iid (conv/top-id c4)
              inst    (conv/instance c4 new-iid)
              html    (last-html fr)]
          (is (= "blue" (:initial inst))
              "wizard remembered the previously-typed colour")
          (is (str/includes? html "value=\"blue\"")
              "input renders with the prior value so Datastar can seed the new signal")
          (is (str/includes? html (str "data-bind:answer-" new-iid))
              "new step uses a fresh per-iid signal name")

          (testing "second back pre-fills the name step"
            (let [[c5 fr2] (back-click c4 new-iid)
                  name-iid (conv/top-id c5)
                  html2    (last-html fr2)]
              (is (= "Alice" (:initial (conv/instance c5 name-iid))))
              (is (str/includes? html2 "value=\"Alice\""))

              (testing "forward again carries values back into the summary"
                (let [[c6]      (submit c5 name-iid "Alice")
                      colour    (conv/top-id c6)
                      [c7]      (submit c6 colour "blue")
                      sum       (conv/top-id c7)
                      sum-inst  (conv/instance c7 sum)]
                  (is (= "Alice" (:name   sum-inst)))
                  (is (= "blue"  (:colour sum-inst)))))

              (testing "submitting with a missing signal falls back to :initial"
                ;; Even if the browser sent no signal at all (Datastar
                ;; failed to initialise from value=…), the handler's
                ;; fallback to `:initial` keeps the value alive.
                (let [[c6']   (kernel/dispatch c5
                                               {:instance-id name-iid
                                                :event       :submit
                                                :signals     {}})
                      wid     (first (:conv/stack c6'))
                      colour' (conv/top-id c6')]
                  (is (= "Alice" (:name (conv/instance c6' wid)))
                      "wizard stored the name from the :initial fallback")
                  (is (= "Favourite colour?"
                         (:label (conv/instance c6' colour')))
                      "advanced to the colour step"))))))))))

(deftest wizard-state-survives-multiple-backs
  (binding [render/*cid* "test-cid"]
    (let [[c1]    (kernel/run-effects (conv/new-conversation)
                                      (kernel/boot :demo/wizard))
          wid     (first (:conv/stack c1))
          step1   (conv/top-id c1)
          [c2]    (submit c1 step1 "Ada")
          step2   (conv/top-id c2)
          [c3]    (submit c2 step2 "rebeccapurple")
          summary (conv/top-id c3)
          [c4]    (back-click c3 summary)
          colour' (conv/top-id c4)
          [c5]    (back-click c4 colour')]
      (is (= "Ada"           (:name   (conv/instance c5 wid))))
      (is (= "rebeccapurple" (:colour (conv/instance c5 wid)))
          "wizard task state is preserved across two ::back walks"))))

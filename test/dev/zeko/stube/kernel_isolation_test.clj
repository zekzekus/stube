(ns dev.zeko.stube.kernel-isolation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [dev.zeko.stube.adapter.ring :as ring-adapter]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.core :as s]
            [dev.zeko.stube.embed :as embed]
            [dev.zeko.stube.kernel :as kernel]
            [dev.zeko.stube.registry :as registry]))

(use-fixtures :each (fn [t] (registry/clear!) (t) (registry/clear!)))

(defn- eventually [pred]
  (let [deadline (+ (System/currentTimeMillis) 1000)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 10) (recur))
        :else false))))

(defn- install-test-component! []
  (registry/register!
    {:component/id :isolation/counter
     :component/init (constantly {:n 0})
     :component/render (fn [self]
                         [:div (s/root-attrs self)
                          "ctx=" (:name (s/context self)) " n=" (:n self)])
     :component/handle (fn [self {:keys [event]}]
                         (case event
                           :inc (update self :n inc)
                           self))}))

(defn- boot-live! [k cid]
  (let [root (embed/pending-root k cid)]
    (embed/apply-conv! k cid
      (fn [c] (kernel/run-effects c (kernel/boot root))))))

(deftest make-kernel-instances-do-not-share-live-state
  (install-test-component!)
  (let [k1   (embed/make-kernel {:base-path "/one"
                                 :context-fn (constantly {:name "one"})})
        k2   (embed/make-kernel {:base-path "/two"
                                 :context-fn (constantly {:name "two"})})
        cid1 (embed/mint-conversation! k1 :isolation/counter {} {})
        cid2 (embed/mint-conversation! k2 :isolation/counter {} {})]
    (boot-live! k1 cid1)
    (boot-live! k2 cid2)
    (let [iid1  (conv/top-id (embed/conversation k1 cid1))
          _iid2 (conv/top-id (embed/conversation k2 cid2))]
      (embed/dispatch! k1 cid1 {:instance-id iid1 :event :inc :signals {}})
      (is (= 1 (:n (conv/top-instance (embed/conversation k1 cid1)))))
      (is (= 0 (:n (conv/top-instance (embed/conversation k2 cid2)))))
      (is (= "one" (:name (s/context (conv/top-instance (embed/conversation k1 cid1))))))
      (is (= "two" (:name (s/context (conv/top-instance (embed/conversation k2 cid2))))))
      (is (not (contains? (embed/active-conversations k1) cid2)))
      (is (not (contains? (embed/active-conversations k2) cid1)))
      (is (str/includes? (pr-str (embed/shell-for k1 cid1))
                         (str "/one/sse/" cid1)))
      (is (str/includes? (pr-str (embed/shell-for k2 cid2))
                         (str "/two/sse/" cid2)))
      (is (str/includes? (pr-str (embed/head-tags k1))
                         "/one/stube/preserve.js"))
      (is (str/includes? (pr-str (embed/head-tags k2))
                         "/two/stube/preserve.js")))))

(deftest core-publish-routes-to-active-embedded-kernel
  (registry/register!
    {:component/id :isolation/pubsub
     :component/init (constantly {:seen nil})
     :component/render (fn [self]
                         [:div (s/root-attrs self) (pr-str (:seen self))])
     :start (fn [self]
              [self [(s/subscribe :isolation/topic :published)]])
     :component/handle (fn [self {:keys [event payload]}]
                         (case event
                           :publish (do (s/publish! :isolation/topic
                                                     (:name (s/context self)))
                                        self)
                           :published (assoc self :seen payload)
                           self))})
  (let [k1   (embed/make-kernel {:context-fn (constantly {:name "one"})})
        k2   (embed/make-kernel {:context-fn (constantly {:name "two"})})
        cid1 (embed/mint-conversation! k1 :isolation/pubsub {} {})
        cid2 (embed/mint-conversation! k2 :isolation/pubsub {} {})]
    (boot-live! k1 cid1)
    (boot-live! k2 cid2)
    (let [iid1 (conv/top-id (embed/conversation k1 cid1))]
      (embed/dispatch! k1 cid1 {:instance-id iid1
                                :event       :publish
                                :signals     {}})
      (is (eventually #(= "one" (:seen (conv/top-instance
                                         (embed/conversation k1 cid1))))))
      (is (nil? (:seen (conv/top-instance (embed/conversation k2 cid2))))))))

(deftest replay-works-against-kernel-without-starting-server
  (install-test-component!)
  (let [k (embed/make-kernel {:context-fn (constantly {:name "replay"})})
        [c _frags] (embed/replay-with k :isolation/counter [{:event :inc}
                                                            {:event :inc}])]
    (is (= 2 (:n (conv/top-instance c))))
    (is (= "replay" (:name (s/context (conv/top-instance c)))))
    (is (empty? (embed/active-conversations k)))))

(deftest ring-routes-use-kernel-base-path
  (let [k      (embed/make-kernel {:base-path "/widget"})
        paths  (set (map first (ring-adapter/ring-routes k)))]
    (is (contains? paths "/widget/sse/:cid"))
    (is (contains? paths "/widget/event/:cid/:iid/:event"))
    (is (contains? paths "/widget/upload/:cid/:iid"))
    (is (contains? paths "/widget/stube/ui.css"))
    (is (contains? paths "/widget/stube/preserve.js"))))

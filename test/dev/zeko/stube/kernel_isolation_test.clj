(ns dev.zeko.stube.kernel-isolation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dev.onionpancakes.chassis.core :as chassis]
            [dev.zeko.stube.adapter.ring :as ring-adapter]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.core :as s]
            [dev.zeko.stube.embed :as embed]
            [dev.zeko.stube.runtime :as rt]
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
  (let [root (rt/pending-root k cid)]
    (rt/apply-conv! k cid
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
    (let [iid1  (conv/top-id (rt/conversation k1 cid1))
          _iid2 (conv/top-id (rt/conversation k2 cid2))]
      (embed/dispatch! k1 cid1 {:instance-id iid1 :event :inc :signals {}})
      (is (= 1 (:n (conv/top-instance (rt/conversation k1 cid1)))))
      (is (= 0 (:n (conv/top-instance (rt/conversation k2 cid2)))))
      (is (= "one" (:name (s/context (conv/top-instance (rt/conversation k1 cid1))))))
      (is (= "two" (:name (s/context (conv/top-instance (rt/conversation k2 cid2))))))
      (is (not (contains? (rt/active-conversations k1) cid2)))
      (is (not (contains? (rt/active-conversations k2) cid1)))
      (is (str/includes? (pr-str (embed/shell-for k1 cid1))
                         (str "/one/sse/" cid1)))
      (is (str/includes? (pr-str (embed/shell-for k2 cid2))
                         (str "/two/sse/" cid2)))
      (is (str/includes? (pr-str (embed/head-tags k1))
                         "/one/preserve.js"))
      (is (str/includes? (pr-str (embed/head-tags k2))
                         "/two/preserve.js")))))

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
    (let [iid1 (conv/top-id (rt/conversation k1 cid1))]
      (embed/dispatch! k1 cid1 {:instance-id iid1
                                :event       :publish
                                :signals     {}})
      (is (eventually #(= "one" (:seen (conv/top-instance
                                         (rt/conversation k1 cid1))))))
      (is (nil? (:seen (conv/top-instance (rt/conversation k2 cid2))))))))

(deftest replay-works-against-kernel-without-starting-server
  (install-test-component!)
  (let [k (embed/make-kernel {:context-fn (constantly {:name "replay"})})
        [c _frags] (embed/replay-with k :isolation/counter [{:event :inc}
                                                            {:event :inc}])]
    (is (= 2 (:n (conv/top-instance c))))
    (is (= "replay" (:name (s/context (conv/top-instance c)))))
    (is (empty? (rt/active-conversations k)))))

(deftest app-and-principal-are-visible-to-component-code
  (let [seen     (atom [])
        login-fn (fn [request] (some-> request :query-string keyword))]
    (registry/register!
      {:component/id     :isolation/app-principal
       :component/init   (constantly {})
       :component/render (fn [self]
                           [:div (s/root-attrs self)
                            (pr-str {:app (s/app) :principal (s/principal)})])
       :component/handle (fn [self _]
                           (swap! seen conj {:app (s/app) :principal (s/principal)})
                           self)})
    (let [k    (embed/make-kernel {:app          {:db ::stub-db}
                                   :principal-fn login-fn})
          cid  (embed/mint-conversation! k :isolation/app-principal {}
                                         {:query-string "ada"})]
      (let [root (rt/pending-root k cid)]
        (rt/apply-conv! k cid
          (fn [c] (kernel/run-effects c (kernel/boot root)))))
      (let [iid (conv/top-id (rt/conversation k cid))]
        (embed/dispatch! k cid {:instance-id iid :event :ping :signals {}}))
      (is (= [{:app {:db ::stub-db} :principal :ada}] @seen)
          "handler sees both :app and :principal bound by the runtime")
      (is (= :ada (:conv/principal (rt/conversation k cid)))
          ":principal-fn result is persisted on the conversation"))))

(deftest principal-defaults-to-nil-without-principal-fn
  (registry/register!
    {:component/id     :isolation/no-principal
     :component/render (fn [self] [:div (s/root-attrs self)])})
  (let [k   (embed/make-kernel)
        cid (embed/mint-conversation! k :isolation/no-principal {} {})]
    (is (nil? (:conv/principal (rt/conversation k cid))))))

(deftest conversation-id-is-visible-to-component-code
  (let [seen (atom [])]
    (registry/register!
      {:component/id     :isolation/cid-aware
       :component/init   (constantly {})
       :component/render (fn [self]
                           (swap! seen conj [:render (s/conversation-id)])
                           [:div (s/root-attrs self)])
       :component/handle (fn [self _]
                           (swap! seen conj [:handle (s/conversation-id)])
                           self)})
    (let [k   (embed/make-kernel)
          cid (embed/mint-conversation! k :isolation/cid-aware {} {})]
      (rt/apply-conv! k cid
        (fn [c] (kernel/run-effects c (kernel/boot (rt/pending-root k cid)))))
      (let [iid (conv/top-id (rt/conversation k cid))]
        (embed/dispatch! k cid {:instance-id iid :event :ping :signals {}}))
      (is (every? (fn [[_ id]] (= cid id)) @seen)
          ":render and :handle both see the active cid")
      (is (= 2 (count (filter (fn [[k _]] (= k :render)) @seen)))
          "render fires for boot and for the dispatch redraw")
      (is (= 1 (count (filter (fn [[k _]] (= k :handle)) @seen)))
          "handle fires once for the dispatched event")))
  (is (nil? (s/conversation-id))
      "returns nil outside a runtime binding"))

(deftest rendered-shell-for-runs-boot-server-side
  (registry/register!
    {:component/id     :isolation/static
     :component/init   (constantly {:greeting "hello server"})
     :component/render (fn [self]
                         [:section (s/root-attrs self)
                          [:h1 (:greeting self)]])})
  (let [k    (embed/make-kernel)
        {:keys [cid shell]} (embed/rendered-shell-for! k :isolation/static {})
        rendered-html (chassis/html shell)
        conv (rt/conversation k cid)
        iid  (conv/top-id conv)]
    (is (str/includes? rendered-html "hello server")
        "the rendered first paint contains the component's HTML")
    (is (str/includes? rendered-html "<div id=\"root\">")
        "the SSE morph target is still in place")
    (is (str/includes? rendered-html "data-init")
        "the data-init that opens the SSE stream is preserved")
    (is (true? (:conv/server-rendered? conv))
        "the flag is set so the SSE handler can skip the first re-render")
    (is (true? (get-in conv [:conv/instances iid :instance/rendered?]))
        "the root instance is marked rendered")
    (testing "host can still dispatch events afterwards — the conv is fully alive"
      (registry/register!
        {:component/id     :isolation/static
         :component/init   (constantly {:greeting "hello server"})
         :component/render (fn [self] [:div (s/root-attrs self) (:greeting self)])
         :component/handle (fn [self _] (assoc self :greeting "after dispatch"))})
      (embed/dispatch! k cid {:instance-id iid :event :ping :signals {}})
      (is (= "after dispatch"
             (:greeting (get-in (rt/conversation k cid)
                                [:conv/instances iid])))))))

(deftest signal-case-kernel-default-binds-render-helpers
  (let [k-kebab (embed/make-kernel)
        k-camel (embed/make-kernel {:signal-case :camel})]
    (is (= :kebab (:signal-case k-kebab))
        "default is :kebab so existing hosts keep their wire shape")
    (is (= :camel (:signal-case k-camel)))
    (rt/with-kernel-bindings k-camel "cv-x"
      (fn []
        (is (= {(keyword "data-bind:edit-markdown") true}
               (s/bind :edit-markdown))
            "bind picks the kernel default casing when no per-call opt is given")
        (is (= "$editMarkdown" (s/$ :edit-markdown))
            "$ ref uses the same kernel default")
        (is (= "v" (s/signal {:signals {:editMarkdown "v"}} :edit-markdown))
            "signal lookup translates the keyword under the kernel default")))
    (rt/with-kernel-bindings k-kebab "cv-x"
      (fn []
        (is (= {(keyword "data-bind:edit-markdown__case.kebab") true}
               (s/bind :edit-markdown)))))))

(deftest ring-routes-use-kernel-base-path
  (let [k      (embed/make-kernel {:base-path "/widget"})
        paths  (set (map first (ring-adapter/ring-routes k)))]
    (is (contains? paths "/widget/sse/:cid"))
    (is (contains? paths "/widget/event/:cid/:iid/:event"))
    (is (contains? paths "/widget/upload/:cid/:iid"))
    (is (contains? paths "/widget/ui.css"))
    (is (contains? paths "/widget/preserve.js"))
    (is (contains? paths "/widget/behaviors.js"))
    (is (contains? paths "/widget/styles/*asset"))
    (is (contains? paths "/widget/modules/*asset"))
    (is (contains? paths "/widget/behaviors/*asset"))
    (is (contains? paths "/widget/halos.js"))
    (is (contains? paths "/widget/halos/:cid/panel"))
    (is (contains? paths "/widget/halos/:cid/enable"))))

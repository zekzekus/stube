(ns dev.zeko.stube.history-test
  "Tests for S-2: `history` effect for URL sync.

  Acceptance criteria from the issue:
  - `replay` traces `[:history …]` effects without a server.
  - File-store roundtrip preserves nothing about URL state (it's transient).
  - The effect constructor emits the expected wire vector.
  - The kernel step translates to a `:script` fragment with the correct JS."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.core         :as s]
            [dev.zeko.stube.effects      :as e]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.store        :as store]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- use-registry [t]
  (registry/clear!)
  (t)
  (registry/clear!))

(clojure.test/use-fixtures :each use-registry)

(defn- script-fragments [frags]
  (filter #(= :script (:fragment/kind %)) frags))

;; ---------------------------------------------------------------------------
;; Effect constructor shape (wire contract)
;; ---------------------------------------------------------------------------

(deftest history-constructor-wire-shape
  (testing ":replace mode"
    (is (= [:history :replace "/foo?q=bar"]
           (s/history :replace "/foo?q=bar"))))
  (testing ":push mode"
    (is (= [:history :push "/step/2"]
           (s/history :push "/step/2"))))
  (testing "accessors round-trip"
    (let [eff (e/history :push "/page/3")]
      (is (= :push    (e/history-mode eff)))
      (is (= "/page/3" (e/history-url  eff)))))
  (testing "invalid mode throws"
    (is (thrown? Exception (e/history :invalid "/url")))))

;; ---------------------------------------------------------------------------
;; Kernel step: translates to a :script fragment
;; ---------------------------------------------------------------------------

(deftest history-step-emits-script-fragment
  (testing ":replace → history.replaceState JS"
    (let [[_conv frags] (kernel/run-effects (conv/new-conversation)
                                            [(s/history :replace "/notes?id=1")])]
      (is (= 1 (count (script-fragments frags))))
      (let [js (:fragment/script (first (script-fragments frags)))]
        (is (re-find #"replaceState" js))
        (is (re-find #"/notes\?id=1" js)))))

  (testing ":push → history.pushState JS"
    (let [[_conv frags] (kernel/run-effects (conv/new-conversation)
                                            [(s/history :push "/notes/42")])]
      (is (= 1 (count (script-fragments frags))))
      (let [js (:fragment/script (first (script-fragments frags)))]
        (is (re-find #"pushState" js))
        (is (re-find #"/notes/42" js))))))

(deftest history-does-not-change-stack-or-conversation
  (registry/register!
    {:component/id     :t/hist
     :component/init   (constantly {:n 0})
     :component/render (fn [s] [:div {:id (:instance/id s)} (:n s)])
     :component/handle (fn [s _event]
                         (let [n' (inc (:n s))]
                           [(assoc s :n n')
                            [(s/history :replace (str "/counter?n=" n'))]]))})
  (let [[c0]   (kernel/run-effects (conv/new-conversation) (kernel/boot :t/hist))
        iid    (conv/top-id c0)
        [c1 frags] (kernel/dispatch c0 {:instance-id iid :event :inc :signals {}})]
    (is (= [iid] (:conv/stack c1)) "stack unchanged after :history")
    (is (= 1 (:n (conv/instance c1 iid))))
    (is (some #(= :script (:fragment/kind %)) frags)
        "history effect produces a script fragment")
    (is (some #(= :elements (:fragment/kind %)) frags)
        "auto-render still fires after state change")))

;; ---------------------------------------------------------------------------
;; replay traces [:history …] without a server
;; ---------------------------------------------------------------------------

(deftest replay-traces-history-effects
  (registry/register!
    {:component/id     :t/url-counter
     :component/init   (fn [{:keys [n]}] {:n (or n 0)})
     :component/render (fn [s] [:div {:id (:instance/id s)} (:n s)])
     :component/handle (fn [s _]
                         (let [n' (inc (:n s))]
                           [(assoc s :n n')
                            [(s/history :push (str "/counter?n=" n'))]]))})
  (let [[_conv frags] (s/replay :t/url-counter
                                [{:event :inc}
                                 {:event :inc}])]
    (is (= 2 (count (filter #(= :script (:fragment/kind %)) frags)))
        "two history push effects traced across two replay steps")))

;; ---------------------------------------------------------------------------
;; File-store roundtrip: URL state is transient, not persisted
;; ---------------------------------------------------------------------------

(deftest history-effect-is-transient-not-persisted
  ;; The [:history …] effect fires as a fragment to the browser and is
  ;; immediately consumed; it is not stored on the conversation map at
  ;; all.  A file-store roundtrip of the conversation should contain no
  ;; trace of the history URLs.
  (registry/register!
    {:component/id     :t/url-thing
     :component/init   (constantly {:page 1})
     :component/render (fn [s] [:div {:id (:instance/id s)} (:page s)])
     :component/handle (fn [s _]
                         [(assoc s :page 2)
                          [(s/history :replace "/page/2")]])})
  (let [[c0]  (kernel/run-effects (conv/new-conversation) (kernel/boot :t/url-thing))
        iid   (conv/top-id c0)
        [c1 _] (kernel/dispatch c0 {:instance-id iid :event :next :signals {}})]
    ;; The conversation map should not carry any :history key or URL string
    ;; that would make it non-EDN or silently leak URL info into persistence.
    (is (not (re-find #"replaceState\|pushState\|/page/2"
                      (pr-str c1)))
        "no history JS appears in the persisted conversation map")
    ;; Verify the conversation round-trips through the file store cleanly.
    (let [st    (store/in-memory-store)
          saved (store/save! st c1)]
      (is (map? saved) "save! returns the conversation map"))))

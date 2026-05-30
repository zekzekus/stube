(ns dev.zeko.stube.effects-test
  "The named effect constructors must emit exactly the wire vectors the
  kernel has always understood — otherwise existing literal-vector code
  diverges from constructor-based code at the byte level."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.zeko.stube.core         :as s]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.effects      :as e]))

(deftest call-builds-expected-wire-vector
  (testing "no args, no resume"
    (is (= [:call (conv/embed :ui/prompt) :resume nil]
           (s/call :ui/prompt))))
  (testing "resume only (keyword 2nd arg)"
    (is (= [:call (conv/embed :ui/prompt) :resume :on-pick]
           (s/call :ui/prompt :on-pick))))
  (testing "args only (map 2nd arg)"
    (is (= [:call (conv/embed :ui/prompt {:text "Hi"}) :resume nil]
           (s/call :ui/prompt {:text "Hi"}))))
  (testing "args + resume"
    (is (= [:call (conv/embed :ui/prompt {:text "Hi"}) :resume :on-pick]
           (s/call :ui/prompt {:text "Hi"} :on-pick))))
  (testing "existing embed spec"
    (is (= [:call (conv/embed :ui/prompt {:label "Name"}) :resume :on-pick]
           (s/call (s/embed :ui/prompt {:label "Name"}) :on-pick)))))

(deftest become-builds-replace-effect
  (is (= [:replace (conv/embed :wizard/step-2)]
         (s/become :wizard/step-2)))
  (is (= [:replace (conv/embed :wizard/step-2 {:from :a})]
         (s/become :wizard/step-2 {:from :a})))
  (is (= [:replace (conv/embed :wizard/step-2 {:from :a})]
         (s/become (s/embed :wizard/step-2 {:from :a})))))

(deftest call-in-slot-builds-expected-wire-vector
  (is (= [:call-in-slot :slot/main (conv/embed :feature/edit) :resume nil]
         (s/call-in-slot :slot/main :feature/edit)))
  (is (= [:call-in-slot :slot/main (conv/embed :feature/edit) :resume :on-saved]
         (s/call-in-slot :slot/main :feature/edit :on-saved)))
  (is (= [:call-in-slot :slot/main (conv/embed :feature/edit {:id 7}) :resume nil]
         (s/call-in-slot :slot/main :feature/edit {:id 7})))
  (is (= [:call-in-slot :slot/main (conv/embed :feature/edit {:id 7}) :resume :on-saved]
         (s/call-in-slot :slot/main :feature/edit {:id 7} :on-saved)))
  (is (= [:call-in-slot :slot/main (conv/embed :feature/edit {:id 7}) :resume :on-saved]
         (s/call-in-slot :slot/main (s/embed :feature/edit {:id 7}) :on-saved))))

(deftest leaf-effect-ctors-match-literal-vectors
  (is (= [:answer 42]         (s/answer 42)))
  (is (= [:patch [:p "hi"]]   (s/patch [:p "hi"])))
  (is (= [:patch-signals {:x 1}] (s/patch-signals {:x 1})))
  (is (= [:execute-script "alert(1)"] (s/execute-script "alert(1)")))
  (is (= [:end {:done true}]  (s/end {:done true})))
  (is (= [:back] (s/back)))
  (is (= [:after 1000 :tick] (s/after 1000 :tick)))
  (is (= [:subscribe :topic :msg] (s/subscribe :topic :msg)))
  (is (= [:unsubscribe] (s/unsubscribe)))
  (is (= [:unsubscribe :topic] (s/unsubscribe :topic))))

(deftest io-wraps-thunk
  (let [thunk (fn [] :nope)
        eff   (s/io thunk)]
    (is (= :io (first eff)))
    (is (identical? thunk (e/io-thunk eff)))))

;; ---------------------------------------------------------------------------
;; S-14: answer-error
;; ---------------------------------------------------------------------------

(deftest answer-error-wire-shape
  (let [ex  (ex-info "boom" {:why :test})
        eff (s/answer-error ex)]
    (is (= [:answer-error ex] eff))
    (is (identical? ex (e/answer-error-value eff)))))

;; ---------------------------------------------------------------------------
;; dispatch-to
;; ---------------------------------------------------------------------------

(deftest dispatch-to-builds-expected-wire-vector
  (testing "accepts an iid string"
    (is (= [:dispatch-to "ix-007" :open]
           (s/dispatch-to "ix-007" :open))))
  (testing "accepts an instance map and pulls :instance/id from it"
    (is (= [:dispatch-to "ix-007" [:open 42]]
           (s/dispatch-to {:instance/id "ix-007"} [:open 42]))))
  (testing "rejects bogus targets with a clear message"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"instance map or an instance-id string"
                          (s/dispatch-to :not-a-target :open))))
  (testing "accessors return iid and route-event"
    (let [eff (s/dispatch-to "ix-007" [:open :a])]
      (is (= "ix-007" (e/dispatch-to-iid eff)))
      (is (= [:open :a] (e/dispatch-to-event eff))))))

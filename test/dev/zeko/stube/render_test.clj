(ns dev.zeko.stube.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.zeko.stube.render :as render]))

(deftest html-renders-hiccup
  (is (= "<div id=\"x\">hi</div>" (render/html [:div {:id "x"} "hi"]))))

(deftest on-builds-data-on-attribute-with-event-routing
  (binding [render/*cid* "cv-001"]
    (let [{:strs [] :as attrs}
          (render/on {:instance/id "ix-9"} :submit)
          [k v] (first attrs)]
      (is (= 1 (count attrs)))
      (is (= (keyword "data-on:submit") k))
      (is (re-find #"@post\('/event/cv-001/ix-9/submit'\)" v)))))

(deftest on-encodes-structured-event-payload
  (binding [render/*cid* "cv-001"]
    (let [attrs (render/on {:instance/id "ix-9"} :click :as [:pick-day 12])
          [k v] (first attrs)]
      (is (= (keyword "data-on:click") k))
      (is (re-find #"/event/cv-001/ix-9/pick-day\?_stube_payload=12" v)))))

(deftest on-target-routes-to-explicit-instance
  (binding [render/*cid* "cv-001"]
    (let [attrs (render/on-target "ix-parent" :click :as [:open 42])
          [k v] (first attrs)]
      (is (= (keyword "data-on:click") k))
      (is (re-find #"/event/cv-001/ix-parent/open\?_stube_payload=42" v)))))

(deftest on-target-accepts-instance-map-or-iid-string
  (binding [render/*cid* "cv-001"]
    (let [via-map  (render/on-target {:instance/id "ix-parent"} :click :as :open)
          via-iid  (render/on-target "ix-parent" :click :as :open)]
      (is (= via-map via-iid)
          "passing self or its iid string yields the same wire attrs"))))

(deftest on-parent-routes-to-parent-instance
  (binding [render/*cid* "cv-001"]
    (let [self  {:instance/id "ix-child" :instance/parent "ix-parent"}
          attrs (render/on-parent self :click :as :close)
          [k v] (first attrs)]
      (is (= (keyword "data-on:click") k))
      (is (re-find #"/event/cv-001/ix-parent/close" v)
          "on-parent posts to :instance/parent, not :instance/id"))
    (is (thrown? clojure.lang.ExceptionInfo
                 (render/on-parent {:instance/id "ix-orphan"} :click :as :close))
        "missing :instance/parent throws — there is no implicit fallback")
    (let [self {:instance/id "ix-child" :instance/parent "ix-parent"}]
      (is (= (render/on-target "ix-parent" :click :as :close)
             (render/on-parent self :click :as :close))
          "on-parent is a thin wrapper around on-target"))))

(deftest instance-id-helper
  (is (= "ix-7" (render/instance-id {:instance/id "ix-7"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (render/instance-id {}))
      "throws with a clear message rather than handing back nil"))

(deftest on-accepts-event-modifiers
  (binding [render/*cid* "cv-001"]
    (testing "valued modifier produces __key.value suffix on the attribute name"
      (let [attrs (render/on {:instance/id "ix"} :input :as :search {:debounce "300ms"})
            [k _v] (first attrs)]
        (is (= (keyword "data-on:input__debounce.300ms") k))))
    (testing "flag-only modifiers emit __key with no value"
      (let [attrs (render/on {:instance/id "ix"} :click :as :open
                             {:prevent true :stop true})
            [k _v] (first attrs)]
        (is (= (keyword "data-on:click__prevent__stop") k)
            "map-style modifiers are sorted by key name for deterministic output")))
    (testing "false / nil modifier values are skipped"
      (let [attrs (render/on {:instance/id "ix"} :input :as :search
                             {:debounce "300ms" :passive false :capture nil})
            [k _v] (first attrs)]
        (is (= (keyword "data-on:input__debounce.300ms") k))))
    (testing "vector form preserves caller order"
      (let [attrs (render/on {:instance/id "ix"} :click :as :open
                             [[:stop true] [:prevent true]])
            [k _v] (first attrs)]
        (is (= (keyword "data-on:click__stop__prevent") k))))))

(deftest on-target-accepts-event-modifiers
  (binding [render/*cid* "cv-001"]
    (let [attrs (render/on-target "ix-p" :input :as :search {:debounce "200ms"})
          [k _v] (first attrs)]
      (is (= (keyword "data-on:input__debounce.200ms") k)))))

(deftest on-without-cid-throws
  (binding [render/*cid* nil]
    (is (thrown? clojure.lang.ExceptionInfo
                 (render/on {:instance/id "ix-9"} :submit)))))

(deftest on-without-instance-throws
  (binding [render/*cid* "cv-1"]
    (is (thrown? clojure.lang.ExceptionInfo
                 (render/on {} :submit)))))

(deftest root-attrs-merges-id-with-other-attrs
  (let [self {:instance/id "ix-42"}]
    (is (= {:id "ix-42"} (render/root-attrs self))
        "bare call yields just the id")
    (is (= {:id "ix-42" :class "card"} (render/root-attrs self {:class "card"}))
        "single attr map merges with id")
    (is (= {:id "ix-42" :class "card" :data-on:submit "x"}
           (render/root-attrs self {:class "card"} {:data-on:submit "x"}))
        "variadic: every attr map merges, later wins on collision")
    (is (= {:id "ix-42" :class "card"}
           (render/root-attrs self {:id "wrong" :class "card"}))
        "framework id wins because morph-by-id depends on it")
    (is (thrown? clojure.lang.ExceptionInfo
                 (render/root-attrs {} {:class "x"}))
        "no :instance/id throws so the wire contract isn't silently lost")))

(deftest preserve-marks-widget-host-and-on-mount-is-first-render-only
  (let [fresh   {:instance/id "ix-42"}
        rendered {:instance/id "ix-42" :instance/rendered? true}]
    (is (= {:data-stube-preserve "editor"}
           (render/preserve fresh :editor)))
    (is (= {:data-init "mountEditor(el)"}
           (render/on-mount fresh :editor "mountEditor(el)")))
    (is (= {}
           (render/on-mount rendered :editor "mountEditor(el)")))
    (let [html (render/html
                 [:div (merge (render/root-attrs fresh)
                              (render/preserve fresh :editor)
                              (render/on-mount fresh :editor "mountEditor(el)"))])]
      (is (str/includes? html "data-stube-preserve=\"editor\""))
      (is (str/includes? html "data-init=\"mountEditor(el)\"")))))

(deftest on-unmount-emits-data-stube-on-unmount
  (let [fresh    {:instance/id "ix-42"}
        rendered {:instance/id "ix-42" :instance/rendered? true}]
    (testing "first render and subsequent renders both emit the attribute"
      ;; Unlike on-mount, the unmount expression must be present on
      ;; every patch — the morph keeps it attached for the bridge to
      ;; pick up when the host is later detached.
      (is (= {:data-stube-on-unmount "editor.destroy()"}
             (render/on-unmount fresh :editor "editor.destroy()")))
      (is (= {:data-stube-on-unmount "editor.destroy()"}
             (render/on-unmount rendered :editor "editor.destroy()"))))
    (testing "label validation matches preserve/on-mount"
      (is (thrown? clojure.lang.ExceptionInfo
                   (render/on-unmount fresh "" "noop()"))
          "empty label rejected"))
    (testing "self with no :instance/id is rejected"
      (is (thrown? clojure.lang.ExceptionInfo
                   (render/on-unmount {} :editor "noop()"))))
    (let [html (render/html
                 [:div (merge (render/root-attrs fresh)
                              (render/preserve   fresh :editor)
                              (render/on-mount   fresh :editor "mount(el)")
                              (render/on-unmount fresh :editor "el.cmView?.destroy()"))])]
      (is (str/includes? html "data-stube-on-unmount=\"el.cmView?.destroy()\""))
      (is (str/includes? html "data-stube-preserve=\"editor\""))
      (is (str/includes? html "data-init=\"mount(el)\"")))))

(deftest bind-builds-data-bind-attribute
  (is (= {(keyword "data-bind:answer__case.kebab") true}
         (render/bind :answer)))
  (is (= {(keyword "data-bind:answer-ix-000002__case.kebab") true}
         (render/bind :answer-ix-000002))
      "Datastar's default bind key casing is camel; keep Clojure kebab-case signal names on the wire"))

(deftest local-bind-scopes-signal-to-instance
  (let [self {:instance/id "ix-000002"}]
    (is (= :answer-ix-000002 (render/local-signal self :answer)))
    (is (= {(keyword "data-bind:answer-ix-000002__case.kebab") true}
           (render/local-bind self :answer)))))

(deftest back-button-posts-to-conversation-back-route
  (binding [render/*cid* "cv-001"]
    (is (= [:button {:type "button"
                     :class "stube-button"
                     (keyword "data-on:click") "@post('/back/cv-001')"}
            "Back"]
           (render/back-button "Back")))))

(deftest upload-attrs-use-multipart-route-and-hidden-frame
  (binding [render/*cid* "cv-001"]
    (let [self {:instance/id "ix-9"}]
      (is (= {:method  "post"
              :action  "/upload/cv-001/ix-9"
              :enctype "multipart/form-data"
              :target  "stube-upload-ix-9"}
             (render/upload-attrs self)))
      (is (= [:iframe {:name   "stube-upload-ix-9"
                       :title  "stube upload target"
                       :hidden true
                       :style  "display:none; width:0; height:0; border:0;"}]
             (render/upload-frame self))))))

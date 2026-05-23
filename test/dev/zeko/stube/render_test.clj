(ns dev.zeko.stube.render-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
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
      (is (re-find #"@post\('/conv/cv-001/ix-9/submit'\)" v)))))

(deftest on-encodes-structured-event-payload
  (binding [render/*cid* "cv-001"]
    (let [attrs (render/on {:instance/id "ix-9"} :click :as [:pick-day 12])
          [k v] (first attrs)]
      (is (= (keyword "data-on:click") k))
      (is (re-find #"/conv/cv-001/ix-9/pick-day\?_stube_payload=12" v)))))

(deftest on-target-routes-to-explicit-instance
  (binding [render/*cid* "cv-001"]
    (let [attrs (render/on-target "ix-parent" :click :as [:open 42])
          [k v] (first attrs)]
      (is (= (keyword "data-on:click") k))
      (is (re-find #"/conv/cv-001/ix-parent/open\?_stube_payload=42" v)))))

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
                     (keyword "data-on:click") "@post('/conv/cv-001/back')"}
            "Back"]
           (render/back-button "Back")))))

(deftest upload-attrs-use-multipart-route-and-hidden-frame
  (binding [render/*cid* "cv-001"]
    (let [self {:instance/id "ix-9"}]
      (is (= {:method  "post"
              :action  "/stube/upload/cv-001/ix-9"
              :enctype "multipart/form-data"
              :target  "stube-upload-ix-9"}
             (render/upload-attrs self)))
      (is (= [:iframe {:name   "stube-upload-ix-9"
                       :title  "stube upload target"
                       :hidden true
                       :style  "display:none; width:0; height:0; border:0;"}]
             (render/upload-frame self))))))

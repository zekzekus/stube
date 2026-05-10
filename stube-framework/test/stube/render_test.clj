(ns stube.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [stube.render :as render]))

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

(deftest on-without-cid-throws
  (binding [render/*cid* nil]
    (is (thrown? clojure.lang.ExceptionInfo
                 (render/on {:instance/id "ix-9"} :submit)))))

(deftest on-without-instance-throws
  (binding [render/*cid* "cv-1"]
    (is (thrown? clojure.lang.ExceptionInfo
                 (render/on {} :submit)))))

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

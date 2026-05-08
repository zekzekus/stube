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

(deftest on-without-cid-throws
  (binding [render/*cid* nil]
    (is (thrown? clojure.lang.ExceptionInfo
                 (render/on {:instance/id "ix-9"} :submit)))))

(deftest on-without-instance-throws
  (binding [render/*cid* "cv-1"]
    (is (thrown? clojure.lang.ExceptionInfo
                 (render/on {} :submit)))))

(deftest bind-builds-data-bind-attribute
  (is (= {(keyword "data-bind:answer") true} (render/bind :answer))))

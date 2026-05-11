(ns dev.zeko.stube.invariant-test
  "Executable checks for the §15 aesthetic bar."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.kernel :as kernel]
            [dev.zeko.stube.registry :as registry]
            [dev.zeko.stube.render :as render]
            [dev.zeko.stube.server :as server]
            [dev.zeko.stube.store])
  (:import (java.time Instant)))

(def ^:private root-dir (io/file "."))

(defn- project-file [path]
  (io/file root-dir path))

(defn- clj-files-under [path]
  (->> (file-seq (project-file path))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))))

(deftest kernel-size-budget-has-explicit-rationale
  (let [text  (slurp (project-file "src/dev/zeko/stube/kernel.clj"))
        lines (count (str/split-lines text))]
    (is (= 1 (count (re-seq #"(?m)^\(defmulti\b" text)))
        "the runtime stays organized around one effect multimethod")
    (is (or (<= lines 350)
            (:dev.zeko.stube/rationale (meta (the-ns 'dev.zeko.stube.kernel))))
        "kernel.clj must either fit the §15.4 budget or carry a rationale")))

(deftest examples-do-not-ship-custom-javascript
  (doseq [f (clj-files-under "examples")]
    (let [text (slurp f)]
      (is (not (re-find #"(?i)<script" text))
          (str "no literal <script> tags in " (.getPath f)))
      (is (not (re-find #"\[:script\b" text))
          (str "no hiccup script nodes in " (.getPath f))))))

(def ^:private example-namespaces
  '[dev.zeko.stube.examples.calc
    dev.zeko.stube.examples.main
    dev.zeko.stube.examples.multicounter
    dev.zeko.stube.examples.seaside-todo
    dev.zeko.stube.examples.tabs
    dev.zeko.stube.examples.todo
    dev.zeko.stube.examples.wizard])

(def ^:private edn-clean-examples
  [:demo/index
   :demo/calc
   :demo/multicounter
   :demo/seaside-todo-root
   :demo/tabs
   :demo/todo
   :demo/wizard])

(defn- reload-edn-clean-examples! []
  (server/reset-state!)
  (registry/clear!)
  (doseq [ns-sym example-namespaces]
    (require ns-sym :reload)))

(defn- read-edn-conversation [s]
  (edn/read-string {:readers {'inst #(Instant/parse %)}} s))

(defn- assert-edn-round-trip [label c]
  (let [printed (binding [*print-dup* false] (pr-str c))]
    (is (not (str/includes? printed "#object["))
        (str label " should not contain opaque JVM objects"))
    (is (= c (read-edn-conversation printed))
        (str label " should survive pr-str/read-string"))))

(defn- boot-example [root]
  (binding [render/*cid* "cv-invariant"]
    (first (kernel/run-effects (conv/new-conversation) (kernel/boot root)))))

(defn- dispatch-event [c event]
  (binding [render/*cid* "cv-invariant"]
    (first (kernel/dispatch c (merge {:instance-id (conv/top-id c)
                                      :signals     {}}
                                     event)))))

(defn- mid-flow [root c]
  (case root
    :demo/calc
    (dispatch-event c {:event :digit :payload "7"})

    :demo/todo
    (dispatch-event c {:event :edit :payload 1})

    :demo/tabs
    (dispatch-event c {:event :tab :payload :notes})

    ;; Booted state is already mid-flow enough for embedded/static
    ;; examples: children are materialized and the stack is live.
    c))

(deftest edn-clean-examples-round-trip-as-conversations
  (reload-edn-clean-examples!)
  (doseq [root edn-clean-examples]
    (testing root
      (assert-edn-round-trip root (mid-flow root (boot-example root))))))

(deftest example-defcomponents-are-top-level-registrations
  (doseq [f (clj-files-under "examples")]
    (doseq [[line-no line] (map-indexed vector (str/split-lines (slurp f)))
            :when (str/includes? line "(s/defcomponent")]
      (is (str/starts-with? line "(s/defcomponent")
          (str "defcomponent should be top-level in " (.getPath f)
               ":" (inc line-no))))))

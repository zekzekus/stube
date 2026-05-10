(ns stube.store-test
  "Tests for the slice-3 conversation store protocol and the two
  shipped implementations."
  (:require [clojure.java.io    :as io]
            [clojure.test       :refer [deftest is testing use-fixtures]]
            [stube.conversation :as conv]
            [stube.store        :as store]))

;; A fresh tmp dir per test, deleted afterwards.  Cheap; keeps the
;; assertions simple because each store is isolated.
(def ^:dynamic *tmp* nil)

(defn- delete-recursive [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursive c)))
  (.delete f))

(use-fixtures :each
  (fn [t]
    (let [d (java.nio.file.Files/createTempDirectory
              "stube-store-test"
              (make-array java.nio.file.attribute.FileAttribute 0))]
      (binding [*tmp* (.toFile d)]
        (try (t) (finally (delete-recursive *tmp*)))))))

;; ---------------------------------------------------------------------------
;; In-memory store: every op is a no-op
;; ---------------------------------------------------------------------------

(deftest in-memory-store-is-a-no-op
  (let [s (store/in-memory-store)]
    (is (= {} (store/load-all s)))
    (is (some? (store/save! s {:conv/id "cv-001"})))
    (is (= {} (store/load-all s)) "save! does not retain the value")
    (is (nil? (store/delete! s "cv-001")))))

;; ---------------------------------------------------------------------------
;; File store: round trips an EDN-clean conversation
;; ---------------------------------------------------------------------------

(deftest file-store-round-trip
  (let [s    (store/file-store *tmp*)
        conv (-> (conv/new-conversation)
                 (assoc :conv/instances {"ix-1" {:instance/id "ix-1"
                                                 :instance/type :t/leaf
                                                 :n 7}}
                        :conv/stack ["ix-1"]))
        cid  (:conv/id conv)]
    (store/save! s conv)
    (let [loaded (store/load-all s)]
      (is (contains? loaded cid))
      (is (= 7 (get-in loaded [cid :conv/instances "ix-1" :n]))
          "instance state survives the round trip")
      (is (= ["ix-1"] (get-in loaded [cid :conv/stack]))
          "stack survives the round trip"))
    (testing "delete!"
      (store/delete! s cid)
      (is (not (contains? (store/load-all s) cid))))))

;; ---------------------------------------------------------------------------
;; File store: skips a save when the conv contains a non-EDN value
;; (the canonical case is a stube.flow cloroutine continuation)
;; ---------------------------------------------------------------------------

(deftest file-store-skips-non-edn
  (let [s     (store/file-store *tmp*)
        ;; A bare 0-arg fn is the simplest non-EDN value we can stuff
        ;; into a conv to mimic a cloroutine continuation.
        conv  (assoc (conv/new-conversation)
                     :conv/instances {"ix-1" {:instance/id "ix-1"
                                              ::cont (fn [] :nope)}})
        captured (java.io.StringWriter.)]
    (binding [*err* captured]
      (store/save! s conv))
    (is (empty? (store/load-all s))
        "the file was not written")
    (is (re-find #"non-EDN" (str captured))
        "store warned via *err*")))

;; ---------------------------------------------------------------------------
;; File store: tolerates a corrupted file on load
;; ---------------------------------------------------------------------------

(deftest file-store-skips-corrupted-file
  (let [s    (store/file-store *tmp*)
        bad  (io/file *tmp* "cv-bad.edn")]
    (spit bad "this-is-not-edn ((((")
    (let [captured (java.io.StringWriter.)]
      (binding [*err* captured]
        (is (= {} (store/load-all s))
            "load-all returns the readable convs only (none here)")
        (is (re-find #"unreadable" (str captured)))))))

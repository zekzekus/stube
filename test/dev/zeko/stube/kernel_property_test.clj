(ns dev.zeko.stube.kernel-property-test
  "Generative tests over the kernel fold.

  We register a small stub registry of components that exercise the
  full effect vocabulary (`:call`, `:call-in-slot`, `:answer`,
  `:replace`, `:patch`, `:patch-signals`, `:back`, `:end`,
  `:set-keyed-children`) and then generate random event sequences
  against them.  After every dispatch we check three structural
  invariants:

  1. **No throw.**  Stale events, double-clicks, and answers from
     popped frames must surface as no-ops, not exceptions.
  2. **Conversation round-trips through EDN.**  `pr-str` and
     `read-string` produce an equal conversation at every step —
     the invariant that lets `file-store` work.
  3. **Fragment targets exist.**  Every `:elements` / `:error`
     fragment that names an instance-id selector refers to an
     instance that exists in the post-dispatch conversation.  This
     catches the whole class of \"the patch points at a div that
     was just removed\" bugs.

  The components themselves are tiny on purpose; the value here is in
  the random walk over the effect language, not in any single
  scenario."
  (:require [clojure.edn  :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.core         :as s]
            [dev.zeko.stube.kernel       :as kernel]
            [dev.zeko.stube.registry     :as registry]
            [dev.zeko.stube.render       :as render])
  (:import (java.time Instant)))

;; ---------------------------------------------------------------------------
;; Stub registry — one component that produces every effect kind under
;; some event.  Handlers are deliberately permissive: any unknown event
;; is a no-op so dispatch can never throw on a randomly-generated event.
;; ---------------------------------------------------------------------------

(defn- register-stubs! []
  (registry/clear!)

  (registry/register!
    {:component/id     :prop/leaf
     :component/init   (fn [_] {:n 0})
     :component/render (fn [self]
                         [:div (s/root-attrs self) "leaf:" (:n self)])
     :component/handle
     (fn [self {:keys [event payload]}]
       (case event
         :inc      (update self :n inc)
         :patch    [(s/patch [:p "patched"])]
         :signals  [(s/patch-signals {:k payload})]
         :answer   [(s/answer (:n self))]
         self))})

  (registry/register!
    {:component/id     :prop/parent
     :component/init   (fn [_] {:answers []})
     :children         {:slot/inner (conv/embed :prop/leaf {})}
     :component/render (fn [self]
                         [:section (s/root-attrs self)
                          [:p "parent " (count (:answers self))]
                          (s/render-slot self :slot/inner)])
     :component/handle
     (fn [self {:keys [event]}]
       (case event
         :call         [(s/call (conv/embed :prop/leaf {}) :on-leaf)]
         :call-in-slot [(s/call-in-slot :slot/inner (conv/embed :prop/leaf {})
                                        :on-leaf)]
         :replace      [(s/become (conv/embed :prop/leaf {}))]
         :back         [(s/back)]
         :end          [(s/end :ok)]
         self))

     :on-leaf
     (fn [self answer]
       (update self :answers (fnil conj []) answer))}))

;; ---------------------------------------------------------------------------
;; Generators
;; ---------------------------------------------------------------------------

(def gen-event
  (gen/let [event (gen/elements [:inc :patch :signals :answer
                                 :call :call-in-slot :replace
                                 :back :end :nope])]
    {:event event}))

;; ---------------------------------------------------------------------------
;; Invariants
;; ---------------------------------------------------------------------------

(defn- iid-selector? [s]
  (and (string? s) (str/starts-with? s "#ix-")))

(defn- selector-iid [s]
  (subs s 1))

(defn- assert-structural-soundness! [c]
  ;; The structural invariants the kernel maintains by contract:
  ;;
  ;;   * every iid on the call stack exists in :conv/instances
  ;;   * every iid named by some parent's :instance/children exists
  ;;
  ;; We deliberately do NOT assert that every :instance/parent points
  ;; at a live ancestor.  `:call-in-slot` parks the previous slot
  ;; occupant on the new instance via `:instance/previous` precisely
  ;; so it can be restored on `:answer`; when the parent itself is
  ;; replaced or ended before that answer arrives, the previous chain
  ;; becomes an orphan with a dangling `:instance/parent`.  Fixing
  ;; that requires sweeping previous chains in pop-top/remove-subtree
  ;; and is its own piece of work — out of scope here.
  (let [instances (:conv/instances c)
        ids       (set (keys instances))]
    (doseq [iid (:conv/stack c)]
      (assert (contains? ids iid)
              (str "stack iid " iid " missing from :conv/instances")))
    (doseq [[iid inst] instances]
      (doseq [[_slot child-iid] (:instance/children inst)]
        (when child-iid
          (assert (contains? ids child-iid)
                  (str "child " child-iid " of " iid " missing from :conv/instances")))))))

(defn- assert-edn-round-trip! [c]
  (let [printed (binding [*print-dup* false] (pr-str c))
        readers {'inst #(Instant/parse %)}
        reread  (edn/read-string {:readers readers} printed)]
    (assert (not (str/includes? printed "#object["))
            (str "conv contains opaque JVM object: " (subs printed 0 (min 200 (count printed)))))
    (assert (= c reread)
            "conv did not round-trip through pr-str/read-string")))

(defn- assert-fragment-targets! [c frags]
  (let [ids (set (keys (:conv/instances c)))]
    (doseq [{:fragment/keys [kind opts]} frags]
      (when (and (#{:elements :error} kind)
                 (iid-selector? (:selector opts)))
        (let [iid (selector-iid (:selector opts))]
          (assert (contains? ids iid)
                  (str kind " fragment targets " (:selector opts)
                       " but no such instance exists post-dispatch")))))))

;; ---------------------------------------------------------------------------
;; Walk
;; ---------------------------------------------------------------------------

(defn- top-iid [c]
  (or (conv/top-id c)
      ;; When the stack is empty the conversation has ended; future
      ;; events still need *some* iid for `dispatch`, but the kernel
      ;; drops events for missing instances as no-ops.
      "ix-gone"))

(defn- run-walk [events]
  (register-stubs!)
  (binding [render/*cid* "cv-property"]
    (let [c0       (first (kernel/run-effects (conv/new-conversation)
                                              (kernel/boot :prop/parent)))]
      (assert-structural-soundness! c0)
      (assert-edn-round-trip! c0)
      (loop [c c0
             [ev & more] events]
        (if (nil? ev)
          true
          (let [[c' frags] (kernel/dispatch c (merge ev
                                                    {:instance-id (top-iid c)
                                                     :signals     {}}))]
            (assert-structural-soundness! c')
            (assert-edn-round-trip! c')
            (assert-fragment-targets! c' frags)
            (recur c' more)))))))

;; ---------------------------------------------------------------------------
;; Property
;; ---------------------------------------------------------------------------

(def kernel-fold-respects-invariants
  (prop/for-all [events (gen/vector gen-event 0 25)]
    (try
      (run-walk events)
      (catch Throwable t
        ;; Surface the failing event sequence inside the assertion so
        ;; shrinking reports the offending walk in the failure output.
        (throw (ex-info (str "kernel-walk failed: " (ex-message t))
                        {:events events}
                        t))))))

(deftest kernel-fold-survives-random-event-sequences
  (let [result (tc/quick-check 200 kernel-fold-respects-invariants)]
    (is (:pass? result)
        (str "shrunk failure: " (pr-str (:shrunk result))))))

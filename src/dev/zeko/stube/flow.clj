(ns dev.zeko.stube.flow
  "Slice-1: linear flow components via the [[defflow]] macro.

  We deliberately shadow `clojure.core/await` (the agent-blocking
  primitive nobody reaches for in 2026) with our own coroutine-suspending
  `await`.  The `:refer-clojure :exclude` keeps the compiler quiet.

  A *flow* is a component whose role is to sequence a series of child
  components and return a final value.  Written by hand, a flow looks
  like a state machine: a `:start` hook plus a chain of `:on-step-N`
  resume keys that thread the partial result forward.  That is correct
  but tedious; the same logic reads naturally as straight-line code:

      (s/defflow :booking/wizard []
        (let [dates   (s/await (s/embed :booking/dates))
              room    (s/await (s/embed :booking/room  {:dates dates}))
              receipt (s/await (s/embed :booking/pay   {:price (price-for room)}))]
          {:dates dates :room room :receipt receipt}))

  The `defflow` macro compiles that body into a regular component
  registration.  Under the hood we use [cloroutine](https://github.com/leonoel/cloroutine):
  the body becomes a stackless coroutine whose suspend point is
  [[await]], and the coroutine continuation lives on the instance map as
  `::coro`.  Each user event delivered by the kernel is funnelled into
  one fixed resume key (`:on-flow-resume`) that injects the child's
  answer back into the coroutine and steps it forward.

  The result is a component whose externally observable behaviour is
  identical to a hand-rolled chain of `:on-step-N` callbacks (modulo the
  resume key's name; see ADR
  [0001-resume-key-naming](../../../../docs/decisions/0001-resume-key-naming.md)),
  but whose source reads as ordinary Clojure.

  ----------------------------------------------------------------------
  Restrictions inherited from cloroutine
  ----------------------------------------------------------------------

  * `await` cannot appear inside a nested `fn`, lazy seq, custom type
    method, or anywhere else the surrounding form might escape the
    coroutine's synchronous context.  `let`/`do`/`if`/`cond`/`when`/
    `loop`+`recur` are all fine.
  * `try`/`catch` *across* an `await` is not supported: a `catch` in
    the surrounding body cannot intercept the exception thrown into
    the coroutine on resume.  Use [[dev.zeko.stube.core/answer-error]]
    in the child to route failures explicitly.
  * Storing the coroutine on the instance map gives up EDN
    serialisability for flow instances.  A conversation containing a
    `defflow` is therefore not durable — the [[dev.zeko.stube.store]]
    file store logs a warning and skips its on-disk save.  This is a
    deliberate property: `defflow` is the ergonomic for transient
    flows.  When you need a long-running flow that survives restarts,
    write a hand-rolled task component with `:start` plus named resume
    keys.  The tutorial section *Durable flows: defflow vs. task
    components* shows the same wizard in both shapes."
    (:refer-clojure :exclude [await])
    (:require [cloroutine.core :as cr]
              [dev.zeko.stube.effects  :as e]
              [dev.zeko.stube.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Coroutine plumbing
;; ---------------------------------------------------------------------------
;;
;; The contract between the coroutine and the framework uses two values:
;;
;;   * the value `await` returns, which (per cloroutine semantics)
;;     becomes the value of `(coro)` on suspend.  We tag it so the
;;     outside can distinguish "I suspended" from "I am done".
;;   * a dynamic var holding the answer the kernel just delivered, so
;;     `on-resume` can hand it back as the value of the suspended
;;     `await` expression.
;;
;; Both ends are called synchronously inside `step!`, so `binding` is
;; sufficient for one-shot communication; no atoms are shared between
;; threads.

(def ^:dynamic ^:private *answer*
  "Volatile holding the value the kernel just delivered to the flow on
  resume.  Bound for the duration of one coroutine step."
  nil)

;; Tag values used to wrap the coroutine's outgoing payload.  Public so
;; the `defflow` macro can reference them from user namespaces; not part
;; of the application-facing API.
(def yield-tag ::yield)
(def done-tag  ::done)

(defn await
  "Inside a [[defflow]] body, suspend the surrounding flow until the
  embedded child produced by `embed-spec` answers, then resume with the
  child's answer value.

  Calling `await` outside a `defflow` body has no useful meaning; it
  exists primarily as the cloroutine break-point marker."
  [embed-spec]
  ;; Cloroutine uses *our* return value as the value the coroutine
  ;; yields back to the caller of `(coro)` at the suspend point.  We
  ;; wrap it so the framework can tell `[::yield …]` (suspended) apart
  ;; from `[::done …]` (the body's final value).
  [yield-tag embed-spec])

(defn on-resume
  "Cloroutine resume hook: returns the value that the suspended `await`
  call should evaluate to inside the body.  Always invoked synchronously
  by `(coro)` from inside [[step!]], so `*answer*` is in scope.

  Public so the `defflow` macro can name it from the user's namespace;
  not intended for application code."
  []
  @*answer*)

(defn step!
  "Advance `coro` (a cloroutine continuation) by one fragment.

  `answer` is the value the previously suspended `await` call should
  evaluate to; pass `nil` for the very first step (which has no
  suspended await to resume).

  Returns one of:

      [:yield <embed-spec>]   the body suspended at `(await embed-spec)`
      [:done  <value>]        the body ran to completion with `value`"
  [coro answer]
  (binding [*answer* (volatile! answer)]
    (let [result (coro)]
      (cond
        (and (vector? result) (= yield-tag (first result)))
        [:yield (second result)]

        (and (vector? result) (= done-tag (first result)))
        [:done (second result)]

        :else
        ;; Defensive: the macro always wraps the body so that the final
        ;; value comes back tagged.  If we see something else, the body
        ;; threw or someone sidestepped the macro.
        (throw (ex-info "stube flow coroutine returned an untagged value"
                        {:got result}))))))

;; ---------------------------------------------------------------------------
;; Effect emission for one coroutine step
;; ---------------------------------------------------------------------------

(def resume-key
  "The single resume key under which every flow's child answers are
  delivered.  Exposed so tests and tooling can refer to it without
  hardcoding the keyword."
  :on-flow-resume)

(defn -advance
  "Run `self`'s coroutine once, injecting `answer` (or `nil` for the
  first step), and return `[self effects]` for the kernel.

  Public-by-namespace-convention only — the macro emits calls to it.
  Application code should not call this directly."
  [self answer]
  (let [coro (::coro self)
        [outcome value] (step! coro answer)]
    (case outcome
      :yield [self [(e/call value resume-key)]]
      :done  [self [(e/answer value)]])))

;; ---------------------------------------------------------------------------
;; The macro
;; ---------------------------------------------------------------------------

(defn flow-cdef
  "Build the component map a `defflow` registers.  Pulled out of the
  macro so the structure is easy to read and easy to test.  Public so
  macro expansions in user namespaces can call it."
  [id init-fn]
  {:component/id   id
   :component/init init-fn
   ;; Tasks have no UI of their own; the kernel's default-render emits a
   ;; hidden placeholder div.  We could inline that here, but letting the
   ;; kernel default through keeps both code paths exercised.
   :start          (fn [self] (-advance self nil))
   resume-key      (fn [self answer] (-advance self answer))})

(defmacro defflow
  "Define and register a flow component.

      (s/defflow :booking/wizard [{:keys [user-id]}]
        (let [dates (s/await (s/embed :booking/dates {:user user-id}))
              room  (s/await (s/embed :booking/room  {:dates dates}))]
          {:dates dates :room room}))

  * `id` — a namespaced keyword (same rule as `defcomponent`).
  * `bindings` — a destructuring vector applied to the embed args map
    passed at instantiation.  `[]` is fine if the flow takes no args.
  * `body` — ordinary Clojure that may call [[await]] zero or more
    times.  The body's final expression is the value the flow answers
    to its parent (or, for a root flow, the value of `:end`).

  Restrictions and the in-memory-only durability boundary are
  documented at the top of [[dev.zeko.stube.flow]]."
  [id bindings & body]
  (when-not (qualified-keyword? id)
    (throw (ex-info "defflow id must be a namespaced keyword" {:got id})))
  (when-not (and (vector? bindings) (<= (count bindings) 1))
    (throw (ex-info "defflow bindings must be a vector with at most one entry"
                    {:got bindings})))
  ;; `bindings` reads like a function arglist — `[]` for "ignore args",
  ;; or `[<destructure>]` for a single destructure of the embed args map.
  ;; We extract the lone destructure form (or `_`) so the surrounding
  ;; `(let [pat args] …)` works regardless of the args' shape.
  (let [pat (or (first bindings) '_)]
    `(registry/register!
       (flow-cdef
         ~id
         (fn [args#]
           {::coro (cr/cr
                     ;; Map cloroutine's break/resume to our own vars.
                     ;; Both sides are fully-qualified so they resolve
                     ;; identically regardless of the user's :require alias.
                     {dev.zeko.stube.flow/await dev.zeko.stube.flow/on-resume}
                     ;; Tag the final value so `step!` can recognise it.
                     ;; The user-visible body sits inside the destructure.
                     (let [~pat args#]
                       [dev.zeko.stube.flow/done-tag (do ~@body)]))})))))

(ns dev.zeko.stube.effects
  "Constructors and accessors for the effect vocabulary the kernel folds.

  Effects on the wire are plain vectors keyed by an op tag:

      [:call <embed> :resume <k>]
      [:call-in-slot <slot> <embed> :resume <k>]
      [:answer <value>]
      [:replace <embed>]
      [:patch <hiccup>]
      [:patch-signals <map>]
      [:execute-script <js>]
      [:history :replace|:push <url>]
      [:io <fn>]
      [:after <ms> <event>]
      [:subscribe <topic> <event>]
      [:unsubscribe] | [:unsubscribe <topic>]
      [:back]
      [:end <value>]

  This namespace deliberately does NOT change that wire shape.  It only
  gives callers — handlers, the kernel's `step` methods, tests — named
  helpers so the data contract lives in one file instead of being spread
  across pattern-matching destructure forms.

  Constructors are named after the op; accessors are named
  `<op>-<role>` (e.g. [[call-embed]], [[after-delay]]).  Effects that
  the kernel materially treats as continuations (call/answer/etc.) and
  those that are pure side-effects (io/after/subscribe/etc.) live side
  by side here because they share one folder."
  (:refer-clojure :exclude [replace]))

;; ---------------------------------------------------------------------------
;; Effect-origin context
;; ---------------------------------------------------------------------------

(def ^:dynamic *effect-iid*
  "Instance id whose handler/lifecycle hook emitted the effects currently
  being folded.  Stack calls historically used the top frame, but
  embedded children can also handle events; slot-local effects need the
  actual emitting instance.  Bound by the kernel/lifecycle around any
  call to `run-effects`."
  nil)

(defmacro with-origin
  "Bind [[*effect-iid*]] to `iid` while running `body`.  Use whenever
  you fold effects whose origin is not the current top frame."
  [iid & body]
  `(binding [*effect-iid* ~iid]
     ~@body))

;; ---------------------------------------------------------------------------
;; Predicates
;; ---------------------------------------------------------------------------

(defn op
  "Return the op keyword of an effect vector."
  [eff]
  (when (vector? eff) (first eff)))

(defn op?
  "True when `eff` is an effect with op `op-kw`."
  [op-kw eff]
  (= op-kw (op eff)))

;; ---------------------------------------------------------------------------
;; Continuation-shaped effects
;; ---------------------------------------------------------------------------

(defn call
  "Push a child onto the stack.  On `:answer`, the parent's `resume-key`
  function is invoked with the answered value."
  ([embed]            [:call embed :resume nil])
  ([embed resume-key] [:call embed :resume resume-key]))

(defn call-embed  [eff] (nth eff 1))
(defn call-resume [eff]
  (let [tail (subvec (vec eff) 2)]
    (-> (apply hash-map tail) :resume)))

(defn call-in-slot
  "Temporarily swap an embedded slot's child; the new child answers back
  to the parent without taking over the page."
  ([slot embed]            [:call-in-slot slot embed :resume nil])
  ([slot embed resume-key] [:call-in-slot slot embed :resume resume-key]))

(defn slot-call-slot   [eff] (nth eff 1))
(defn slot-call-embed  [eff] (nth eff 2))
(defn slot-call-resume [eff]
  (let [tail (subvec (vec eff) 3)]
    (-> (apply hash-map tail) :resume)))

(defn answer
  "Pop this frame; deliver `value` to the parent under its resume key."
  [value]
  [:answer value])

(defn answer-value [eff] (nth eff 1))

(defn replace
  "Pop this frame and push another in its place (Seaside `become:`).
  The replacement inherits the original parent linkage and resume key."
  [embed]
  [:replace embed])

(defn replace-embed [eff] (nth eff 1))

(defn set-keyed-children
  "Reconcile the keyed-child set of `slot` to the ordered `pairs`
  `[[stable-key embed-spec] ...]`.  See `dev.zeko.stube.keyed/reconcile!`
  for the diff semantics."
  [slot pairs]
  [:set-keyed-children slot (vec pairs)])

(defn keyed-children-slot  [eff] (nth eff 1))
(defn keyed-children-pairs [eff] (nth eff 2))

;; ---------------------------------------------------------------------------
;; Output effects
;; ---------------------------------------------------------------------------

(defn patch
  "Emit an extra DOM patch without changing the stack."
  [hiccup]
  [:patch hiccup])

(defn patch-hiccup [eff] (nth eff 1))

(defn patch-signals
  "Push a Datastar signal patch."
  [m]
  [:patch-signals m])

(defn patch-signals-map [eff] (nth eff 1))

(defn execute-script
  "Run literal JS in the browser."
  [js]
  [:execute-script js])

(defn script-source [eff] (nth eff 1))

;; ---------------------------------------------------------------------------
;; Async / side-effect effects
;; ---------------------------------------------------------------------------

(defn io
  "Call `(thunk)` off-thread, fire-and-forget."
  [thunk]
  [:io thunk])

(defn io-thunk [eff] (nth eff 1))

(defn after
  "Schedule `route-event` for the current instance after `delay-ms`."
  [delay-ms route-event]
  [:after delay-ms route-event])

(defn after-delay [eff] (nth eff 1))
(defn after-event [eff] (nth eff 2))

(defn subscribe
  "Subscribe the current instance to `topic`.  Published messages arrive
  as `route-event`."
  [topic route-event]
  [:subscribe topic route-event])

(defn subscribe-topic [eff] (nth eff 1))
(defn subscribe-event [eff] (nth eff 2))

(defn unsubscribe
  "Remove the current instance's topic subscription(s)."
  ([]      [:unsubscribe])
  ([topic] [:unsubscribe topic]))

(defn unsubscribe-topic [eff] (nth eff 1 nil))

;; ---------------------------------------------------------------------------
;; URL history effects
;; ---------------------------------------------------------------------------

(defn history
  "Sync the browser URL without a page reload.

  Two modes:

      (history :replace url)  ; → [:history :replace url]
      (history :push    url)  ; → [:history :push url]

  `:replace` calls `history.replaceState`; `:push` calls `history.pushState`.
  URL may be an absolute path, a relative path, or a full URL string.

  Use `:replace` when a mutation updates the \"current\" logical page (e.g.
  search filter change) and `:push` when the user navigates to a new page
  (e.g. opening a note, advancing a wizard step).

  URL parsing on first load is not in scope — read `?q=` from the GET
  request and pass it as init-args to `mint-conversation!`."
  [mode url]
  (when-not (#{:replace :push} mode)
    (throw (ex-info "history mode must be :replace or :push"
                    {:got mode})))
  [:history mode url])

(defn history-mode [eff] (nth eff 1))
(defn history-url  [eff] (nth eff 2))

;; ---------------------------------------------------------------------------
;; Conversation-level
;; ---------------------------------------------------------------------------

(def back
  "Restore the previous conversation snapshot from `:conv/history`."
  [:back])

(defn end
  "Terminate the conversation with a final value."
  [value]
  [:end value])

(defn end-value [eff] (nth eff 1 nil))

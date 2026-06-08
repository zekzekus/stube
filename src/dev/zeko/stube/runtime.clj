(ns dev.zeko.stube.runtime
  "Embeddable stube runtime instances.

  `dev.zeko.stube.kernel` remains the pure effect fold.  This namespace
  holds the small amount of mutable runtime state needed to embed that
  fold in a host Ring app: live conversations, SSE channels, timers,
  subscriptions, and the pending-root baton between shell render and
  first SSE attach."
  (:require [dev.zeko.stube.conversation :as conv]
            [dev.zeko.stube.errors       :as errors]
            [dev.zeko.stube.fragments    :as f]
            [dev.zeko.stube.kernel       :as pure]
            [dev.zeko.stube.lifecycle    :as lc]
            [dev.zeko.stube.render       :as render]
            [dev.zeko.stube.session      :as session]
            [dev.zeko.stube.shell        :as shell]
            [dev.zeko.stube.store        :as store])
  (:import (java.time Duration Instant)
           (java.util UUID)))

(declare dispatch! dispatch-to! schedule-event! stop-keepalive!
         subscribe! unsubscribe! swap-conv!)

;; ---------------------------------------------------------------------------
;; Kernel values
;; ---------------------------------------------------------------------------

(defn- normalize-base-path [base-path]
  (let [base (or base-path "")]
    (cond
      (or (= base "") (= base "/")) ""
      (.endsWith base "/") (subs base 0 (dec (count base)))
      :else base)))

(defn- default-options [opts]
  (let [custom-session? (contains? opts :session-id-fn)
        merged
        (merge {:context-fn        (constantly nil)
                :app               nil
                :principal-fn      nil
                :store             (store/in-memory-store)
                :base-path         ""
                :root-selector     "#root"
                :session-id-fn     session/request-session
                :on-conv-mint      (fn [conv _request] conv)
                :on-error          nil
                ;; Security / audit observability hooks + the dispatch
                ;; authz seam.  All default to nil (no-op).  See
                ;; make-kernel docstring.
                :on-auth-fail      nil
                :on-stale          nil
                :on-shell-mint     nil
                :before-dispatch   nil
                :ui-css?           true
                :base-css          []
                :css-layer-order   nil
                :eager-scripts     []
                :halos?            false
                :signal-case       :kebab
                ;; Session-cookie hygiene.  The cookie is `Secure` by
                ;; default — embedders run behind the host's TLS.  Set
                ;; `:dev-cookie? true` only when the same kernel serves
                ;; plain HTTP (localhost dev), or the browser will refuse
                ;; to send the cookie back and every conversation looks
                ;; cross-session.  `:cookie-domain` / `:cookie-path`
                ;; scope the cookie for embedded mounts.
                :dev-cookie?       false
                :cookie-domain     nil
                :cookie-path       "/"
                ;; Bounded request parsing.  An event POST carries a JSON
                ;; signals body and an EDN payload query param, both
                ;; attacker-controlled; cap them so a single request can
                ;; neither OOM the parser nor stream an unbounded body.
                ;; Oversize → 413.
                :max-signals-bytes 65536
                :max-payload-bytes 4096
                ;; Multipart uploads.  Reject a body whose Content-Length
                ;; exceeds the cap (413) before parsing, and delete the
                ;; tempfiles ring writes once the dispatch has consumed
                ;; them — unless `:keep-upload?` is set, for handlers that
                ;; hand the file off to async processing and clean up
                ;; themselves.
                :max-upload-bytes  10485760   ; 10 MiB
                :keep-upload?      false
                ;; SSE comment-frame heartbeat that keeps reverse-proxy
                ;; idle timers happy.  15s sits under the common 30/60s
                ;; thresholds (nginx, ALB).  Set to nil or 0 to disable.
                :sse-keepalive-ms  15000}
               opts)]
    ;; The default session minter is a closure over the resolved cookie
    ;; attributes, so `:dev-cookie?` / `:cookie-domain` / `:cookie-path`
    ;; reach `session/ensure-session`.  A host that supplied its own
    ;; `:session-id-fn` (host-managed sessions) or an explicit
    ;; `:ensure-session-fn` keeps full control — we don't override.
    (cond-> merged
      (and (not custom-session?)
           (not (contains? opts :ensure-session-fn)))
      (assoc :ensure-session-fn
             (let [cookie-opts {:secure? (not (:dev-cookie? merged))
                                :domain  (:cookie-domain merged)
                                :path    (:cookie-path merged)}]
               (fn [req] (session/ensure-session req cookie-opts)))))))

(defn make-kernel
  "Create an embeddable stube runtime instance.

  Stable embedder options:

  * `:context-fn` — `(fn [request] ctx)` stored on each conversation and
    readable in handlers with `(s/context self)`.
  * `:app` — opaque host value (typically a small map of dependencies
    such as `{:db ds :mail mailer}`).  Read from component code with
    `(s/app)`.  Not persisted; rebuild from live JVM state on each
    `make-kernel` call.
  * `:principal-fn` — `(fn [request] principal-or-nil)` invoked once at
    mint time.  Result is persisted on the conversation as
    `:conv/principal`; component code reads it with `(s/principal)`.
    Re-authentication is the host's job — if the principal needs to
    change, end the conversation and re-mint.
  * `:store` — persistence backend from `dev.zeko.stube.store`.
  * `:base-path` — URL prefix used by shell/render helpers.
  * `:session-id-fn` — host session lookup; defaults to `stube_sid`.
  * `:on-conv-mint` — optional `(fn [conv request] conv')` hook.
  * `:on-error` — reserved hook for adapter error reporting.
  * `:base-css` — vector of stylesheet URLs (strings) that
    [[head-tags]] should emit unconditionally, before any
    component-derived stylesheet.  Use this when the host has
    page-wide CSS that must appear on every page — including pages
    that do not embed a stube shell (so component-scoped
    `stube_styles/<ns>/<name>.css` is not enough).  URLs are emitted
    verbatim; relative URLs resolve against the host page, absolute
    URLs are passed through unchanged.
  * `:css-layer-order` — optional vector of CSS layer names (strings or
    keywords).  When provided, [[head-tags]] emits a top-level
    `@layer name1, name2, …;` declaration before any
    component-derived stylesheet `<link>`.  Because CSS layers honour
    the *first* declaration order in the document, this fixes the
    cascade order across the per-component stylesheets that
    `head-tags` auto-emits alphabetically from
    `resources/stube_styles/<ns>/<name>.css` — host CSS no longer has
    to live in a single ordered file just to control layer order.
  * `:eager-scripts` — vector of inline JS snippets (strings)
    [[head-tags]] should emit as synchronous `<script>` blocks in
    `<head>` *before* any `type=\"module\"` script.  Use this to seed
    `window.<X>` namespaces that inline Datastar expressions
    (`data-on:input=\"window.X.foo(...)\"`) need available from
    frame 1, before the deferred ESM module graph finishes.  Snippets
    are emitted verbatim and concatenated into a single `<script>`
    block; the host is responsible for the contents (no escaping).
  * `:signal-case` — `:kebab` (default) or `:camel`.  Picks the wire
    casing for every signal helper that renders a `data-bind:<key>`
    attribute, builds an inline-expression `$ref`, or reads a signal
    off a posted event ([[dev.zeko.stube.render/bind]],
    [[dev.zeko.stube.render/local-bind]], [[dev.zeko.stube.render/$]],
    [[dev.zeko.stube.render/signal]]).  Per-call `{:case ...}` opts
    still win.  Choose `:camel` when any inline Datastar expression
    references a signal (JS identifiers can't contain dashes);
    `:kebab` when all signal access is pure-Clojure.
  * `:sse-keepalive-ms` — interval in milliseconds for the SSE
    heartbeat that keeps reverse-proxy idle timers happy.  Defaults
    to 15000.  Set to nil or 0 to disable (e.g. when the host's proxy
    has no idle timeout, or in tests).
  * `:max-signals-bytes` — cap (in bytes) on the JSON signals body of
    an event POST.  Default 64 KiB.  Oversize requests get a `413`
    without the body being parsed or fully buffered.
  * `:max-payload-bytes` — cap (in bytes) on the EDN `payload` query
    param of an event POST.  Default 4 KiB.  Oversize → `413`;
    unparseable → `400`.  The bound also caps EDN nesting depth, so a
    deeply-nested value cannot exhaust the parser stack.
  * `:dev-cookie?` — when true, the `stube_sid` cookie is minted
    *without* the `Secure` attribute.  Default false (secure): an
    embedded kernel runs behind the host's TLS.  Flip this on only when
    the same kernel serves plain HTTP (localhost dev) — otherwise the
    browser refuses to send the cookie back and every conversation
    appears cross-session.  Ignored when the host supplies its own
    `:session-id-fn` / `:ensure-session-fn`.
  * `:cookie-domain` / `:cookie-path` — scope the `stube_sid` cookie.
    Default no `Domain` and `Path=/`.  Set `:cookie-path` to a mount
    prefix when several independent stube apps share an origin.
  * `:max-upload-bytes` — cap (in bytes) on a multipart upload body,
    checked against `Content-Length` before parsing.  Default 10 MiB.
    Oversize → `413`.
  * `:keep-upload?` — by default the upload handler deletes ring's
    multipart tempfiles once the `:upload-received` dispatch has
    consumed them.  Set this true when a handler hands the tempfile to
    asynchronous processing (e.g. an `:io` thunk) and takes
    responsibility for deleting it itself.
  * `:on-auth-fail` / `:on-stale` / `:on-shell-mint` — optional audit
    hooks, each `(fn [info])`, default nil (no-op).  `:on-auth-fail`
    fires when an owner-cookie or CSRF check rejects a request
    (`info` has `:request` `:cid` `:route` `:reason`); `:on-stale`
    fires when a `410`-stale response goes out (`:cid`);
    `:on-shell-mint` fires when a GET mints a conversation (`:request`
    `:cid` `:flow-id`).  A throwing hook is swallowed and logged.
  * `:before-dispatch` — optional authz / rate-limit seam,
    `(fn [conv event request])` run just before an event is dispatched.
    Return `:continue` to proceed or `[:reject status body]` to short-
    circuit with that response.  Fails closed: a throwing hook rejects
    the request."
  ([]
   (make-kernel {}))
  ([opts]
   (let [{:keys [store base-path] :as opts} (default-options opts)
         restored (store/load-all store)]
     (assoc opts
            :id (str (UUID/randomUUID))
            :base-path (normalize-base-path base-path)
            :!conversations  (atom restored)
            :!sse-sessions   (atom {})
            :!sse-keepalive  (atom {})
            :!pending-roots  (atom {})
            :!timers         (atom {})
            :!subscriptions  (atom {})
            ;; Per-cid monitor objects.  `swap-conv!` locks on the
            ;; matching monitor so the dispatch function runs exactly
            ;; once even when concurrent events race on the same
            ;; conversation.  Without this, `swap!`'s retry semantics
            ;; would re-run handlers (and their side effects: spawned
            ;; futures, publish calls, subscribe atoms) more than once,
            ;; causing fan-out amplification.
            :!cid-locks      (atom {})
            ;; Per-kernel dedup for the answer-error fallback warning;
            ;; see `kernel/warn-fallback-once!`.  Scoped here so two
            ;; embedded kernels each emit their own one-time message.
            :!answer-error-warned (atom #{})
            :!shutting-down? (atom false)))))

(defn current-store [k] (:store k))
(defn base-path [k] (:base-path k))
(defn root-selector [k] (:root-selector k))
(defn ui-css? [k] (boolean (:ui-css? k)))
(defn halos? [k] (boolean (:halos? k)))

(defn shutting-down?
  "True once [[halt!]] has begun the shutdown sequence for `k`.  HTTP
  adapters should refuse new conversation mints (typically 503) while
  this is true."
  [k]
  (boolean (some-> (:!shutting-down? k) deref)))

(defn ensure-session
  "Return `[sid set-cookie-header-or-nil]` for `request` under kernel
  `k`.  Kernels with a custom `:session-id-fn` default to host-managed
  sessions and do not mint a stube cookie."
  [k request]
  (if-let [ensure (:ensure-session-fn k)]
    (ensure request)
    [((:session-id-fn k) request) nil]))

(defn authorized?
  "True when `request` owns conversation `cid` under kernel `k`."
  [k request cid]
  (let [conv  (get @(:!conversations k) cid)
        owner (:conv/owner-token conv)]
    (or (nil? owner)
        (= owner ((:session-id-fn k) request)))))

(defn with-kernel-bindings
  "Run `f` with render URL context and async hooks for kernel `k`."
  [k cid f]
  (let [principal (get-in @(:!conversations k) [cid :conv/principal])]
    (binding [render/*cid* cid
              render/*base-path* (:base-path k)
              render/*root-selector* (:root-selector k)
              render/*signal-case* (or (:signal-case k) :kebab)
              pure/*current-kernel* k
              pure/*current-app* (:app k)
              pure/*current-principal* principal
              pure/*schedule-event!* #(schedule-event! k %)
              pure/*subscribe!* #(subscribe! k %)
              pure/*unsubscribe!* #(unsubscribe! k %)
              pure/*dispatch-to!* #(dispatch-to! k %)
              pure/*run-io!* #(future
                                (try (%)
                                     (catch Throwable t
                                       (binding [*out* *err*]
                                         (println "dev.zeko.stube.runtime: :io effect threw —"
                                                  (ex-message t))))))
              errors/*on-error* (:on-error k)]
      (f))))

(defn- with-render-bindings [k cid f]
  (let [principal (get-in @(:!conversations k) [cid :conv/principal])]
    (binding [render/*cid* cid
              render/*base-path* (:base-path k)
              render/*root-selector* (:root-selector k)
              render/*signal-case* (or (:signal-case k) :kebab)
              pure/*current-app* (:app k)
              pure/*current-principal* principal]
      (f))))

;; ---------------------------------------------------------------------------
;; Conversation lifecycle
;; ---------------------------------------------------------------------------

(defn- put-pending-root! [k cid root]
  (swap! (:!pending-roots k) assoc cid root)
  nil)

(defn pending-root
  "Pop and return the root embed/flow for `cid`, if any."
  [k cid]
  (let [[old _] (swap-vals! (:!pending-roots k) dissoc cid)]
    (get old cid)))

(defn- forget-pending-root! [k cid]
  (swap! (:!pending-roots k) dissoc cid)
  nil)

(defn conversation
  "Snapshot of conversation `cid` in kernel `k`, or nil."
  [k cid]
  (get @(:!conversations k) cid))

(defn active-conversations
  "Snapshot of all active conversations in kernel `k`."
  [k]
  @(:!conversations k))

(defn- context-for [k request]
  ((:context-fn k) request))

(defn- principal-for [k request]
  (when-let [f (:principal-fn k)] (f request)))

(defn- install-conversation! [k root-id init-args request owner-token csrf-token]
  (let [ctx       (context-for k request)
        principal (principal-for k request)
        conv (cond-> (conv/new-conversation)
               owner-token       (assoc :conv/owner-token owner-token)
               csrf-token        (assoc :conv/csrf-token csrf-token)
               (some? ctx)       (assoc :conv/context ctx)
               (some? principal) (assoc :conv/principal principal))
        conv ((:on-conv-mint k) conv request)
        cid  (:conv/id conv)]
    (swap! (:!conversations k) assoc cid conv)
    (put-pending-root! k cid (conv/embed root-id (or init-args {})))
    cid))

(defn create-conversation!
  "Compatibility helper for standalone server code that already resolved
  the owner token.  Does not mint a CSRF token — that is the
  [[mint-conversation!]] (GET shell) path's job; conversations created
  here fall back to cookie + `SameSite=Lax` for cross-site protection."
  ([k root-id]
   (create-conversation! k root-id nil))
  ([k root-id owner-token]
   (install-conversation! k root-id {} nil owner-token nil)))

(defn conversation-csrf-token
  "The CSRF nonce recorded on conversation `cid`, or nil."
  [k cid]
  (:conv/csrf-token (conversation k cid)))

(defn rotate-session!
  "Rotate the session that owns conversation `cid`: mint a fresh
  `stube_sid`, record it as the new `:conv/owner-token`, and return the
  `Set-Cookie` header string the host must attach to its response (built
  with this kernel's cookie attributes).  Returns nil if `cid` is
  unknown or the kernel uses host-managed sessions.

  Call this on login / logout: a fixed session id surviving a privilege
  change is the classic session-fixation hazard.  The conversation's
  CSRF token is left intact, so the *current* page keeps working; the
  old `stube_sid` stops authorizing as soon as the new cookie lands."
  [k cid]
  (when (and (:ensure-session-fn k) (conversation k cid))
    (let [sid (session/new-session)]
      (swap-conv! k cid (fn [c] [(assoc c :conv/owner-token sid) []]))
      (session/session-cookie-header sid
        {:secure? (not (:dev-cookie? k))
         :domain  (:cookie-domain k)
         :path    (:cookie-path k)}))))

(defn mint-conversation!
  "Register a new conversation for `root-id` and return its cid.

  The 4-arity calls [[ensure-session]] internally; the 5-arity accepts
  a pre-computed `owner-token` from a caller that already ran
  ensure-session and needs the conversation to be owned by *that*
  exact session (otherwise ensure-session, called with a still-
  cookieless request, would mint a second sid and own the conv with
  one the browser will never present)."
  ([k root-id request]
   (mint-conversation! k root-id {} request))
  ([k root-id init-args request]
   (let [[sid _set-cookie] (ensure-session k request)]
     (install-conversation! k root-id init-args request sid (conv/new-csrf-token))))
  ([k root-id init-args request owner-token]
   (install-conversation! k root-id init-args request owner-token (conv/new-csrf-token))))

(defn- cid-lock
  "Return (and lazily mint) the per-cid monitor object for `cid`.  Locking
  on this serialises every `swap-conv!` for `cid` so the dispatch
  function (which has side effects: spawned futures, publish/subscribe
  mutations) runs exactly once."
  [k cid]
  (or (get @(:!cid-locks k) cid)
      (-> (swap! (:!cid-locks k)
                 (fn [m]
                   (if (contains? m cid) m (assoc m cid (Object.)))))
          (get cid))))

(defn swap-conv!
  "Apply `(f conv) → [conv' fragments]` to conversation `cid` under the
  per-cid lock, then atomically commit `conv'`.  `f` is called exactly
  once — unlike a bare `swap!` whose retry semantics would re-run `f`
  (and its side effects) under contention."
  [k cid f]
  ;; `cid-lock` returns a long-lived monitor stashed in `(:!cid-locks k)`,
  ;; so `locking` here is over an object shared across all swap-conv! calls
  ;; for the same cid.  clj-kondo can't see through the helper.
  #_:clj-kondo/ignore
  (locking (cid-lock k cid)
    (when-let [c (get @(:!conversations k) cid)]
      (let [pair     (f c)
            [c' _fr] pair]
        (swap! (:!conversations k) assoc cid c')
        (try
          (store/save! (:store k) c')
          (catch Throwable t
            (binding [*out* *err*]
              (println "dev.zeko.stube.runtime: store save! threw —" (ex-message t)))))
        pair))))

(defn- cancel-timers! [k cid]
  (doseq [f (get @(:!timers k) cid)]
    (future-cancel f))
  (swap! (:!timers k) dissoc cid)
  nil)

(defn remove-subscriptions-for-cid! [k cid]
  (swap! (:!subscriptions k)
         (fn [topics]
           (into {}
                 (keep (fn [[topic subscribers]]
                         (let [subscribers' (into {}
                                                  (remove (fn [[[sub-cid _iid] _event]]
                                                            (= sub-cid cid)))
                                                  subscribers)]
                           (when (seq subscribers')
                             [topic subscribers']))))
                 topics)))
  nil)

(defn end-conversation!
  "Drop a conversation, any SSE binding, pending root, timers,
  subscriptions, and persisted copy."
  [k cid]
  (cancel-timers! k cid)
  (stop-keepalive! k cid)
  (remove-subscriptions-for-cid! k cid)
  (forget-pending-root! k cid)
  (swap! (:!conversations k) dissoc cid)
  (swap! (:!sse-sessions k) dissoc cid)
  (swap! (:!cid-locks k) dissoc cid)
  (try
    (store/delete! (:store k) cid)
    (catch Throwable t
      (binding [*out* *err*]
        (println "dev.zeko.stube.runtime: store delete! threw —" (ex-message t)))))
  nil)

(defn end! [k cid] (end-conversation! k cid))

(defn- ->duration [x]
  (cond
    (nil? x) nil
    (instance? Duration x) x
    (integer? x) (Duration/ofMillis x)
    :else (throw (ex-info "Expected java.time.Duration or millisecond integer"
                          {:got x}))))

(defn reap!
  "End conversations whose `:conv/touched` is older than `ttl`."
  [k ttl]
  (let [ttl     (->duration ttl)
        cutoff  (.minus (Instant/now) ttl)
        expired (->> @(:!conversations k)
                     (keep (fn [[cid c]]
                             (when-let [^Instant touched (:conv/touched c)]
                               (when (.isBefore touched cutoff)
                                 cid))))
                     vec)]
    (doseq [cid expired]
      (end-conversation! k cid))
    expired))

;; ---------------------------------------------------------------------------
;; SSE and dispatch
;; ---------------------------------------------------------------------------

(defn- start-keepalive!
  "Start a daemon Thread that pings `sse-gen` every `interval-ms` until
  either the channel rejects the write or the thread is interrupted.
  Returns the Thread so the caller can stash it for cancellation."
  [sse-gen interval-ms]
  (let [^Thread t (Thread.
                    ^Runnable
                    (fn []
                      (try
                        (loop []
                          (Thread/sleep ^long interval-ms)
                          (when (f/push-keep-alive! sse-gen)
                            (recur)))
                        (catch InterruptedException _ nil)
                        (catch Throwable _ nil)))
                    "stube-sse-keepalive")]
    (.setDaemon t true)
    (.start t)
    t))

(defn- stop-keepalive! [k cid]
  (when-let [^Thread t (get @(:!sse-keepalive k) cid)]
    (.interrupt t)
    (swap! (:!sse-keepalive k) dissoc cid)))

(defn register-sse! [k cid sse-gen]
  (swap! (:!sse-sessions k) assoc cid sse-gen)
  (when-let [ms (:sse-keepalive-ms k)]
    (when (pos? ms)
      (stop-keepalive! k cid)
      (swap! (:!sse-keepalive k) assoc cid (start-keepalive! sse-gen ms)))))

(defn unregister-sse! [k cid]
  (stop-keepalive! k cid)
  (swap! (:!sse-sessions k) dissoc cid))

(defn sse [k cid]
  (get @(:!sse-sessions k) cid))

(def push-fragments! f/push!)

(defn apply-conv!
  "Apply `(f conv) → [conv' fragments]`, push fragments over SSE, and
  end the conversation if the kernel marked it ended."
  [k cid f]
  (when (conversation k cid)
    (let [[conv' frags] (with-kernel-bindings
                          k cid
                          #(swap-conv! k cid f))]
      (when-let [sse-gen (sse k cid)]
        (push-fragments! sse-gen frags))
      (when (:conv/ended? conv')
        (end-conversation! k cid))
      [conv' frags])))

(defn run-effects! [k cid effects]
  (apply-conv! k cid (fn [c] (pure/run-effects c effects))))

(defn dispatch!
  "Dispatch one event into a live conversation and return the fragments
  produced.  Also pushes those fragments to an open SSE stream."
  [k cid {:keys [instance-id] :as event}]
  (when-let [live (conversation k cid)]
    (when (and (not (:conv/ended? live))
               (conv/instance live instance-id))
      (second (apply-conv! k cid (fn [c] (pure/dispatch c event)))))))

(defn shell-for
  "Return the embeddable Hiccup shell fragment for conversation `cid`."
  [k cid]
  (shell/fragment cid {:dev? (halos? k)
                       :base-path (:base-path k)
                       :root-selector (:root-selector k)
                       :csrf-token (conversation-csrf-token k cid)}))

(defn- first-elements-html
  "Pick the HTML body of the first `:elements`-kind fragment in `frags`,
  or nil.  The boot fragment a freshly-instantiated root produces lands
  with `:selector #root, :patch-mode :inner`, so its body is exactly
  what should live inside the shell's `<div id=\"root\">`."
  [frags]
  (some (fn [f]
          (when (= :elements (:fragment/kind f))
            (:fragment/html f)))
        frags))

(defn rendered-shell-for!
  "Mint a conversation for `root-id`, boot it server-side, and return a
  Hiccup shell whose `#root` already contains the rendered first
  paint.

  Pairs with [[shell-for]] for cases where the host needs a readable
  GET response (static `/about` pages, SEO-visible content, no-JS
  fallbacks) instead of an empty `<div id=\"root\">` that fills in
  over the SSE connection.

  Returns `{:cid <cid> :shell <hiccup>}`.  The shell carries the same
  `data-init` that opens the SSE stream, so once the browser
  connects the conversation is fully interactive — but the initial
  HTML is already there at first paint.

  Marks the conversation `:conv/server-rendered? true`; the SSE
  handler reads this flag and skips the resume-render that path 2
  would otherwise fire (which would re-emit the same HTML and run
  `:wakeup` hooks meant for crash-resume, not for first attach).
  The flag is cleared on the first SSE attach, so subsequent
  reattaches (a network blip, a hot reload) behave like the normal
  restore path.

  Limitations: if the root component's `:start` emits more than one
  visible fragment (e.g. a `call-in-slot` during boot), only the
  primary `#root inner` fragment is inlined; the extras would be
  pushed over SSE on attach, the same way they are today."
  ([k root-id request]
   (rendered-shell-for! k root-id {} request))
  ([k root-id init-args request]
   (let [cid     (mint-conversation! k root-id init-args request)
         pending (pending-root k cid)
         [_conv frags]
         (apply-conv! k cid
           (fn [c]
             (let [[c' fs] (pure/run-effects c (pure/boot pending))]
               [(assoc c' :conv/server-rendered? true) fs])))
         html    (or (first-elements-html frags) "")]
     {:cid   cid
      :shell (shell/rendered-fragment cid html
                                      {:dev?          (halos? k)
                                       :base-path     (:base-path k)
                                       :root-selector (:root-selector k)
                                       :csrf-token    (conversation-csrf-token k cid)})})))

(defn head-tags
  "Return Hiccup head nodes required by [[shell-for]] for kernel `k`."
  [k]
  (shell/head-tags {:dev? (halos? k)
                    :ui-css? (ui-css? k)
                    :base-css (:base-css k)
                    :css-layer-order (:css-layer-order k)
                    :eager-scripts (:eager-scripts k)
                    :base-path (:base-path k)
                    :root-selector (:root-selector k)}))

;; ---------------------------------------------------------------------------
;; Per-kernel timers and pub/sub
;; ---------------------------------------------------------------------------

(def ^:private no-payload ::no-payload)

(defn- route-event->event-map [iid route-event]
  (let [{:keys [event payload]}
        (if (vector? route-event)
          (let [[event & payloads] route-event]
            {:event event
             :payload (case (count payloads)
                        0 no-payload
                        1 (first payloads)
                        (vec payloads))})
          {:event route-event
           :payload no-payload})]
    (cond-> {:instance-id iid
             :event       event
             :signals     {}}
      (not= no-payload payload) (assoc :payload payload))))

(defn- forget-timer! [k cid f]
  (swap! (:!timers k) update cid
         (fn [fs]
           (let [fs' (disj (or fs #{}) f)]
             (when (seq fs') fs'))))
  nil)

(defn schedule-event!
  "Schedule a future event for a cid/iid within kernel `k`."
  [k {:keys [cid instance-id delay-ms event]}]
  (let [!self (atom nil)
        fut   (future
                (try
                  (Thread/sleep (max 0 (long delay-ms)))
                  (dispatch! k cid (route-event->event-map instance-id event))
                  (catch InterruptedException _ nil)
                  (catch Throwable t
                    (binding [*out* *err*]
                      (println "dev.zeko.stube.runtime: scheduled event threw —" (ex-message t))))
                  (finally
                    (when-let [self @!self]
                      (forget-timer! k cid self)))))]
    (reset! !self fut)
    (swap! (:!timers k) update cid (fnil conj #{}) fut)
    fut))

(defn dispatch-to!
  "Asynchronously deliver `event` to `target-iid` in conversation `cid`
  under kernel `k`.  Fired by the `:dispatch-to` effect; runs on a
  background future so the current handler can complete first.  The
  matching event is dropped (the standard stale-event path) if the
  target instance is gone by the time the future runs."
  [k {:keys [cid target-iid event]}]
  (future
    (try
      (dispatch! k cid (route-event->event-map target-iid event))
      (catch Throwable t
        (binding [*out* *err*]
          (println "dev.zeko.stube.runtime: dispatch-to threw —" (ex-message t)))))))

(defn subscribe!
  "Subscribe cid/iid to `topic` within kernel `k`."
  [k {:keys [cid instance-id topic event]}]
  (swap! (:!subscriptions k) assoc-in [topic [cid instance-id]] event)
  nil)

(defn unsubscribe!
  "Remove one cid/iid subscription.  If `topic` is nil, remove all of
  the instance's subscriptions."
  [k {:keys [cid instance-id topic]}]
  (let [sub-key [cid instance-id]]
    (swap! (:!subscriptions k)
           (fn [topics]
             (into {}
                   (keep (fn [[t subscribers]]
                           (let [subscribers' (if (or (nil? topic) (= topic t))
                                                (dissoc subscribers sub-key)
                                                subscribers)]
                             (when (seq subscribers')
                               [t subscribers']))))
                   topics))))
  nil)

(defn subscriptions [k]
  @(:!subscriptions k))

(defn- published-event-map [iid route-event payload]
  {:instance-id iid
   :event       (if (vector? route-event) (first route-event) route-event)
   :payload     payload
   :signals     {}})

(defn publish!
  "Asynchronously deliver `msg` to every live subscriber of `topic` in
  kernel `k`."
  [k topic msg]
  (let [targets (get @(:!subscriptions k) topic)]
    (doseq [[[cid iid] route-event] targets]
      (future
        (dispatch! k cid (published-event-map iid route-event msg))))
    (count targets)))

(defn publish-local!
  "Like [[publish!]], but only delivers to subscribers in conversation
  `cid`.  Use this for parent/child or sibling channels that must
  stay within one browser tab; other conversations' subscribers on
  the same topic do not see the message.

  Returns the number of subscribers targeted."
  [k cid topic msg]
  (let [targets (->> (get @(:!subscriptions k) topic)
                     (filter (fn [[[sub-cid _iid] _route]] (= sub-cid cid))))]
    (doseq [[[c iid] route-event] targets]
      (future
        (dispatch! k c (published-event-map iid route-event msg))))
    (count targets)))

;; ---------------------------------------------------------------------------
;; Dev tooling helpers
;; ---------------------------------------------------------------------------

(defn enable-halos! [k cid]
  (when (and (halos? k) (conversation k cid))
    (swap-conv! k cid (fn [c] [(assoc c :conv/halos? true) []]))
    nil))

(defn enable-halos-and-redraw! [k cid]
  (when (and (halos? k) (conversation k cid))
    (let [[_conv' frags]
          (with-kernel-bindings
            k cid
            #(swap-conv! k cid (fn [c]
                                 (pure/redraw-top
                                   (assoc c :conv/halos? true)))))]
      (when-let [sse-gen (sse k cid)]
        (push-fragments! sse-gen frags))
      :enabled)))

;; ---------------------------------------------------------------------------
;; Pure replay against a kernel configuration
;; ---------------------------------------------------------------------------

(defn replay-with
  "Purely replay `events` against a fresh conversation rooted at
  `root-id`, using `k`'s render configuration but mutating no runtime
  state.  See [[dev.zeko.stube.embed/replay-with]] for the public-facing
  forwarder."
  [k root-id events]
  (let [ctx (context-for k nil)
        c0  (cond-> (conv/new-conversation)
              (some? ctx) (assoc :conv/context ctx))]
    (binding [render/*base-path* (:base-path k)
              render/*root-selector* (:root-selector k)
              pure/*run-io!* nil]
      (let [[booted boot-frags]
            (with-render-bindings
              k (:conv/id c0)
              #(pure/run-effects c0 (pure/boot root-id)))]
        (reduce (fn [[c frags] event]
                  (let [[c' more] (with-render-bindings
                                    k (:conv/id c)
                                    #(pure/dispatch c (conv/replay-event c event)))]
                    [c' (into frags more)]))
                [booted (vec boot-frags)]
                events)))))

(defn- shutdown-stop-iids
  "Order in which `:stop` should fire across a conversation's live
  instances during shutdown: top-of-stack first, children before their
  own frame.  Pre-existing `:end` handling uses parent-first
  pop-style; here the issue spec asks for children-before-parents, so
  the per-frame descendant list is reversed before concatenating."
  [conv]
  (vec
    (mapcat (fn [frame-iid]
              ;; Shutdown destroys everything — include previous-
              ;; chain instances so their `:stop` hooks fire too.
              (rseq (conv/subtree-ids conv frame-iid)))
            (rseq (or (:conv/stack conv) [])))))

(defn- run-shutdown-stop-hooks! [k]
  ;; Run :stop through apply-conv! so any patches it emits go out over
  ;; SSE before the :close fragment.  Do NOT set :conv/ended? here:
  ;; apply-conv!'s end-conversation! path would `delete!` from the
  ;; store, defeating the final flush-store! step.
  (doseq [[cid _conv] @(:!conversations k)]
    (try
      (apply-conv! k cid
        (fn [c]
          (let [iids (shutdown-stop-iids c)]
            (if (seq iids)
              (lc/run-stop-hooks pure/run-effects c iids)
              [c []]))))
      (catch Throwable t
        (binding [*out* *err*]
          (println "stube halt!: :stop hook for" cid "threw —" (ex-message t)))))))

(defn- flush-store! [k]
  (doseq [[_cid conv] @(:!conversations k)]
    (try
      (store/save! (:store k) conv)
      (catch Throwable t
        (binding [*out* *err*]
          (println "stube halt!: final save! threw —" (ex-message t)))))))

(defn halt!
  "Drain a kernel.  Sequence:

    1. Mark the kernel as shutting down so HTTP adapters can refuse
       new conversation mints.
    2. Cancel pending scheduled events.
    3. Run `:stop` hooks for every live instance (children before
       their frame, top stack frame first).
    4. Drain open SSE streams with a final `:close` fragment.
    5. Flush the store with one last `save!` per conversation.
    6. Clear per-kernel runtime registries.

  Returns nil.  Idempotent: subsequent calls on the same kernel are
  cheap no-ops."
  [k]
  (when (compare-and-set! (:!shutting-down? k) false true)
    (doseq [cid (keys @(:!timers k))]
      (cancel-timers! k cid))
    (doseq [cid (keys @(:!sse-keepalive k))]
      (stop-keepalive! k cid))
    (run-shutdown-stop-hooks! k)
    (doseq [[_cid sse-gen] @(:!sse-sessions k)]
      (try
        (push-fragments! sse-gen [f/close])
        (catch Throwable _ nil)))
    (flush-store! k)
    (reset! (:!sse-sessions k) {})
    (reset! (:!sse-keepalive k) {})
    (reset! (:!pending-roots k) {})
    (reset! (:!subscriptions k) {})
    (reset! (:!cid-locks k) {})
    (reset! (:!answer-error-warned k) #{})
    (reset! (:!conversations k) {}))
  nil)

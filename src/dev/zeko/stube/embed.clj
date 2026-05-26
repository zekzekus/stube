(ns dev.zeko.stube.embed
  "Embeddable runtime API for stube.

  This is the namespace host applications reach for when they want to
  drop stube into an existing Ring app, Integrant system, or test
  harness.  Everything here delegates to [[dev.zeko.stube.runtime]]
  through `requiring-resolve` so the pure kernel value-language in
  [[dev.zeko.stube.kernel]] does not pull the mutable runtime in at
  load time.

  Reading guide
  -------------

  A host typically uses three or four functions:

      (def k (embed/make-kernel {:store … :base-path \"/app\"}))
      (def cid (embed/mint-conversation! k :flow/root init-args request))
      (embed/shell-for k cid)        ; → Hiccup nodes for `<body>`
      (embed/head-tags k)             ; → Hiccup nodes for `<head>`
      (embed/dispatch! k cid event)   ; programmatic event injection
      (embed/halt! k)                 ; graceful shutdown

  Component code never reaches into this namespace.  Component authors
  stay inside [[dev.zeko.stube.core]] (`s/...`), where state is implicit
  and the active kernel is bound by the runtime around each dispatch.

  Everything else here — `^:no-doc` helpers, dynamic vars in
  [[dev.zeko.stube.kernel]] — is plumbing.  If you're a host embedder
  and you find yourself reaching past the documented surface, that is
  usually a sign the documented surface is missing something; open an
  issue rather than wiring against private helpers."
  (:refer-clojure :exclude []))

(defn- ^:no-doc runtime-var [sym]
  (or (requiring-resolve sym)
      (throw (ex-info "Unable to resolve stube runtime var" {:var sym}))))

;; ---------------------------------------------------------------------------
;; Public embedder surface
;; ---------------------------------------------------------------------------

(defn make-kernel
  "Create an embeddable stube runtime instance.  See
  [[dev.zeko.stube.runtime/make-kernel]] for the supported option set."
  ([]
   ((runtime-var 'dev.zeko.stube.runtime/make-kernel)))
  ([opts]
   ((runtime-var 'dev.zeko.stube.runtime/make-kernel) opts)))

(defn mint-conversation!
  "Register a conversation in `k` and return its cid."
  ([k root-id request]
   ((runtime-var 'dev.zeko.stube.runtime/mint-conversation!) k root-id request))
  ([k root-id init-args request]
   ((runtime-var 'dev.zeko.stube.runtime/mint-conversation!) k root-id init-args request)))

(defn shell-for
  "Return an embeddable Hiccup shell fragment for conversation `cid`."
  [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/shell-for) k cid))

(defn head-tags
  "Return Hiccup nodes for the assets required by [[shell-for]].  Host
  pages should include these in `<head>`: optional stock CSS,
  preserve.js, Datastar, and optional halos tooling."
  [k]
  ((runtime-var 'dev.zeko.stube.runtime/head-tags) k))

(defn dispatch!
  "Dispatch an event into live conversation `cid` in runtime `k` and
  return the produced fragments."
  [k cid event]
  ((runtime-var 'dev.zeko.stube.runtime/dispatch!) k cid event))

(defn replay-with
  "Purely replay `events` against `root-id` using runtime `k`'s render
  configuration.  Runtime state is not mutated.

  Differs in shape from [[dev.zeko.stube.core/replay]], which does not
  take a kernel.  Use this one when you want the replay to honour the
  same base-path / context the kernel produces in
  production; the bare `core/replay` is for unit-tests of components
  whose render output doesn't depend on those bindings."
  [k root-id events]
  ((runtime-var 'dev.zeko.stube.runtime/replay-with) k root-id events))

(defn halt!
  "Close open SSE streams and clear runtime registries for `k`."
  [k]
  ((runtime-var 'dev.zeko.stube.runtime/halt!) k))

(defn shutting-down?
  "True after [[halt!]] has begun draining `k`.  HTTP adapters should
  refuse new conversation mints (typically 503) while this is true."
  [k]
  ((runtime-var 'dev.zeko.stube.runtime/shutting-down?) k))

(defn publish!
  "Publish `msg` to every live instance subscribed to `topic` in
  runtime kernel `k`.  Use this from host code outside component
  dispatch; component code can call [[dev.zeko.stube.core/publish!]]."
  [k topic msg]
  ((runtime-var 'dev.zeko.stube.runtime/publish!) k topic msg))

;; ---------------------------------------------------------------------------
;; Internal plumbing — exposed for adapters, not application code
;; ---------------------------------------------------------------------------
;;
;; The `^:no-doc` helpers below are the surface the HTTP adapter and
;; the standalone server use to drive the runtime.  Host applications
;; that just want to embed stube in a Ring pipeline should not need
;; them.

(defn ^:no-doc create-conversation! [k root-id owner-token]
  ((runtime-var 'dev.zeko.stube.runtime/create-conversation!) k root-id owner-token))

(defn ^:no-doc ensure-session [k request]
  ((runtime-var 'dev.zeko.stube.runtime/ensure-session) k request))

(defn ^:no-doc pending-root [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/pending-root) k cid))

(defn ^:no-doc conversation [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/conversation) k cid))

(defn ^:no-doc active-conversations [k]
  ((runtime-var 'dev.zeko.stube.runtime/active-conversations) k))

(defn ^:no-doc swap-conv! [k cid f]
  ((runtime-var 'dev.zeko.stube.runtime/swap-conv!) k cid f))

(defn ^:no-doc end-conversation! [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/end-conversation!) k cid))

(defn ^:no-doc end! [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/end!) k cid))

(defn ^:no-doc reap! [k ttl]
  ((runtime-var 'dev.zeko.stube.runtime/reap!) k ttl))

(defn ^:no-doc register-sse! [k cid sse-gen]
  ((runtime-var 'dev.zeko.stube.runtime/register-sse!) k cid sse-gen))

(defn ^:no-doc unregister-sse! [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/unregister-sse!) k cid))

(defn ^:no-doc sse [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/sse) k cid))

(defn ^:no-doc run-effects! [k cid effects]
  ((runtime-var 'dev.zeko.stube.runtime/run-effects!) k cid effects))

(defn ^:no-doc apply-conv! [k cid f]
  ((runtime-var 'dev.zeko.stube.runtime/apply-conv!) k cid f))

(defn ^:no-doc schedule-event! [k event]
  ((runtime-var 'dev.zeko.stube.runtime/schedule-event!) k event))

(defn ^:no-doc subscribe! [k sub]
  ((runtime-var 'dev.zeko.stube.runtime/subscribe!) k sub))

(defn ^:no-doc unsubscribe! [k sub]
  ((runtime-var 'dev.zeko.stube.runtime/unsubscribe!) k sub))

(defn ^:no-doc subscriptions [k]
  ((runtime-var 'dev.zeko.stube.runtime/subscriptions) k))

(defn ^:no-doc authorized? [k request cid]
  ((runtime-var 'dev.zeko.stube.runtime/authorized?) k request cid))

(defn ^:no-doc current-store [k]
  ((runtime-var 'dev.zeko.stube.runtime/current-store) k))

(defn ^:no-doc with-kernel-bindings [k cid f]
  ((runtime-var 'dev.zeko.stube.runtime/with-kernel-bindings) k cid f))

(defn ^:no-doc enable-halos! [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/enable-halos!) k cid))

(defn ^:no-doc enable-halos-and-redraw! [k cid]
  ((runtime-var 'dev.zeko.stube.runtime/enable-halos-and-redraw!) k cid))

(defn ^:no-doc ui-css? [k]
  ((runtime-var 'dev.zeko.stube.runtime/ui-css?) k))

(defn ^:no-doc halos? [k]
  ((runtime-var 'dev.zeko.stube.runtime/halos?) k))

(defn ^:no-doc base-path [k]
  ((runtime-var 'dev.zeko.stube.runtime/base-path) k))

(defn ^:no-doc root-selector [k]
  ((runtime-var 'dev.zeko.stube.runtime/root-selector) k))

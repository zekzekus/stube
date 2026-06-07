(ns dev.zeko.stube.embed
  "Embeddable runtime API for stube.

  This is the namespace host applications reach for when they want to
  drop stube into an existing Ring app, Integrant system, or test
  harness.  Every fn here is a thin facade over [[dev.zeko.stube.runtime]];
  the indirection used to go through `requiring-resolve` for a load-order
  concern that no longer applies, see ADR 0006.

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

  Adapters (`http.clj`, `halos/http.clj`, `server.clj`) drive the
  runtime through [[dev.zeko.stube.runtime]] directly — `embed` is the
  *host* surface, not the adapter surface."
  (:require [dev.zeko.stube.runtime :as rt]))

(defn make-kernel
  "Create an embeddable stube runtime instance.  See
  [[dev.zeko.stube.runtime/make-kernel]] for the supported option set."
  ([]     (rt/make-kernel))
  ([opts] (rt/make-kernel opts)))

(defn mint-conversation!
  "Register a conversation in `k` and return its cid."
  ([k root-id request]
   (rt/mint-conversation! k root-id request))
  ([k root-id init-args request]
   (rt/mint-conversation! k root-id init-args request)))

(defn shell-for
  "Return an embeddable Hiccup shell fragment for conversation `cid`."
  [k cid]
  (rt/shell-for k cid))

(defn rendered-shell-for!
  "Mint a conversation, boot it server-side, and return both the cid
  and a Hiccup shell whose `#root` placeholder already contains the
  rendered first paint.

  Use this for routes that need a readable GET response — static
  `/about` pages, SEO-visible content, no-JS fallbacks — instead of
  the empty `<div id=\"root\">` [[shell-for]] returns.  The shell
  carries the same `data-init` that opens the SSE stream, so once
  the browser connects the conversation is fully interactive.

  Returns `{:cid <cid> :shell <hiccup>}`.

  See [[dev.zeko.stube.runtime/rendered-shell-for!]] for the contract
  details and limitations."
  ([k root-id request]
   (rt/rendered-shell-for! k root-id request))
  ([k root-id init-args request]
   (rt/rendered-shell-for! k root-id init-args request)))

(defn head-tags
  "Return Hiccup nodes for the assets required by [[shell-for]].  Host
  pages should include these in `<head>`: optional stock CSS,
  preserve.js, Datastar, and optional halos tooling.

  **Renderer constraint.**  The returned tree carries chassis
  `RawString` markers around `<script>` / `<style>` bodies (e.g. for
  `:eager-scripts` and inline `:styles`) so that quotes and other
  syntax inside the body aren't HTML-escaped.  Rendering the tree
  through chassis emits the bodies verbatim; rendering through any
  other Hiccup-shaped renderer (hiccup2, rum, reagent SSR, …) will fall
  back to the wrapper's `toString` and then HTML-escape it, silently
  breaking inline scripts.

  Hosts using a non-chassis renderer must re-wrap the chassis
  `RawString` instances in the renderer's own raw primitive before
  emitting — pass the tree through [[rewrap-raw]] with your renderer's
  raw constructor (e.g. `hiccup2.core/raw`) instead of hand-rolling a
  walker."
  [k]
  (rt/head-tags k))

(defn chassis-raw?
  "True when `x` is a chassis `RawString` — the marker stube wraps inline
  `<script>` / `<style>` bodies in inside [[head-tags]] / [[shell-for]] /
  [[rendered-shell-for!]] output.  Hosts running their own Idiomorph or
  SSR walker can use this to detect bodies that must be emitted verbatim."
  [x]
  (instance? dev.onionpancakes.chassis.core.RawString x))

(defn rewrap-raw
  "Re-wrap every chassis `RawString` in a [[head-tags]] / [[shell-for]] /
  [[rendered-shell-for!]] Hiccup tree using `raw-fn`, your renderer's own
  raw-string primitive, leaving the rest of the tree untouched.

  This is the one boundary a **non-chassis** embedder (hiccup2, rum,
  reagent SSR) must bridge: those renderers don't recognise the chassis
  marker and would HTML-escape inline script/style bodies, so `\"…\"`
  arrives as `&quot;…` and the scripts fail to parse.  Under chassis
  (`start!` or the stock shell) nothing needs re-wrapping and you never
  call this.

  hiccup2:

      (require '[hiccup2.core :as h])
      (into [:head [:title \"Host app\"]]
            (embed/rewrap-raw h/raw (embed/head-tags kernel)))

  rum / reagent: pass that renderer's raw wrapper as `raw-fn`.  Replaces
  the hand-rolled `chassis->hiccup-raw` walker hosts used to copy out of
  the README."
  [raw-fn node]
  (cond
    (chassis-raw? node) (raw-fn (str node))
    (vector? node)      (mapv #(rewrap-raw raw-fn %) node)
    (sequential? node)  (map #(rewrap-raw raw-fn %) node)
    :else               node))

(defn dispatch!
  "Dispatch an event into live conversation `cid` in runtime `k` and
  return the produced fragments."
  [k cid event]
  (rt/dispatch! k cid event))

(defn replay-with
  "Purely replay `events` against `root-id` using runtime `k`'s render
  configuration.  Runtime state is not mutated.

  Differs in shape from [[dev.zeko.stube.core/replay]], which does not
  take a kernel.  Use this one when you want the replay to honour the
  same base-path / context the kernel produces in
  production; the bare `core/replay` is for unit-tests of components
  whose render output doesn't depend on those bindings."
  [k root-id events]
  (rt/replay-with k root-id events))

(defn halt!
  "Close open SSE streams and clear runtime registries for `k`."
  [k]
  (rt/halt! k))

(defn shutting-down?
  "True after [[halt!]] has begun draining `k`.  HTTP adapters should
  refuse new conversation mints (typically 503) while this is true."
  [k]
  (rt/shutting-down? k))

(defn publish!
  "Publish `msg` to every live instance subscribed to `topic` in
  runtime kernel `k`.  Use this from host code outside component
  dispatch; component code can call [[dev.zeko.stube.core/publish!]]."
  [k topic msg]
  (rt/publish! k topic msg))

(defn publish-local!
  "Like [[publish!]] but only delivers to subscribers in conversation
  `cid`.  Use this from host code that already has a cid in hand
  (e.g. after [[mint-conversation!]]) and needs to keep a topic from
  leaking across browser tabs / users.  Component code can call
  [[dev.zeko.stube.core/publish-local!]] without naming the cid —
  the runtime resolves it from the active conversation."
  [k cid topic msg]
  (rt/publish-local! k cid topic msg))

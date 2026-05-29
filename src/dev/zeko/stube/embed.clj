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
  emitting — e.g. a small walker that turns
  `dev.onionpancakes.chassis.core.RawString` into `hiccup2.core/raw`."
  [k]
  (rt/head-tags k))

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

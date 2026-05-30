# Changelog

Big-rock changes to stube. Released versions may batch more than one
development entry.

## Unreleased

Driven by the fifth wave of kasten post-migration notes
(`kasten/stube_notes.md`, §"Notes for Stube from the Kasten
deep-adoption work").

- **`s/child-iid`** (new). `(s/child-iid self :slot/search)` returns
  the iid of the child instance currently mounted under the named
  slot, or nil when the slot is unknown. Same data as
  `(get-in self [:instance/children :slot/search])` — the helper just
  documents the stable contract so callers don't have to reach into
  framework-managed `:instance/*` keys directly. Pairs with the new
  `s/dispatch-to` for parent→child notifications by iid.

- **`s/conversation-id`** (new). Returns the id of the conversation
  currently being dispatched or rendered, or `nil` outside a runtime
  binding. Reads the same dynamic var the runtime already bound for
  URL helpers; component code no longer has to thread the cid through
  `:children` args when it needs to namespace pub/sub topics or asset
  paths by conversation. Pairs with the new `s/publish-local!` for
  intra-conversation messaging without leaking parent iids into topic
  vocabulary.

- **`s/publish-local!`** (new). Like `s/publish!` but only delivers
  to subscribers in the *current conversation* — every other
  conversation's subscribers on the same topic stay silent. The
  matching `embed/publish-local!` accepts an explicit `cid` for host
  code that already has one in hand (e.g. just after
  `mint-conversation!`). Closes the inter-tab-isolation gap that
  forced hosts to bake the parent's iid into topic vocabulary
  (`[:my-topic parent-iid]`) just to keep pub/sub from leaking across
  browser tabs.

- **`s/dispatch-to`** (new effect). `(s/dispatch-to target route-event)`
  schedules an asynchronous dispatch of `route-event` to `target`
  (an instance map or iid string) in the same conversation. The
  event lands on the target's `:handle` exactly as if a button wired
  with `(s/on-target target :click :as route-event)` had been
  clicked. The kernel hands the work off to a background future so
  the current handler completes first and the per-cid lock stays
  non-reentrant; a stale target is dropped silently, same as a stale
  POST. Surfaces the kernel's existing `dispatch!` entry point as a
  proper effect so a child can do "close myself *and* tell the
  parent to open something" in one handler — without inventing a
  pub/sub topic per parent/child pair or chaining a second POST in
  `data-on:click`.

- **`set-keyed-children` learns `:rerender-parent?`.** New optional
  3-arity `(s/set-keyed-children slot pairs {:rerender-parent? true})`
  asks the kernel to render the parent frame on top of the reconcile,
  with the just-updated `:instance/keyed-slots` in scope. Without
  this, the parent's `:render` reads stale slot state (the update
  lands during the effect fold, not before it) and the kernel's
  `rendered-output?` check skips the auto-render because per-child
  `:elements` fragments already counted as output. The opt is a
  no-op on the parent's first paint, so it is always safe to pass.
  Removes the workaround of emitting hand-rolled `(s/patch [:div
  …])` fragments next to `set-keyed-children` for every region of
  the parent that depends on the reconciled set.

- **After-render recipe in `docs/api.md`** (docs).  Calls out
  Datastar's `data-effect` as the recommended substitute for an
  `:on-patched` / `:after-render` lifecycle hook, with `s/on-mount`
  / `s/behavior` / `s/execute-script` next to it as the other
  three answers to "after render, do X." The framework deliberately
  does not ship a separate render-completion callback; the
  three documented seams cover focus, third-party widget setup,
  signal-driven side effects, and one-off post-render glue from
  inside a handler.

- **`embed/rendered-shell-for!`** (new). Mints a conversation,
  boots it server-side, and returns `{:cid <cid> :shell <hiccup>}`
  whose `<div id="root">` already contains the rendered first
  paint. The shell still carries the `data-init` that opens the SSE
  stream, so once the browser connects the conversation is fully
  interactive — but the initial HTML is there at first paint,
  without waiting for the SSE attach. Use this for routes that
  need a readable GET response (static `/about` pages, SEO-visible
  content, no-JS fallbacks) instead of [[shell-for]]'s empty
  `#root` placeholder. The conversation is marked
  `:conv/server-rendered? true`; the SSE handler reads the flag,
  clears it, and skips the resume-render that the restore path
  would otherwise fire — so the browser never sees a "no-op"
  re-paint on first attach, and subsequent reattaches still behave
  like the normal restore path.

## 0.3.4

Driven by the fourth wave of kasten post-migration notes
(`kasten/stube_notes.md`, §"Open rough edges against Stube 0.3.3").
**0.3.3's `ctx.setSignal` fix was based on a misread of Datastar v1
and shipped a different no-op.** This release corrects the mechanism
and adds the browser-side round-trip smoke that would have caught
both 0.3.1 and 0.3.3.

- **`ctx.setSignal` / `ctx.patchSignals` actually reach the signal
  store now** (bugfix, again). The 0.3.3 implementation dispatched
  a `datastar-signal-patch` `CustomEvent` on `document`, described in
  the 0.3.3 changelog as "Datastar v1's documented external-write
  entry point." That was wrong: in Datastar v1.0.1 the event is
  *outbound* — Datastar fires it from inside its own signal setter so
  observers can react — and the only `addEventListener` for it is the
  hook that makes it a valid target for user-written
  `data-on:datastar-signal-patch="…"` expressions. Nothing on the
  receive side consumes our dispatches, so behavior writes still
  no-op'd. The bridge now writes through Datastar's *public* attribute
  surface instead: it looks up the element marked
  `data-stube-signal-mirror="<wire>"`, sets its `.value`, and
  dispatches a standard DOM `input` event. Datastar's own `data-bind`
  handler does the rest. No coupling to any Datastar-internal symbol;
  `data-bind` is the headline public attribute every Datastar app
  relies on.

- **`s/signal-mirror`** (new). Renders the hidden `data-bind` input
  the bridge looks up:

  ```clojure
  [:input (s/signal-mirror :edit-markdown)]
  ```

  Returns attrs for `<input type="hidden" data-bind:<wire>
  data-stube-signal-mirror="<wire>">` with the wire name resolved
  through the same casing rules as [`s/bind`](#) — kernel-level
  `:signal-case` plus per-call `{:case ...}` override. Behavior code
  doesn't change: it still calls `ctx.setSignal(name, value)`. The
  marker just gives the bridge an unambiguous target so it doesn't
  have to guess which DOM element a behavior wants to write through.

- **`ctx.signals.get` reads from the mirror's `.value`** when one is
  in scope, with a best-effort `globalThis.ds` fallback for hosts
  whose Datastar build exposes it. Same DOM source-of-truth for
  reads and writes — a behavior can round-trip its own signal
  through `set` then `get` with no Datastar runtime handle.

- **End-to-end Playwright smoke for `ctx.setSignal`** (new). A new
  `/signal-mirror` example pairs a tiny component with a behavior
  whose `mount` writes `'hello'`; `test-e2e/.../signal_mirror_test.clj`
  asserts the bound `<p data-text="$x">` reflects the value within a
  frame. This is the round-trip check the kasten notes explicitly
  called out as the missing safeguard; nothing in the unit suite
  could have caught the silent no-ops that shipped in 0.3.1 and
  0.3.3.

## 0.3.3

## 0.3.3

Driven by the third wave of kasten post-migration notes
(`kasten/stube_notes.md`, §"Open rough edges against Stube 0.3.2").

- **Behavior `ctx.setSignal` / `ctx.patchSignals` reach the signal
  store again** (bugfix). The 0.3.1 helpers assumed Datastar exposed
  its signal store on `globalThis.ds`. Datastar v1.0.1 exposes no
  such global, so every write silently no-op'd: the canonical
  "behavior owns a widget and mirrors its internal state into a bound
  signal" pattern (CodeMirror, Chart.js, drag-and-drop) called out by
  the 0.3.1 changelog never actually worked. The bridge now dispatches
  a `CustomEvent("datastar-signal-patch", {detail})` on `document` —
  Datastar v1's documented external-write entry point — and behaviors
  can drop the hidden-input-plus-`data-bind` workaround. Reads
  (`ctx.signals.get`) remain best-effort against `globalThis.ds`;
  behaviors that need the latest value should read it off the DOM or
  round-trip through `ctx.fetch`.

- **`:signal-case` on `make-kernel`.** `:kebab` (default — preserves
  existing wire shape) or `:camel`. Picks the casing every signal
  helper uses: `s/bind`, `s/local-bind`, the new `s/$` /
  `s/signal` / `s/signal-wire-name`. Hosts that use inline Datastar
  expressions (`data-on:input="$createSlug = ..."`) need `:camel`,
  because JS identifiers can't contain dashes; pure-Clojure hosts can
  keep `:kebab`. Per-call `{:case ...}` opts on the same helpers still
  win. Replaces the previous "framework picks for you" stance baked
  into `s/bind`'s hard-coded `__case.kebab`.

- **`s/$`, `s/signal`, `s/signal-wire-name`** (new). The casing-aware
  helpers that pair with `s/bind`: `(s/$ :create-title)` returns the
  Datastar `$ref` string in the active casing for use in inline
  expressions; `(s/signal event :edit-markdown)` reads a posted signal
  off the event in the same casing the wire uses, so the read side
  mirrors `s/bind` without each handler having to know which
  convention is active. `s/signal-wire-name` is the underlying
  translation, exposed for hosts that build wire keys by hand.

- **`s/bind` and `s/local-bind` accept `{:case ...}`** (new arity).
  Per-call override of the kernel-bound casing default.

- **`embed/head-tags` renderer constraint documented** (docs). The
  returned Hiccup tree carries chassis `RawString` markers around
  `<script>` / `<style>` bodies so quotes and JSON literals survive
  verbatim. Rendering through chassis (the documented integration) is
  unchanged; hosts using hiccup2 / rum / reagent SSR must re-wrap
  those instances in their renderer's own raw primitive or the
  bodies are HTML-escaped and inline scripts fail to parse. Spelled
  out in the docstrings of `embed/head-tags` and `shell/head-tags`.

## 0.3.2

- **`:eager-scripts` no longer HTML-escapes its body** (bugfix). The
  shell's `eager-script-block` wrapped the concatenated snippets in
  `[:script body]` without `chassis/raw`, so any `"` inside a JSON
  literal landed in the browser as `&quot;` and the `<script>` tag
  failed to parse with `Uncaught SyntaxError: Unexpected token '&'`.
  Bodies are now emitted via `chassis/raw`, matching the documented
  "emitted verbatim" contract. Pinned by `kasten/stube_notes.md`
  after the 0.3.1 migration.

## 0.3.1

Driven by the second wave of kasten post-migration notes
(`kasten/stube_notes.md`, §"Open rough edges against Stube 0.3.0").
Four rough edges land here; the fifth — a unified signal-naming /
binding toolkit — stays deferred in `todo.md §2` until a second host
hits the same shape.

- **`behaviors.js` base-path resolution no longer requires a host
  body attribute** (bugfix). The shipped bridge is loaded as
  `<script type="module">`, and inside a module IIFE
  `document.currentScript` is always null — so the previous attempt
  to read `data-stube-base-path` from the script tag silently fell
  through to an empty base-path on every non-standalone mount, and
  behavior modules 404'd unless the host stamped the attribute on
  `<body>` itself. The resolver now consults, in order:
  `import.meta.url` (always available in modules; the bridge is
  served from `<base>/behaviors.js`, so stripping that suffix
  recovers the base), then `<html>` / `<body>`
  `data-stube-base-path`, then any element carrying the attribute
  (the shell `<div>` does), then the empty string. The
  `data-stube-base-path` attribute on the script tag and on the
  shell `<div>` is retained for legacy fallback.

- **`:base-css` on `make-kernel` / `start!`.** A vector of
  stylesheet URLs that `head-tags` emits unconditionally, in order,
  before component-derived stylesheets. Use this when host CSS has
  to appear on routes that don't embed `head-tags` at all (e.g. a
  static `/about` page that shares a layered stylesheet with the
  stube-driven `/`). URLs are emitted verbatim; relative URLs
  resolve against the host page, absolute URLs pass through.

- **`:eager-scripts` on `make-kernel` / `start!`.** A vector of
  inline JS snippets that `head-tags` emits as one synchronous
  `<script>` block in `<head>` *before* any `type="module"` script.
  This is the blessed seam for seeding `window.<X>` namespaces that
  inline Datastar expressions
  (`data-on:input="window.X.bindSlugSync($value)"`) need available
  from frame 1, before the deferred ESM module graph finishes
  parsing. Snippets are emitted verbatim and concatenated; the host
  owns the contents.

- **Behavior `ctx` grows real signal writes and a `fetch` helper.**
  `ctx.signals` now exposes `get` / `set` / `patch` against
  Datastar's nested signal tree (dotted paths supported), and the
  ctx surfaces `ctx.setSignal(name, value)` and
  `ctx.patchSignals(map)` as direct aliases so behavior code does
  not have to reach into `ctx.signals` for the common write paths.
  `ctx.fetch(eventUrl, opts?)` posts to a stube event URL the same
  shape `s/on` does — pair it with `data-stube-arg-event-url` (or
  build the URL on the fly with `event-url` and pass it in) when a
  behavior wants to drive server state without a sibling form. The
  end result: behaviors that own a live widget (CodeMirror,
  Chart.js, drag-and-drop) can mirror their internal state into
  bound signals directly, retiring the hidden-input shim that
  previously bridged the two worlds.

Carried forward to `todo.md`: the signal-naming / binding helper
toolkit (`s/data-signals`, `s/$`, `s/signal` lookup,
`s/scoped-signal`). Kasten still hand-rolls the registry; the right
API surface remains design-heavy enough that one datapoint is not
enough to commit to a shape.

## 0.3.0

- **Client-side seam: behaviors, component-scoped CSS, file-convention
  assets.** Stube grows an opinion about where in-browser JS and CSS
  live and how they connect to a component. See
  [ADR 0007](docs/decisions/0007-client-side-seam.md) for the design
  rationale.

  - `s/root-attrs` now automatically emits
    `data-stube-component="ns/name"` and `class="stube-c-<ns>-<name>"`
    on every component root, derived from `:instance/type`. CSS
    selectors and behaviors can address every instance by its
    registered keyword; user-supplied `:class` is concatenated, never
    replaced. Instance maps without `:instance/type` (hand-rolled in
    tests) are unaffected.
  - **`s/behavior self behavior-id args`** attaches a client-side
    behavior to an element. Renders `data-stube-behavior="ns/name"`
    plus one `data-stube-arg-<k>` per arg. The shell loads a tiny
    `behaviors.js` bridge that sweeps the document on every
    `stube:patched`, lazy-imports the matching module from
    `<base>/behaviors/<ns>/<name>.js`, and drives a fixed lifecycle:
    `mount(el, ctx)` once, `patched(el, ctx)` on every later morph
    that left the element alive, `unmount(el, ctx)` on detach. `ctx`
    is `{el, args, basePath, signals}`. Pairs with `s/preserve` when
    the behavior owns DOM children outside the server's render tree.
    Behaviors `fetch` the URLs produced by `s/event-url` to drive
    server state — the canonical "client widget tells the server
    something happened" pattern, demonstrated in the new `/sketch`
    example.
  - **Per-component CSS file convention.**
    `resources/stube_styles/<ns>/<name>.css`, when present, is
    auto-linked by `head-tags`. Selectors target the auto-emitted
    `[data-stube-component="ns/name"]`.
  - **Inline `:styles "..."` on `defcomponent`.** Colocated CSS for
    small components: `&` is replaced with the component's
    `[data-stube-component]` selector at head-emit time; all chunks
    are concatenated into a single `<style>` block.
  - **`:modules ["foo/bar"]` on `defcomponent`** declares eager JS
    module dependencies served from
    `resources/stube_modules/foo/bar.js`. `head-tags` emits one
    deduped script per distinct entry across the whole registry.
  - **Asset routes** added under each kernel's base-path:
    `/<base>/styles/<ns>/<name>.css`, `/<base>/modules/<id>.js`,
    `/<base>/behaviors/<ns>/<name>.js`, `/<base>/behaviors.js`. Asset
    segments are restricted to `[A-Za-z0-9_-]`; traversal-shaped
    requests return 400 and never touch `io/resource`.
  - **New `/sketch` example** drives the whole seam end-to-end:
    `s/behavior` for canvas drawing, `s/preserve` for the live pixels,
    a per-component stylesheet, an inline `:styles` chip, a `:modules`
    entry that registers a global `c` keyboard shortcut, an
    `s/execute-script` snapshot button. Behavior posts back to a
    server event URL on every pointerup; the server-owned stroke
    counter morphs in the chip outside the preserved subtree.

## 0.2.1

- **cljdoc analysis fix.** Marked `dev.zeko.stube.kit` with `^:no-doc`
  so cljdoc-analyzer filters the namespace out before requiring it.
  The kit adapter pulls in `integrant.core`, which stube intentionally
  does not declare as a runtime dependency — without the marker the
  0.2.0 cljdoc build failed on `Could not locate integrant/core`. The
  kit-clj integration story still lives in the README; only the
  generated API page for this one namespace is omitted from cljdoc.

## 0.2.0

- **cljdoc.org publishing.** Added `doc/cljdoc.edn` so the API
  reference, tutorial, internals, rationale, changelog and ADRs land
  in the expected order on
  [cljdoc.org/d/dev.zeko/stube](https://cljdoc.org/d/dev.zeko/stube).
  README picks up Clojars + cljdoc badges and a one-line pointer to
  the rendered docs. The release script now POSTs to cljdoc's
  `request-build2` endpoint after the Clojars push so docs rebuild
  without a manual step. No library code changed.

## 0.1.8

Driven by the first big embedder's post-migration notes (kasten,
`kasten/stube_notes.md`). Four threads land here; the fifth — a
unified signal-naming/binding toolkit — is bigger and stays in
`todo.md` until a second host hits the same shape.

- **Event modifiers on `s/on` / `s/on-target`.** Both helpers accept
  an optional trailing `modifiers` map (or vector of pairs) that
  becomes Datastar event modifiers in the attribute name itself
  (`data-on:input__debounce.300ms`, `data-on:click__stop__prevent`).
  Values may be strings, numbers, keywords, or `true` for flag-only
  modifiers. Map entries are sorted by key name for deterministic
  output; vector form preserves caller order for cases like
  `__debounce.300ms.leading` where one Datastar modifier takes
  multiple positional parts. Previously hosts had to hand-roll the
  attribute keyword to get debounce/throttle on search boxes,
  typeaheads, and autosave fields.
- **Public `s/instance-id` + `s/on-target` accepts an instance map.**
  Pure rendering helpers no longer have to reach for `:instance/id`
  to call `on-target`. `(s/instance-id self)` is now the stable seam
  that returns the iid or throws; `s/on-target` and `s/event-url`
  accept either a bare iid string or an instance map. New
  `(s/on-parent self …)` is shorthand for the recurring
  child-renders-control-that-belongs-to-the-parent pattern —
  equivalent to `(s/on-target (:instance/parent self) …)` but reads
  as a stable API surface rather than reaching into the instance
  map. (Picks the structural name `on-parent` over the semantic
  `on-owner` to avoid collision with the existing
  `:conv/owner-token` security vocabulary.)
- **`stube:patched` browser lifecycle event.** The shipped
  `preserve.js` bridge now dispatches a `CustomEvent` on `document`
  after every successful Datastar `patch-elements` morph. Hosts that
  need to run after a patch lands (scroll/focus restoration, title
  measurement, third-party widget reflow, optimistic UI cleanup) can
  listen for `stube:patched` instead of inventing their own
  MutationObserver. `event.detail` carries `selector` and `mode`
  from the patch.
- **Asset URLs collapse the redundant `/stube/` segment** (pre-1.0
  breaking). With `:base-path "/stube"`, asset routes now live at
  `/stube/ui.css`, `/stube/preserve.js`, `/stube/halos.js`, and
  `/stube/halos/:cid/{panel,enable}` instead of doubling the segment
  (`/stube/stube/ui.css`). Standalone (empty base-path) gets the
  symmetric `/ui.css`, `/preserve.js`, `/halos.js`. Conversation
  endpoints (`/sse`, `/event`, `/back`, `/upload`) already lived at
  the kernel's base-path; this aligns the asset routes with the same
  rule. URL construction is centralised in `render.clj` and
  `adapter/ring.clj`, so generated `head-tags` and `shell-for` pick
  the new paths up automatically; hosts that hardcoded
  `/stube/stube/...` in their own templates need a trivial rewrite.

Carried forward to `todo.md`: a signal-naming/binding helper toolkit
(`s/data-signals`, `s/$`, `s/signal` lookup, `s/scoped-signal`) so
hosts don't rediscover Datastar's HTML-attribute case-collapse rules.
Tracked but not built: the kasten author flagged this as the biggest
remaining shared pattern, but the right API surface is design-heavy
enough to wait for a second concrete embedder.

## 0.1.7

- **Playwright-based e2e smoke suite.** New `:e2e` deps alias and
  `make e2e` target run a browser-driven sanity pass over the live
  examples catalogue. Twenty tests pin one stube primitive each:
  morph-by-id, `s/keyed-children`, `:call-in-slot`, `:url` projection,
  `s/decorate`, `s/preserve`, `:principal-fn` gating, error-frame /
  `s/answer-error`, `defflow`/`s/await`/wizard-back, dialogs,
  recursive renderers, structured event payloads, table sort,
  pagination, inactive-child preservation. The suite is gated to
  `make e2e` and `make release`; `make test` stays Clojure-only and
  fast. See `test-e2e/dev/zeko/stube/e2e/` for the harness.
- **`mint-conversation!` bugfix.** A fresh shell visit minted a
  session id for `Set-Cookie`, then ignored it and minted a *second*
  id inside `mint-conversation!`. The conversation ended up owned by
  the second id while the browser was told to send the first — every
  subsequent SSE GET hit the cross-session check and returned 403.
  Manual browser sessions papered over this by reusing a cookie from
  an earlier tab, but a fully fresh BrowserContext (or any new
  install) was broken. `shell-handler` now threads its sid through to
  a new 5-arity `mint-conversation!`. Pinned by
  `shell-set-cookie-matches-conv-owner-token` in `http_test`.
- **`examples/reading_list.clj` Close button.** The per-card Close
  button used `(s/on self :click :as [:close id])`, where `self` is
  the item — but `:reading/item` has no `:handle`, so the click was a
  no-op and the desk's `:close` resume never ran. Switched to
  `s/on-target` against `(:instance/parent self)` so the click POSTs
  to the desk that owns the handler. The e2e test for URL-driven
  restore + close now exercises the full round-trip.

## 0.1.6

- **R1 — refactor round 1** (road to 1.0): cleanup, docs, and a
  shape refresh around the embed / runtime / server seam. No
  user-visible behaviour change; the public surface stays where
  it was. See #28 for the umbrella plan.
  - **R1-01** (#29): removed the dead `dev.zeko.stube.routes`
    namespace and the stray test that exercised its private fn.
    The standalone server has long since used
    `dev.zeko.stube.adapter.ring/ring-handler`; the internals
    module map drops the line too.
  - **R1-02** (#30): cleared seven `clj-kondo` warnings (one in
    `src/`, six in `test/`). The genuine false positive — kondo
    can't see through `runtime/cid-lock` — gets an
    `#_:clj-kondo/ignore` with a one-line explanation; the rest
    are real cleanups.
  - **R1-03** (#31): added a `make lint` target and made
    `make test` depend on it. `clj-kondo` exits non-zero on any
    warning, so the standard pre-PR check now catches lint
    regressions before tests even run. `AGENTS.md` documents the
    workflow and the `#_:clj-kondo/ignore` escape hatch.
  - **R1-04** (#32): introduced a private `defalias` macro in
    `dev.zeko.stube.core` and rewrote every plain
    `(def ^{:doc "..."} foo target/foo)` re-export with it.
    `:arglists` now flows through, so `(doc s/answer)`,
    `(doc s/patch)`, `(doc s/end)` — and CIDER eldoc — show the
    target signature. clj-kondo learns the form via
    `:lint-as clj-kondo.lint-as/def-catch-all` so dependent
    namespaces still resolve `s/...` names.
  - **R1-11** (#39): new `core_test/every-public-name-has-doc-and-arglists`
    sweeps `ns-publics` of `dev.zeko.stube.core` and fails if any
    non-`:no-doc` var loses its docstring, or any function var
    loses `:arglists`. Locks the R1-04 invariant against
    regression.
  - **R1-08** (#36): hoisted the duplicated `replay-event` helper
    out of `core.clj` and `runtime.clj` into a single public
    `conversation/replay-event`. Both callers (`core/replay` and
    `runtime/replay-with`) now share the event-shape normalisation
    rule.
  - **R1-18** (#46): extracted `conversation/snapshot-for-dispatch`
    from inside `kernel/dispatch`. The `[:back]` carve-out (a
    handler walking history backwards must not have its own
    pre-state pushed onto that history first) now lives next to
    the conversation data it shapes; `kernel/dispatch` reads
    the snapshot decision in one line instead of ten.
  - **R1-17** (#45): `registry/register!` now throws `ex-info`
    when a `defcomponent` form declares both `:foo` and
    `:component/foo` for any lifecycle key (silent lift used to
    overwrite the long-form value). The registry ns docstring and
    `docs/api.md` document why lifecycle keys are a closed set
    while resume keys (`:on-foo`, `:on-error-foo`) pass through
    verbatim.
  - **R1-10** (#38): replaced the `subvec` + `apply hash-map`
    accessors in `effects/call-resume` and
    `effects/slot-call-resume` with positional destructure
    (Option A from the issue). The wire shape stays a vector and
    the kernel multimethod is unchanged; the per-effect
    allocation overhead in `call`/`call-in-slot` goes away.
  - **R1-13** (#41): moved the `answer-error` fallback warning's
    once-per-pair dedup from a JVM-global atom in `kernel.clj`
    onto the kernel value (`:!answer-error-warned`, reset on
    `halt!`). Two embedded kernels in the same JVM now each emit
    their own one-time message. Kernel-less paths (pure
    `s/dispatch` / `s/replay`) skip the warning entirely.
  - **R1-09** (#37): new `Dynamic bindings` section in
    `docs/internals.md` catalogues every `^:dynamic` var (15 of
    them across kernel/render/effects/errors/flow/dev) by where
    it is bound, where it is read, and what `nil` means. Also
    fixes the stale `render/*conv*` docstring (`frame/render-frame`
    is the binder, not the kernel).
  - **R1-12** (#40): new `load_direction_test` codifies the
    pure/impure split. Walks `ns-aliases` transitively from each
    pure namespace (`conversation`, `effects`, `fragments`,
    `kernel`, `frame`, `lifecycle`, `registry`, `render`, plus
    the `dev`/`errors`/`keyed` helpers they pull) and fails if
    any reach `runtime`, `server`, `http`, the adapters, or the
    kit glue.
  - **R1-15** (#43): replaced the U+2500 BOX DRAWINGS LIGHT
    HORIZONTAL character used as a section underline in several
    ns docstrings with plain ASCII hyphens. Renders the same in
    monospace terminals but no longer breaks in editors that
    don't auto-detect UTF-8 or in GitHub's plaintext diff view.
    `AGENTS.md` documents the convention.
  - **R1-14** (#42): spike-on-hold. The `:url` machinery
    (S-11) is wired directly into `kernel/dispatch` via
    `maybe-emit-url`; a hook list would generalise it, but a
    hook list with one consumer is harder to read than the
    straight call. Decision: not yet. `docs/internals.md` picks
    up a one-paragraph note next to the dispatch-path diagram so
    the seam is grep-able when a second consumer appears.
  - **R1-05** (#33): dropped the `requiring-resolve` indirection
    in `dev.zeko.stube.embed`. The namespace now contains only
    the ten documented public fns
    (`make-kernel`/`mint-conversation!`/`shell-for`/`head-tags`/
    `dispatch!`/`replay-with`/`halt!`/`shutting-down?`/`publish!`),
    each a thin direct delegate to `dev.zeko.stube.runtime`. The
    ~25 `^:no-doc` plumbing fns it used to expose for adapters
    have moved back to `runtime`; `http`, `halos/http`,
    `adapter/ring`, and `server` `:require` runtime directly.
  - **R1-06** (#34): narrowed `dev.zeko.stube.server` to
    lifecycle (`start!`, `stop!`, `mount!`, `unmount!`, `mounts`,
    `reset-state!`, the reaper) plus a small default-kernel
    convenience surface (`default-kernel`, `conversation`,
    `active-conversations`, `end!`, `inspect`, `publish!`). The
    ~20 wrapper fns that nothing outside the namespace needed
    are gone; the two consumer test files (`server_test`,
    `http_test`) switch to `rt/foo (server/default-kernel) …`.
  - **R1-07** (#35): new ADR
    `docs/decisions/0006-embed-as-direct-runtime-facade.md`
    records the decision behind R1-05 and R1-06. The original
    `requiring-resolve` choice was never written down; this
    ADR is the first written form, and the index table also
    picks up the missing 0005 entry along the way.
  - **R1-16** (#44): restructured the README into two clearly
    delimited paths — *Getting started* for downstream users
    (Clojars coordinate, the counter snippet, Datastar SDK
    pinning, Ring embedding, kit-clj, host widget integration)
    and *Running this repo* for contributors (`nix develop`,
    `clojure -M:examples`, the examples table, Datastar
    Inspector and `(s/inspect cid)` hints). Content was reordered,
    not rewritten.
- New `:example-ring` deps.edn alias runs the plain-Ring embedded
  example (`examples/dev/zeko/stube/examples/embedded_ring.clj`)
  via `clojure -M:example-ring` instead of needing a REPL.

## 0.1.5

- The dev/debug mode halos improved in terms of experience

## 0.1.4

- Deployment error forced this release to keep changelog consistent

## 0.1.3

- **Road to 1.0** (breaking, pre-1.0): trimmed pre-1.0 surface in
  preparation for stability.
  - Removed the `:emit-on-mount` colocated key; use `:start`
    directly. `:start` already covers the effect-only case
    (`(fn [self] [self effects])`), so the sugar layer was net
    cruft. Tutorial chapter and `reading_list.clj` updated. The
    S-12 CHANGELOG note below describes the original sugar.
  - Removed the dual URL route shape: the `:legacy` standalone
    paths (`/conv/:cid/sse`, `/conv/:cid/:iid/:event`, …) are gone;
    every kernel now uses the `:adapter` paths (`/sse/:cid`,
    `/event/:cid/:iid/:event`, …). `:route-style` is no longer
    an option to `s/start!`, `make-kernel`, or the shell.
  - Added `s/with-app` and `s/with-principal` macros so component
    tests no longer reach into `dev.zeko.stube.kernel/*current-*`
    directly. Docstrings, `docs/api.md`, `docs/tutorial.md`, and
    ADR 0004 updated to use them.

- **S-15**: `s/on-unmount` Hiccup helper for preserved hosts. Mirrors
  `s/on-mount`: returns a `data-stube-on-unmount` attribute carrying
  a synchronous JS expression with `el` bound to the host element.
  `preserve.js` grew a document-wide MutationObserver that fires the
  expression once when the host genuinely detaches — queueMicrotask
  defers the check so Idiomorph's detach+reattach swap dance can't
  double-fire. Logs to `console.error` on throws; never blocks the
  morph. CodeMirror/Chart.js/`<video>` integrations now have a real
  teardown path. README and `preserved_widget.clj` updated.
- **S-14**: `(s/answer-error ex)` + `:on-error-<key>` resume keys.
  Symmetric child→parent failure routing — the child catches its
  exception and emits `(s/answer-error ex)`; the parent declares
  `:on-error-saved` next to `:on-saved` and receives the exception
  verbatim. Three-tier fallback: explicit `:on-error-<key>` →
  `:on-<key>` with `[:error ex]` plus a one-time deprecation
  warning per cdef → the default error-frame banner. New ADR
  `0005-answer-error-and-resume.md`; new worked example
  `error_answer.clj`; `todo.md §2` entry removed.
- **S-13**: New `docs/api.md` section "Reading dependencies — `app`
  vs `context` vs `principal`" with a comparison table, decision
  tree by question, three common mistakes (notably reading
  `(s/context self)` from `:init`), and a worked migration moving
  the DB choice from `:app` to `:context-fn`. Cross-refs from the
  existing `s/app` / `s/context` / `s/principal` entries, from the
  `defcomponent` `:init` row, and from `make-kernel`'s opts list.
- **S-12**: Shareable-URL bootstrap recipe and `:emit-on-mount` sugar.
  Declaring `:emit-on-mount (fn [self] effects)` lifts to
  `:component/start` at registration; declaring both is a register-time
  error. New worked example `reading_list.clj` demonstrates the
  three-piece pattern (`:init-args-fn` → `:emit-on-mount` →
  `:url`) end-to-end. New tutorial chapter "Shareable views — URL as
  durable state" walks through the same flow. `docs/api.md`
  cross-refs from `mount!` and `keyed-children`.
- **S-11**: Root-component `:url` key for declarative URL sync. Returns
  `nil`, a string, or `[:replace|:push url]`; the kernel diffs against
  `:conv/last-url` after every dispatch and auto-emits a `[:history …]`
  effect on change. Explicit `(s/history …)` from the handler always
  wins. Only the root frame's `:url` applies. `:init-args-fn` on
  `mount!` pairs with this to read the URL back in on a fresh mount.
  `url_state_counter.clj` refactored to use the new form;
  `url_state_counter_manual.clj` preserves the hand-rolled version for
  comparison.

## 0.1.2

- Fix the `:call-in-slot` previous-chain leak surfaced by
  `kernel-property-test` during the 0.1.1 sweep. New
  `conversation/subtree-ids` walks `:instance/previous` chains
  alongside `:instance/children` and `:instance/keyed-slots`; the
  frame-destruction paths (`pop-top`, `:replace`, `:end`, root-frame
  `:answer`, keyed-child removal, runtime `halt!`) use it so
  previous-chain instances get their `:stop` hooks and are swept
  from `:conv/instances`. The narrow `descendant-ids` survives for
  paths where the previous gets restored (`answer-from-slot`,
  `mark-rendered`, history wakeup). Pinned by a focused regression in
  `embed-test` and by tightened structural assertions in
  `kernel-property-test`.

## 0.1.1

Road-to-1.0 sweep. See `todo.md` for the items deliberately deferred
past this release (composition spikes, popstate, extra HTTP routes,
the `:call-in-slot` previous-chain leak surfaced by the new property
test).

- Generative kernel test. New `kernel_property_test` uses test.check
  to walk random event sequences through `kernel/dispatch` against a
  stub registry, asserting after every step that (a) no throw,
  (b) `pr-str`/`read-string` round-trip yields an equal conversation,
  (c) every `:elements`/`:error` fragment that names an iid selector
  refers to an instance that exists post-dispatch, and (d) the call
  stack and `:instance/children` only reference live instances.
  The test surfaced a real leak: `:call-in-slot`'s
  `:instance/previous` chain is orphaned when the parent frame is
  replaced or ended before the slot child answers. Out of scope here;
  named and bounded by a comment in the test so it does not get
  forgotten.
- Tightened invariant test: examples may not reach into internal
  namespaces (`kernel`, `server`, `conversation`, `runtime`,
  `render`, `frame`, `fragments`, `http`, `lifecycle`, `effects`,
  `registry`). `embedded_ring.clj` stays the documented exception
  for `dev.zeko.stube.embed` as the host-embedder surface.
- Documentation: cross-process pub/sub is now called out as
  single-JVM-by-design in `docs/internals.md`, with a sketched
  `Publisher` protocol for the eventual seam if a real bus is ever
  wanted, plus a working-today recipe using `:app` as a bring-your-own
  bus. New `docs/decisions/` folder with four short ADRs covering
  resume-key naming, EDN-clean conversation state, the embed/call
  split, and the `:app` + `:principal-fn` contract.
- SSE heartbeat for reverse-proxy idle timeouts. Every kernel now runs
  a per-conversation keepalive thread that sends a `stube-keepalive`
  event (an SSE event-type Datastar ignores) every
  `:sse-keepalive-ms` milliseconds, default 15000. The heartbeat
  stops when the SSE channel unregisters or the kernel halts. Pass
  `nil` or `0` to disable. `docs/internals.md` carries an "SSE behind
  a reverse proxy" section with nginx, ALB, Caddy, and HAProxy knobs.
- `defflow` durability is now documented as a deliberate property,
  not a pending gap. A conversation containing a `defflow` is
  in-memory only by design (its cloroutine continuation is not
  EDN-serialisable). For long-running flows that must survive a
  restart, write the same shape as a hand-rolled task component with
  `:start` + named resume keys; the tutorial now shows the two side
  by side. The store's skip-on-defflow warning is more pointed about
  the workaround.
- Application-boundary primitives for embedders:
  - New `:app` option on `make-kernel` carries an opaque host value
    (typically a map of long-lived dependencies) that component code
    reads via `(s/app)`. The value is not serialised with the
    conversation; rebuild it from live JVM state on each
    `make-kernel` call.
  - New `:principal-fn` option is invoked once when a conversation is
    minted; its result is persisted on the conversation under
    `:conv/principal` and surfaced through `(s/principal)`. The
    principal is fixed for the life of the conversation — end and
    re-mint to change identity. There is no `set-principal!`
    operation by design.
  - Both options are accepted by `s/start!` for the standalone
    server. The `protected_counter` example now reads its principal
    via `(s/principal)` instead of carrying app-level login state on
    the conversation.
- Extracted `dev.zeko.stube.embed` as the documented host-embedder
  namespace. `dev.zeko.stube.kernel` is back to being just the pure
  effect fold (`step`, `run-effects`, `dispatch`, `boot`,
  `resume-top`, `redraw-top`). Internal callers, tests, examples, and
  docs have been updated. The §15.4 line-count invariant has been
  replaced by a structural one: the runtime stays organised around a
  single effect multimethod.
## 0.1.0

- API polish pass before 1.0:
  - `s/back` is now a zero-arity function `(s/back)` returning the
    `[:back]` effect, matching every other effect constructor. Call
    sites that used the bare value need a pair of parens.
  - `dev.zeko.stube.kernel/replay` is now `kernel/replay-with` so the
    kernel-aware host helper no longer collides in name with the
    kernel-less `core/replay` used by component-author tests.
  - `registry/register!` now lifts every colocated author key —
    `:start`, `:stop`, `:wakeup`, `:children` in addition to the
    previous `:init`/`:render`/`:handle`/`:keep`/`:doc`/`:state` — to
    its `:component/<name>` home. Component definitions registered via
    any entry point (`defcomponent`, `register-component!`,
    `decorate!`) are now uniform; the kernel reads them under a single
    namespace.
  - Updated the Seaside-todo menu example to use `s/on-target`
    against the parent iid instead of synthesising a fake parent
    instance map.
- Hardened stale-event handling: POSTs for missing instances inside a
  live conversation are now harmless `204` no-ops instead of ending the
  whole conversation; missing/ended conversations still surface the
  stale-page response.
- Tightened runtime isolation for embedders. Runtime state now lives on
  kernel values, shell/head helpers are public, and `s/publish!` routes
  to the active embedded kernel when called from component code.
- Made `:io` a runtime-interpreted effect. Pure `dispatch`/`replay`
  keep it inert unless a runtime hook is bound, preserving the kernel's
  value-oriented testability.
- Hardened keyed children: keyed metadata survives handler state
  replacement, children inherit conversation context, and changed embed
  args rebuild the child subtree while preserving the root iid.
- Added public cross-instance event targeting via `s/on-target`, with
  `s/event-url` documented as the low-level escape hatch.
- Made `s/call`, `s/become`, and `s/call-in-slot` accept existing embed
  specs directly, so stock helpers like `(s/confirm "?")` compose
  without reaching into internal effect constructors.
- Improved shell embedding: `stube/head-tags` now returns the stock CSS,
  preserve bridge, Datastar, and optional halos assets for the current
  kernel/base path; examples no longer reach into shell internals.
- Improved error targeting so first-render failures patch the shell root
  correctly instead of assuming the failing instance already exists in
  the DOM.
- Removed the unused legacy async registry namespace; timers, pub/sub,
  SSE sessions, pending roots, and shutdown state are owned by the
  embeddable runtime.
- Refreshed the public documentation set: README, tutorial, API
  reference, internals guide, historical design notes, roadmap, and this
  changelog now describe the current framework surface.

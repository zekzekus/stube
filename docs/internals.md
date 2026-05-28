# Internals

How stube is put together, from the moment a click leaves the browser
to the moment a new DOM patch lands. The [rationale](rationale.md)
covers *why* the framework looks the way it does; this page covers
*how* the ideas there compose into running code.

> The implementation is roughly 4,000 lines of Clojure, written
> hand-in-hand with LLM assistants. Everything below can be read in
> source form; this document is the map I would have wanted while
> writing it.

---

## The mental model in one paragraph

stube treats a single user's session against a mounted flow as a
**conversation** — a plain Clojure map. Inside the conversation lives
a **stack** of *frames* and a flat **instance map** keyed by instance
id. A frame is just the id of the topmost component. Each *instance*
is itself a map: framework‑managed keys under the `:instance/*`
namespace, user state at the top level. Handlers, lifecycle hooks and
flow continuations all operate on values and return a description of
what should happen next — the **effect language**. The kernel folds
those effects into the next conversation value, producing a sequence
of **fragments** (one per Datastar event). The HTTP layer pushes
fragments down the SSE stream; the browser morphs them into the DOM
by id.

That's the whole machine. Nothing magical happens between a
`(s/answer v)` and the `:on-foo` resume; the kernel literally looks
up the key.

---

## Module map

```
src/dev/zeko/stube/
  core.clj          ← the public API (re-exports + macros)
  registry.clj      ← {:component/id → cdef} atom
  conversation.clj  ← pure helpers over the conversation map
  effects.clj       ← constructors + accessors for the effect vocabulary
  fragments.clj     ← {:fragment/kind …} + the Datastar SSE translator
  frame.clj         ← render one frame; render-slot-overlay
  keyed.clj         ← keyed-child reconciliation and rendering
  lifecycle.clj     ← run :start / :stop / :wakeup
  kernel.clj        ← step / run-effects / dispatch (pure fold) plus
                       the stable embedder façade (make-kernel,
                       mint-conversation!, shell-for, head-tags,
                       dispatch!, replay-with, publish!, halt!) that
                       forwards into runtime via requiring-resolve
  runtime.clj       ← per-kernel mutable runtime state and hooks
  flow.clj          ← defflow macro, cloroutine glue
  render.clj        ← hiccup → HTML; data-on / data-bind helpers
  ui.clj            ← stock confirm / prompt / choose / info components
  store.clj         ← ConversationStore protocol + in-memory / file impls
  session.clj       ← session cookie + ownership token
  http.clj          ← ring handlers (shell, SSE, event, back, upload)
  adapter/ring.clj  ← Reitit route data / Ring handler for embedded kernels
  kit.clj           ← optional Integrant adapter
  shell.clj         ← the empty HTML page Datastar bootstraps from
  server.clj        ← standalone http-kit lifecycle + a thin default-kernel convenience surface
  errors.clj        ← local error banner fragments + :on-error hook
  halos*.clj        ← dev tool (overlay + inspector)
```

Three observations:

1. **The kernel does not require the server.** The pure fold half of
   `kernel.clj` works over plain values; the server is what drives it.
   That is why `(s/dispatch conv event)` and `(s/replay …)` work with
   no HTTP layer running. The embedder-façade in `embed.clj` is a thin
   public surface — ~10 functions, all documented — that delegates
   straight into `runtime.clj` via a normal `:require`; adapters
   (`http.clj`, `halos/http.clj`, the standalone `server.clj`) reach
   into runtime directly. See ADR 0006 for the rationale and the
   earlier `requiring-resolve` indirection it replaces. The pure/impure
   split is codified in
   `test/dev/zeko/stube/load_direction_test.clj`, which fails if any
   pure namespace transitively `:require`s the runtime, server, http,
   or adapter namespaces.
2. **`render.clj` is at the wire boundary but knows nothing about
   Datastar.** It produces HTML strings and Datastar *attribute*
   names; the SSE event types are all in `fragments.clj`.
3. **`fragments.clj` is the single boundary for *outgoing* SSE
   patches.** `http.clj` also touches the SDK (signal extraction and
   the http-kit SSE adapter) and `shell.clj` reads the Datastar CDN
   URL, but the kernel's fragment-emission path goes through
   `fragments.clj` alone. If stube ever wanted to ship over
   WebSockets instead of SSE, this file and `http.clj` are the ones
   that would change.

---

## The conversation, in detail

`dev.zeko.stube.conversation`. Shape:

```clojure
{:conv/id        "cv-019"
 :conv/instances {"ix-7e2" {…instance map…} …}
 :conv/stack     ["ix-7c1" "ix-7e2"]   ; bottom → top
 :conv/history   [previous-conv …]
 :conv/created   #inst "…"
 :conv/touched   #inst "…"}
```

- **`:conv/instances`** holds every live instance — those on the
  stack *and* those embedded under another instance via
  `:instance/children`. The stack only carries *frames*; embedded
  widgets are addressed through their parent.
- **`:conv/stack`** is the active call chain. Bottom is the root
  flow; top is whatever the user is currently interacting with.
- **`:conv/history`** is a vector of *previous full conversation
  values*, used by `[:back]`. Each snapshot has its own
  `:conv/history` stripped to avoid quadratic growth.
- **`:conv/touched`** is bumped on every dispatch; the optional
  reaper ends conversations whose timestamp is older than the
  configured TTL.

### Instance shape

```clojure
{:instance/id        "ix-7e2"
 :instance/type      :auth/login
 :instance/parent    "ix-7c1" | nil
 :instance/resume    :on-login | nil       ; where THIS frame's answer flows
 :instance/rendered? false                  ; toggled after first DOM patch
 :instance/children  {:slot/header "ix-…"} ; eager children from :children
 :instance/keyed-slots {:slot/cols {…}}     ; keyed-child framework state
 :instance/slot      :slot/main             ; iff this is a call-in-slot child
 :instance/previous  "ix-prev"              ; the displaced child to restore on answer
 …user state from (:component/init cdef)…}
```

User state lives **at the top level** of the instance map, not under
a `:state` key. Handlers therefore see one merged map. The kernel
calls `preserve-meta` after every handler return to make sure a
mis-typed handler can't clobber the `:instance/*` keys, including
framework-owned keyed-child metadata.

### Resume keys, the right way around

This bit is worth stating clearly because it's the single thing
`docs/archive/v2.md` got slightly wrong:

> The resume key lives on the **child** frame, not the parent.

When `(s/call (s/prompt "Name?") :on-name)` runs, the helper returns
an embed spec for `:ui/prompt`, then the kernel:

1. Instantiates `:ui/prompt` and stamps the new child instance with
   `:instance/resume :on-name`.
2. Pushes it.

When the child eventually emits `[:answer v]`, the kernel:

1. Reads the *child's* `:instance/resume` to learn the key.
2. Pops the child.
3. Looks up `(:on-name parent-cdef)` and invokes it with `[parent v]`.

Any component can be called by anyone; the caller decides what the
answer means. The callee never has to know.

---

## The effect language

The kernel's effect vocabulary is small enough to fit on one card:

```
[:call <embed> :resume k]            push a child frame
[:call-in-slot <slot> <embed> :resume k]
                                      swap an embedded slot's child
[:set-keyed-children <slot> [[key embed]…]]
                                      reconcile an ordered keyed child set
[:answer v]                          pop this frame, deliver v
[:replace <embed>]                   pop this frame, push another (Seaside `become:`)
[:patch hiccup]                      extra DOM patch, no stack change
[:patch-signals {…}]                 push a Datastar signal patch
[:execute-script "..."]              raw JS in the browser
[:history :replace|:push url]        sync browser URL
[:io fn]                             runtime-interpreted off-thread thunk
[:after ms event]                    schedule a future event for this instance
[:subscribe topic event]             subscribe this instance to a topic
[:unsubscribe]  /  [:unsubscribe t]  drop subscription(s)
[:back]                              walk one step backward in :conv/history
[:end v]                             terminate the conversation
```

Constructors live in `dev.zeko.stube.effects` and are re-exported as
`s/call`, `s/answer`, …. The wire shape is plain vectors so handlers
can hand-roll effects if they prefer.

### Folding

```
   handler returns [self' effects]
            │
            ▼
   ┌────────────────────────┐
   │ run-effects            │     reduce step over each effect
   │   conv ── eff ──▶ [conv' [frag …]]
   └────────────────────────┘
            │
            ▼
   server pushes fragments to the open SSE
```

Implementation:

```clojure
(defn run-effects
  [conv effects]
  (reduce (fn [[c frags] eff]
            (let [[c' fs] (step c eff)]
              [c' (into frags fs)]))
          [conv []]
          effects))
```

`step` is a multimethod keyed on the effect tag. Each method is one
small piece of semantics: `:call` instantiates and pushes; `:answer`
unwinds and resumes; `:replace` does the Seaside `become:`; `:back`
rewinds history. The multimethod is intentionally centralised today;
`todo.md` tracks the pre-1.0 line-count reduction target.

The kernel also has dynamic side-effect hooks (`*schedule-event!*`,
`*subscribe!*`, `*unsubscribe!*`, `*run-io!*`) that the runtime binds
while folding live requests. Without those bindings, the pure fold keeps
those effects inert: for example, `[:io f]` produces no fragments and
does not call `f` during `s/dispatch` / `s/replay`.

### One subtle invariant: post-handler auto-render

If a handler returns no element-producing effects and the instance
still exists, `dispatch` re-renders that instance before returning
control. That's why `(fn [self _] (update self :n inc))` works
without an explicit `(s/patch …)` — the kernel notices state changed
and pushes a new frame. The same rule applies to `:call`, `:answer`
and `:replace`: if any of them produced element fragments, the
auto-render is skipped to avoid emitting a redundant patch.

---

## The dispatch path

Following one click from browser back to browser. Pretend you clicked
`+` on a counter:

```
browser
  │ click → Datastar reads data-on:click → @post('/event/CID/IID/inc')
  ▼
http.clj  event-handler
  │ pull cid, iid, event name from path
  │ pull signals from the request body (JSON)
  │ pull structured payload from the _stube_payload query param (EDN)
  │ check session authorization
  │ embed/dispatch! k cid {:instance-id iid :event :inc :payload p :signals s}
  ▼
runtime.clj  dispatch!  →  apply-conv!  →  swap-conv!
  │ bind render/*cid*, kernel/*current-kernel*,
  │      kernel/*schedule-event!*, *subscribe!*, *unsubscribe!*, *run-io!*
  │ atomically: kernel/dispatch conv event  →  [conv' frags]
  │ persist via store/save!
  │ push frags to the open SSE generator
  ▼
kernel.clj  dispatch
  │ resolve component for the target instance
  │ snapshot conv onto :conv/history (unless effect is [:back])
  │ merge kept signals onto self  (conv/merged-self)
  │ run :handle  → coerce-return → [self' fx]
  │ run-effects conv** fx           (each :step method runs)
  │ if no element fragments emitted, auto-render this instance
  ▼
render.clj  html
  │ chassis renders hiccup → string (selector + patch-mode in opts)
  ▼
fragments.clj  push!
  │ translate {:fragment/kind :elements …} → d*/patch-elements!
  │ lock-sse! around the doseq so concurrent dispatches never interleave
  ▼
browser
  │ Datastar morphs the new HTML into the DOM by id
```

Two important things this picture flattens:

- **The `:url` auto-emit.** After the handler's effects fold, the
  kernel compares the root's `(:component/url cdef)` projection against
  `:conv/last-url` and emits a `[:history :push …]` effect if they
  diverge (suppressed when the handler already emitted its own
  `[:history …]`). The logic lives in `maybe-emit-url` inside
  `kernel/dispatch`. If a second similar feature ever appears (audit
  hook, post-dispatch invariant, observer), this is the seam to
  generalise — see issue R1-14 for the sketched hook-list shape. Until
  there is a real second consumer the special-casing stays inline.
- **Snapshot before mutate.** `:conv/history` is appended *before*
  the handler runs (modulo the `[:back]` exception), so even a
  handler that emits multiple effects has a clean rewind point.
- **The `:effect-iid` binding.** When a resume key fires after a
  child answers, the kernel rebinds `effects/*effect-iid*` to the
  parent's id so any `s/call-in-slot` inside the resume targets the
  correct parent. Embedded children with their own handlers run with
  the embedded instance as origin. Code in `:io` thunks should treat
  the conversation as out-of-band; if async work needs to land back in
  the UI, publish or dispatch a fresh event instead of mutating the old
  `self`.

---

## Rendering: `:rendered?` and morph-by-id

The kernel keeps a per-instance `:instance/rendered?` flag. First
render of a frame uses the *first‑render* path:

```
patch-elements!  selector "#root"  patch-mode :inner
```

— blow the whole shell away and inject the new frame. Subsequent
renders use Datastar's default morph-by-id, which finds the element
with `id = instance-id` and morphs the new HTML into it. That is why
`s/root-attrs` *must* be on every component's root element.

Three subtle moments toggle the flag:

1. **First emit of a frame's first patch** sets it true. From then on
   the cheaper morph-by-id path runs.
2. **`[:back]`** clears the flag on every instance in the restored
   conversation, because the DOM that those instances were rendered
   into may no longer be there.
3. **`resume-top`** (used when the browser re-attaches to an
   already-running conversation, e.g. after persistence restore or
   hot reload) clears the top frame's flag so it re-renders shell-style
   into the freshly served `<div id="root">`.

The `frame/render-slot-overlay` path is the one weird child: when a
`call-in-slot` adds a new child to a parent, the overlay patch targets
the *parent's* id with mode `:replace` to swap in the new HTML, then
the parent's `:rendered?` flag is bumped.

---

## Two-way bindings and signals

Datastar signals are page-global by default. The flow is:

1. Server emits `<input data-bind:draft__case.kebab="true">` via
   `(s/bind :draft)`. The `__case.kebab` modifier asks Datastar to
   keep the wire key kebab-cased so the server can read it as
   `:draft` without any case-fixing logic.
2. The user types. Datastar updates `$draft` in its in-page signal
   store.
3. The next event POSTs the full signal store as JSON in the request
   body.
4. `http.clj/read-signals` parses it (charred) with `:key-fn keyword`.
5. The kernel calls `conv/merged-self`, which consults the
   component's `:component/keep` set and lifts matching signals onto
   `self` *before* `:handle` runs.

`s/local-bind` is the same dance but the wire key gets suffixed with
the instance id (`:draft-ix-007`). On the way back,
`merge-kept-signals` consults that local key first and lifts its
value onto the *logical* `:draft`. The handler reads `(:draft self)`
either way.

The reason for `__case.kebab`: Datastar 1.0 camel-cases
`data-bind:<key>` by default, which would turn `:draft-name` on the
server into `draftName` on the wire and back to `:draftName` on the
server — defeating the round trip.

---

## `defflow` and the cloroutine bridge

`dev.zeko.stube.flow`. A `defflow` body becomes a regular component
whose state contains a *cloroutine continuation* under `::coro`. The
contract is small:

```
(s/await embed-spec)
   ↓  body suspends; (coro) returns [::yield embed-spec]
flow advances by emitting (s/call embed-spec :on-flow-resume)
   ↓  child answers v
:on-flow-resume binds *answer* and invokes (coro) again
   ↓  cloroutine resumes the suspended `await` with v
body proceeds until the next await or its final value
   ↓  final value comes back as [::done value]
flow emits (s/answer value)
```

Every flow uses the same resume key (`:on-flow-resume`). The user
never types it; the macro emits it.

Two consequences:

- A `defflow` instance is **not EDN**. The cloroutine continuation
  is a Java object. `file-store` detects this with a `pr-str` scan
  and skips the save with a warning.
- `await` can only appear in synchronous positions inside the
  cloroutine — `let`, `if`, `cond`, `do`, `loop`+`recur` are OK;
  nested `(fn …)` and lazy seqs are not.

For state that must survive a process restart, write a task
component by hand: `:start` emits the first `:call`, each `:on-step-N`
threads partial state through to the next call. That shape *is* EDN
and persists cleanly.

---

## Async surfaces

`dev.zeko.stube.runtime` holds the side-effecting registries on each
kernel value, so embedded kernels do not share live state:

| atom | role |
|------|------|
| `:!conversations` | `{cid → conv}` live conversation state |
| `:!sse-sessions`  | `{cid → sse-generator}` open Datastar channels |
| `:!pending-roots` | one-shot baton: a cid is minted by shell GET and its root embed sits here until the first SSE connect boots it |
| `:!timers`        | scheduled futures created by `[:after ms event]` |
| `:!subscriptions` | `{topic → {[cid iid] event}}` for pub/sub |
| `:!shutting-down?` | shutdown/drain gate |

The pure kernel never reaches into those atoms. During `apply-conv!`,
the runtime binds the kernel's scheduling/subscription/IO hooks; the
effect fold calls the hook, and the hook updates this kernel's registry
or starts the relevant future.

`s/publish!` is a regular function call, not an effect. From inside a
component dispatch it uses the `*current-kernel*` binding, so embedded
components publish into their own host kernel. Outside a dispatch it
falls back to the standalone server's default kernel. Delivery spawns
one future per subscriber and dispatches each as a normal event with
`:payload` set to the published message.

The `*…*` vars named above are documented in
[Dynamic bindings](#dynamic-bindings) below.

### Single-JVM scope

Pub/sub is **in-process only by design**. A `(s/publish! topic msg)`
call walks the kernel's `:!subscriptions` atom, which is a plain
Clojure map of subscribers in the same JVM. Across two JVMs (two
nodes behind a load balancer, a worker container alongside a web
container) the subscribers in the other process never see the
message.

This is deliberate. The kernel intentionally has no opinion on
distributed messaging — every reasonable choice (Redis pub/sub,
Postgres `LISTEN`, NATS, Kafka, Cloud Pub/Sub) brings its own
durability, ordering, and back-pressure model, and most apps want to
pick one based on infrastructure they already run. A real
cross-process layer would slot in as a small `Publisher` protocol the
runtime can be handed at `make-kernel` time:

```clojure
(defprotocol Publisher
  (publish-out!  [this topic msg])
  (subscribe-in! [this topic on-msg]))
```

The local `:!subscriptions` walk stays the in-process fast path; the
hook fires for each publish so an external bus can fan the message
out to the other nodes, and a subscribe-side daemon dispatches
incoming messages back into the right kernel. Until a user asks for
this, the protocol isn't worth the surface area — but the seam is
named here so the eventual change is additive.

If you are running two nodes today and need them to see each other's
publishes, the working-today recipe is: stand up your bus of choice
in `:app`, and have components call `(some-bus-publish (:bus (s/app))
topic msg)` directly instead of `(s/publish! topic msg)`. The kernel
stays in its lane; the bus stays in its.

---

## Persistence

`dev.zeko.stube.store` defines a tiny protocol:

```clojure
(defprotocol ConversationStore
  (load-all [this])
  (save!    [this conv])
  (delete!  [this cid]))
```

- `in-memory-store` is a no-op default (the server's atom IS the
  source of truth).
- `file-store` writes `<cid>.edn` files. Writes go to a sibling temp
  file and rename atomically; reads use `clojure.edn/read-string`
  with no eval and a permissive `:default tagged-literal` reader.
  `Instant` is registered with a `print-method`/`edn-reader` pair so
  `:conv/created`/`:conv/touched` round-trip via plain `#inst "…"`.

Save runs *outside* `swap!`'s CAS retry loop — the store only sees
the final value — so it never gets called twice for one logical
write.

Failures are logged to `*err*` and swallowed. Persistence is best
effort; a disk-full condition must not break the live request.

---

## The HTTP layer

Five conversation routes, all in `dev.zeko.stube.http`:

```
GET  /<mount-path>             → shell-handler   ; mint cid, serve HTML
GET  /sse/:cid                 → sse-handler     ; long-lived SSE
POST /back/:cid                → back-handler    ; emit [:back]
POST /event/:cid/:iid/:event   → event-handler   ; dispatch one event
POST /upload/:cid/:iid         → upload-handler  ; multipart → :upload-received
```

Embedded kernels prefix the same paths with their `:base-path`.

Plus the framework asset routes:

```
GET  <base>/ui.css                       → stock stylesheet
GET  <base>/preserve.js                  → preserve bridge + on-unmount observer
GET  <base>/behaviors.js                 → behaviors bridge
GET  <base>/styles/<ns>/<name>.css       → resources/stube_styles/<ns>/<name>.css
GET  <base>/modules/<id>.js              → resources/stube_modules/<id>.js
GET  <base>/behaviors/<ns>/<name>.js     → resources/stube_behaviors/<ns>/<name>.js
GET  <base>/halos.js                     → dev overlay (when enabled)
```

`preserve.js` also hosts the `data-stube-on-unmount` MutationObserver
that fires host-widget teardown expressions on real DOM removal
(queueMicrotask-deferred so Idiomorph's detach+reattach swap dance
can't double-fire) and dispatches a `stube:patched` `CustomEvent` on
`document` after each successful morph.

`behaviors.js` walks elements carrying `data-stube-behavior` on every
`stube:patched`, lazy-imports the matching module from the
`/behaviors/` route, and drives the `mount`/`patched`/`unmount`
lifecycle once per element per slug. The same MutationObserver
pattern as `preserve.js` keeps unmount firings de-duplicated against
the morph dance.

Asset segments are validated against `[A-Za-z0-9_-]` before any
filesystem lookup; traversal-shaped requests return 400 without
touching `io/resource`.

The SSE handler has three startup paths:

1. **Fresh shell visit** — the pending flow on the baton boots.
2. **Restored conversation** — the cid exists in memory (loaded
   from disk on startup, or carried over a hot reload), so the top
   frame's `:wakeup` runs and the kernel re-renders shell-style.
3. **Unknown cid** — the SSE channel stays empty; the browser sees no
   patches. Event/upload POSTs for a missing or ended conversation
   return 410 and, when possible, push a "this page is stale, please
   reload" patch before closing the conversation.

An event/upload POST for a missing instance inside an otherwise-live
conversation is different: it is treated as a harmless stale event and
returns 204 without ending the conversation. Double-clicks, slot swaps,
keyed-child replacements, timers, and browser retries can all produce
that shape legitimately.

Session ownership is enforced by a signed cookie set on the shell
response. The cookie's value is also stamped onto `:conv/owner-token`
and checked on every subsequent route hit — so a leaked URL is not
enough to drive someone else's conversation.

### SSE behind a reverse proxy

Long-polling SSE connections fall foul of the proxy idle timers that
most HTTP fronts apply to "stuck" upstream connections. The defaults
that bite people:

| front          | default idle timeout              |
|----------------|-----------------------------------|
| nginx          | 60s (`proxy_read_timeout`)        |
| AWS ALB        | 60s (idle timeout, per-target)    |
| Caddy          | 30s (`reverse_proxy` flush)       |
| HAProxy        | 50s (`timeout server`)            |

stube ships an SSE comment-frame heartbeat to keep these connections
warm. Every `:sse-keepalive-ms` (default 15s) the runtime sends a
`stube-keepalive` event with an empty data line. Datastar ignores
unknown event types, so the client sees nothing — only the proxy sees
activity. The heartbeat is cancelled on `unregister-sse!` and `halt!`.

Set the option to `nil` or `0` to disable (useful in tests or when
the host's fronting layer has no idle timeout):

```clojure
(embed/make-kernel {:sse-keepalive-ms nil})
```

You still need to make sure the proxy does not *buffer* the SSE
stream — heartbeats save the connection from being closed for being
idle, but a proxy that holds bytes back until it has a full chunk
will defer your UI updates by seconds. Sample config knobs for the
two most common deployments:

```nginx
# nginx — disable proxy buffering on the SSE route only.
location ~ ^/.*?/sse/ {
  proxy_pass            http://stube;
  proxy_http_version    1.1;
  proxy_set_header      Connection "";
  proxy_buffering       off;
  proxy_cache           off;
  # The heartbeat is 15s; raise the timer so it stays well above that.
  proxy_read_timeout    1h;
  proxy_send_timeout    1h;
}
```

```
# ALB target group — raise the idle timeout on the load balancer.
# (The heartbeat keeps things alive within the new window.)
aws elbv2 modify-load-balancer-attributes \
  --load-balancer-arn $ARN \
  --attributes Key=idle_timeout.timeout_seconds,Value=300
```

For Caddy, `flush_interval -1` on the SSE route plus `transport http
{ read_buffer 4096 }` is sufficient. For HAProxy, `option
http-server-close` plus a large `timeout tunnel` works.

---

## The shell

`dev.zeko.stube.shell` emits roughly this for every mounted GET:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <link rel="stylesheet" href="/ui.css">                   <!-- optional -->
    <link rel="stylesheet" href="/styles/notes/shell.css">   <!-- one per registered component with a stylesheet -->
    <style>[data-stube-component="foo/bar"] { … }</style>    <!-- inline :styles, scoped at head-emit -->
    <script type="module" src="/preserve.js"   data-stube-base-path=""></script>
    <script type="module" src="/behaviors.js"  data-stube-base-path=""></script>
    <script type="module" src="<datastar-cdn>"></script>
    <script type="module" src="/modules/notes/zoom.js"></script>
                                                              <!-- one per distinct :modules entry -->
  </head>
  <body data-init="@get('/sse/CID')" data-stube-base-path="">
    <div id="root"></div>
  </body>
</html>
```

The stylesheet links and module scripts are derived from the
registry at head-emit time — `head-tags` walks every registered
component, asks `io/resource` whether a matching stylesheet file
exists, and unions the `:component/modules` vectors with sort+dedup.
Inline `:styles` chunks are concatenated into a single `<style>`
block; each `&` is replaced with the component's
`[data-stube-component="ns/name"]` selector.

`data-init` is what Datastar fires once when it processes the
element. (`data-on:load` doesn't, because `<body>` has no `load`
event after Datastar attaches — one of the five Datastar facts the
slice-0 implementation had to discover the hard way; see
`docs/archive/v2_1.md` §0.)

`shell/head-tags` exposes the same head nodes for embedders, with
`:base-path`, `:ui-css?`, and `:halos?` already applied;
`embed/head-tags` is the public wrapper. After that one line of HTML,
every UI patch in the user's session arrives via the SSE stream.

---

## Shutdown sequence

`(s/stop!)` (standalone) and `(stube/halt! kernel)` (embedded) run the
same drain through `dev.zeko.stube.runtime/halt!`:

1. **Freeze new requests.** A `:!shutting-down?` flag flips on; the
   stock `shell-handler` returns `503 + Retry-After: 5` for any new
   mint.  Embedders that bypass `shell-handler` can poll
   `(stube/shutting-down? kernel)` themselves.
2. **Cancel scheduled events.** Every `s/after` future for every cid
   is `future-cancel`ed.  In-flight timer firings finish.
3. **Run `:stop` on every live instance**, children before their
   frame, top stack frame first.  The fold goes through
   `apply-conv!`, so any DOM patches a stop hook emits ship over SSE
   before the channel closes.  A throwing hook is logged; the drain
   keeps going.
4. **Drain SSE streams.** Every registered generator receives a
   `:close` fragment.
5. **Flush the store.** Each conversation gets one last `save!`
   *before* `:!conversations` is cleared, so file-backed stores end
   the process with an up-to-date snapshot.
6. **Reset per-kernel registries** (`:!sse-sessions`,
   `:!pending-roots`, `:!subscriptions`, `:!conversations`).

`halt!` is idempotent (`compare-and-set!` on `:!shutting-down?`), so
calling it twice — say, both from a JVM shutdown hook and from a host
container — is safe.

`:start` and `:wakeup` still use `registry/lookup!` and surface
missing components loudly; `:stop` uses the no-throw `lookup` so a
component de-registered between mount and shutdown (hot reload, test
teardown after `registry/clear!`) simply has no `:stop` to run.

---

## Halos — the dev overlay

`dev.zeko.stube.halos` and `…/halos/http.clj` implement an optional
in-browser overlay inspired by Smalltalk's halos:
`(s/start! {:halos? true})` and `?halos=1` on the URL turn it on
per-conv. Each rendered component gets a small set of data-attrs
naming its `:component/id`, `:instance/id`, `:component/source`, and
parent linkage. The overlay JS adds visible handles around hovered
components and a side panel showing the tree, the instance map, and
the conversation history.

You don't need halos to use stube; you might need them while
debugging a hairy component composition. Pair with `(s/inspect cid)`
on the REPL side.

---

## Where to look in the source

Want to understand a particular thing? Start here.

| if you want to know… | read |
|---|---|
| What's in a conversation | `conversation.clj` |
| What effects mean | `effects.clj` + the `step` multimethod in `kernel.clj` |
| How the dispatch chain works | `kernel.clj/dispatch` (one function) |
| How a frame becomes HTML | `frame.clj` + `render.clj` |
| How SSE patches are emitted | `fragments.clj` |
| How `defflow` is built | `flow.clj` (~210 lines) |
| Routes and shell page | `http.clj` + `shell.clj` |
| Live conversation storage | `runtime.clj` + `store.clj`; `server.clj` is the standalone wrapper |
| Timers and pub/sub | `runtime.clj` |
| The stock dialogs | `ui.clj` |
| The dev overlay | `halos.clj`, `halos/http.clj`, `resources/dev/zeko/stube/halos.js` |

Tests under `test/dev/zeko/stube/` mirror the source structure
file‑for‑file and are short. Reading the kernel tests
(`kernel_test.clj`, `flow_test.clj`, `embed_test.clj`,
`back_test.clj`) is one of the fastest ways to internalise the
semantics of each effect.

---

## Dynamic bindings

stube uses a small set of `^:dynamic` vars to keep the pure kernel
decoupled from the runtime while still letting handlers schedule
events, subscribe, publish, and read app/principal/context. They
fall into five groups.

### Side-effect hooks (kernel ↔ runtime seam)

Bound by `runtime/with-kernel-bindings` around every kernel
dispatch.  Read inside the kernel fold so a pure `(s/dispatch …)` /
`(s/replay …)` can run with the side effects left inert.

| var | bound by | read by | nil means |
|---|---|---|---|
| `kernel/*schedule-event!*` | `runtime/with-kernel-bindings` | `effects.step` for `[:after …]` | timer is dropped |
| `kernel/*subscribe!*` | `runtime/with-kernel-bindings` | `effects.step` for `[:subscribe …]` | subscribe is dropped |
| `kernel/*unsubscribe!*` | `runtime/with-kernel-bindings` | `effects.step` for `[:unsubscribe …]` | unsubscribe is dropped |
| `kernel/*run-io!*` | `runtime/with-kernel-bindings` | `effects.step` for `[:io …]` | `:io` effect is inert |

### Kernel / app / principal context

Bound by the runtime so component code can resolve "what kernel am
I in?", "what app deps did the embedder attach?", and "who is the
authenticated principal on this conversation?".

| var | bound by | read by | nil means |
|---|---|---|---|
| `kernel/*current-kernel*` | `runtime/with-kernel-bindings` | `s/publish!`, `kernel/warn-fallback-once!` | publish targets the standalone default kernel; per-kernel deduped warnings skip |
| `kernel/*current-app*` | `runtime/with-kernel-bindings`; `core/with-app` (tests) | `s/app` | `(s/app)` returns nil |
| `kernel/*current-principal*` | `runtime/with-kernel-bindings`; `core/with-principal` (tests) | `s/principal` | anonymous conversation |

### Render context

Bound around hiccup rendering so attribute helpers can build URLs
and look up embedded children without each `:render` fn passing
them through.

| var | bound by | read by | nil means |
|---|---|---|---|
| `render/*cid*` | `frame/render-frame`, http handlers, halos | `render/event-url`, `render/sse-url`, `render/back-url`, etc. | render is being exercised outside an http context (e.g. tests); URLs use a placeholder |
| `render/*base-path*` | `runtime/with-kernel-bindings`, `runtime/replay-with` | URL builders in `render.clj` | empty string (standalone default) |
| `render/*root-selector*` | `runtime/with-kernel-bindings`, `runtime/replay-with` | shell/first-frame render | `#root` |
| `render/*conv*` | `frame/render-frame` | `render/render-slot` to locate embedded children by id | slot rendering produces a placeholder |

### Effect-origin context

| var | bound by | read by | nil means |
|---|---|---|---|
| `effects/*effect-iid*` | `kernel/dispatch` and `lifecycle/*-hook` paths via `effects/with-origin` | slot-local effects that need the actual emitting instance instead of the top frame | effects fall back to top-frame heuristics |

### Error / dev tooling

| var | bound by | read by | nil means |
|---|---|---|---|
| `errors/*on-error*` | `runtime/with-kernel-bindings` | `errors/build-fragment` | the host-level reporting hook is not installed; the banner still renders |
| `dev/*enabled?*` | tests that opt out of schema validation | `dev/validate!` | follow the global `*enabled?*` default (dev-mode schema check is on) |

### Flow internals

| var | bound by | read by | nil means |
|---|---|---|---|
| `flow/*answer*` | `flow/defflow` resume path | cloroutine `(s/await …)` resume value | flow is not in the middle of a resume |

---

## A note on what *isn't* here

A few things that are deliberately not in stube as of this writing:

- **No bundled auth model.** The session/ownership layer scopes who
  can drive whose conversation; *who that user is* is for your app
  to decide.
- **No bundled database.** The conversation is your application
  state for the duration of a session; long-lived data goes wherever
  your app keeps it. `s/io`, `s/after`, and `s/publish!` are bridges
  back into that outside world, not a storage layer.
- **No client-side framework.** That is the entire point. If the
  browser needs to do something Datastar can't, `(s/execute-script)`
  is the escape hatch.
- **No HTML templating language other than hiccup.** Chassis was
  picked specifically for its hiccup compatibility.
- **No defmulti for components.** A component is a map. A pluggable
  variant is `(s/decorate base overrides)` — `merge` lifted into the
  framework's vocabulary.

If any of these change before 1.0, the change will land in
`docs/decisions/` (or its successor) first, with the rationale.

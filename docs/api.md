# API reference

Component-author APIs documented here live in the
`dev.zeko.stube.core` namespace — the surface I try to keep stable
while the rest of the project evolves. Aliased as `s` by convention:

```clojure
(require '[dev.zeko.stube.core :as s])
```

Anything outside `dev.zeko.stube.core` is **internal** and may move
without notice, except the embedder surface documented in
[`dev.zeko.stube.embed`](#embedding-in-a-host-ring-app). If you find
yourself reaching into another namespace to do something normal, file
an issue — the API is meant to grow to cover that, not for callers to
grow workarounds.

The reference is organised by *what you're trying to do*:

- [Defining components](#defining-components) — `defcomponent`,
  `defflow`, `decorate`
- [Effects from a handler](#effects-from-a-handler) — `call`,
  `answer`, `patch`, …
- [Hiccup helpers](#hiccup-helpers) — `root-attrs`, `on`,
  `on-target`, `bind`, `render-slot`, `keyed-children`, …
- [Reading dependencies](#reading-dependencies--app-vs-context-vs-principal)
  — when to pick `s/app`, `s/context`, or `s/principal`
- [Stock UI components](#stock-ui-components) — `confirm`, `prompt`,
  `choose`, `info`
- [Lifecycle and mounting](#lifecycle-and-mounting) — `mount!`,
  `start!`, …
- [Embedding in a host Ring app](#embedding-in-a-host-ring-app) —
  `make-kernel`, `ring-routes`, `shell-for`, `head-tags`
- [REPL / testing surface](#repl--testing-surface) — `dispatch`,
  `replay`, `inspect`
- [Persistence](#persistence) — `in-memory-store`, `file-store`

A short cheat sheet sits at the [end of this page](#cheat-sheet).

---

## Defining components

### `(s/defcomponent id & opts)`

Macro. Defines and registers a component. `id` must be a namespaced
keyword.

```clojure
(s/defcomponent :auth/login
  :doc    "Prompt for credentials."
  :init   (fn [args]  state-map)              ; optional
  :keep   #{:signal-keys}                     ; optional
  :render (fn [self]  hiccup)                 ; optional
  :handle (fn [self event] [self' effects])   ; optional
  :start  (fn [self]      [self' effects])    ; optional lifecycle
  :stop   (fn [self]      [self' effects])    ; optional lifecycle
  :wakeup (fn [self]      [self' effects])    ; optional lifecycle
  :on-foo (fn [self answer-value] [self' fx]) ; resume keys (any number)
  :children {:slot/header (s/embed :ui/site-header)} ; optional eager children
  )
```

Recognised keys:

| key | shape | when it runs |
|---|---|---|
| `:doc` | string | static; queryable via `s/help` |
| `:init` | `(fn [args] state-map)` | once at instantiation — `args` is the only handle on outside data; for kernel-level dependencies see [Reading dependencies](#reading-dependencies--app-vs-context-vs-principal) |
| `:keep` | `#{:k1 :k2 …}` | listed Datastar signals are merged onto `self` on every event |
| `:render` | `(fn [self] hiccup)` | whenever a frame needs re-rendering |
| `:handle` | `(fn [self {:keys [event payload signals]}] …)` | on every dispatched event |
| `:start` | `(fn [self] …)` | once, right after instantiation |
| `:stop` | `(fn [self] …)` | just before the frame/subtree is removed |
| `:wakeup` | `(fn [self] …)` | when a persisted/history-restored frame becomes live again |
| `:on-<key>` | `(fn [self answer-value] …)` | when a child `:answer`s under that resume key |
| `:on-error-<key>` | `(fn [self exception] …)` | when a child `:answer-error`s under that resume key — see [Failure routing from a child](#failure-routing-from-a-child) |
| `:children` | `{slot embed-spec}` or `(fn [self] …)` | declared once; the kernel instantiates eagerly |
| `:url` | `(fn [self] url-string-or-[op url]-or-nil)` | pure projection of state to the browser URL — see [URL as a projection of state](#url-as-a-projection-of-state) |

**Colocated keys are a closed set.** `:init`, `:render`, `:handle`,
`:keep`, `:doc`, `:state`, `:start`, `:stop`, `:wakeup`, `:children`
and `:url` are lifted to `:component/<name>` at registration. Resume
keys (`:on-foo`, `:on-error-foo`, …) are open — authors invent them
per call site, and the kernel looks them up by exact name with no
namespacing. Declaring both `:foo` and `:component/foo` for a
lifecycle key raises an `ex-info` at register time.

**Handler return shapes.** Any of these is fine; the kernel coerces:

```clojure
nil                     ; no change, no effects
self'                   ; new self, no effects
[effect …]              ; same self, these effects
[self' [effect …]]      ; both
```

So `(update self :n inc)` is a perfectly good handler return.

**Two-way bindings.** When `:keep #{:answer}` is declared and the
DOM signal `:answer` (or its instance‑local form, see `local-bind`)
comes in with an event, the kernel writes it onto `self` *before*
your handler sees it. You read `(:answer self)`.

**The `event` map** the kernel passes to `:handle`:

```clojure
{:event   :submit          ; the route keyword (yours)
 :payload anything-edn     ; from (s/on … :as [:event v])
 :signals {:draft "hi"}}   ; raw signals from the browser
```

### `(s/register-component! id opts)`  /  `(s/register-component! id opts source-map)`

The function form of `defcomponent`. Useful when you want to build
a component map programmatically. `source-map` is
`{:file ... :line ...}` for the halos dev tool.

### `(s/defflow id bindings & body)`

Macro. Defines and registers a *flow* — a component whose role is to
sequence children. Body reads as straight-line Clojure with
`(s/await child-embed)` as a suspend point.

```clojure
(s/defflow :booking/wizard [{:keys [user-id]}]
  (let [dates (s/await (s/embed :booking/dates {:user user-id}))
        room  (s/await (s/embed :booking/room  {:dates dates}))]
    {:dates dates :room room}))
```

The body's final expression becomes the flow's `:answer`. For a
root flow that turns into `[:end value]` and closes the SSE channel.

**Rules of the road** (cloroutine‑imposed):

- `await` cannot appear inside a nested `(fn …)`, a lazy seq, or
  any form that escapes synchronous evaluation. `let`, `do`, `if`,
  `cond`, `when`, `loop`+`recur` are all fine.
- `try`/`catch` *across* an `await` is not supported.
- The continuation is **not EDN-serialisable**, so any conversation
  that contains a live `defflow` is in-memory-only. `file-store`
  logs a warning and skips its save; the flow stays live in the
  current process but does not survive a restart. This is a
  deliberate property of `defflow`, not a gap to be fixed — see
  *Durable flows: defflow vs. task components* in the tutorial for
  the EDN-clean alternative.

**Suspend points are var-identity.** Cloroutine recognises `await` by
the var it resolves to, not by its name. `s/await` and
`dev.zeko.stube.flow/await` are intentionally the *same var*: `core` does
`:refer-clojure :exclude [await]` and then `:refer [await]` from
`dev.zeko.stube.flow`. As long as you call `(s/await …)` (the standard
require) you'll never notice this. The traps:

- Don't bring the flow ns in under its own alias and call
  `(flow/await …)` from inside a `defflow` body — that's the same var,
  but readers will mistake it for an unrelated function.
- Don't shadow `await` in a local `let` or function arg. The macro
  walks the body for the var; a locally-bound `await` will be a normal
  Clojure expression and never suspend.

If your body silently runs through to the final expression without
ever pausing, this is almost always the cause.

### `(s/await embed-spec)`

Inside a `defflow` body, suspend until the embedded child answers,
then resume with the answered value. Outside a `defflow` body it
has no useful meaning.

### `(s/decorate base-cdef overrides)`  /  `(s/decorate! base-cdef overrides)`

Build a new component definition by overriding keys of a base
component. `overrides` is either a map (replace) or a function of
the base cdef returning such a map (so the override can call into
the original `:render`/`:handle`/etc.). `decorate!` also registers
the result.

```clojure
(s/decorate! (s/registry-lookup :booking/wizard)
  (fn [base]
    {:component/id     :booking/wizard-with-banner
     :component/render
     (fn [self]
       [:div (s/root-attrs self)
        [:header.banner "Welcome"]
        ((:component/render base) self)])}))
```

No new runtime concept — this is just `merge` lifted into the
framework's vocabulary.

### `(s/registry-lookup id)`  /  `(s/help id)`

`registry-lookup` returns the registered component map (or nil).
`help` returns its `:component/doc`, if any.

---

## Effects from a handler

Handlers (and lifecycle hooks) return effects. These constructors
produce the wire vectors the kernel folds. You can hand-roll the
vectors too, but the constructors are clearer.

| constructor | wire form | meaning |
|---|---|---|
| `(s/call id)` `(s/call id args)` `(s/call embed :on-key)` | `[:call embed :resume k]` | push a child onto the stack; on `:answer`, parent's `:on-key` fires |
| `(s/become id)` `(s/become id args)` `(s/become embed)` | `[:replace embed]` | pop this frame and push another in its place (Seaside `become:`) |
| `(s/call-in-slot slot id args :on-key)` `(s/call-in-slot slot embed :on-key)` | `[:call-in-slot slot embed :resume k]` | temporarily swap an embedded slot's child; child answers back without taking over the page |
| `(s/answer value)` | `[:answer v]` | pop this frame; deliver `v` to the parent under its resume key |
| `(s/answer-error ex)` | `[:answer-error ex]` | pop this frame; deliver `ex` to the parent under its `:on-error-<key>` resume — see [Failure routing from a child](#failure-routing-from-a-child) |
| `(s/patch hiccup)` | `[:patch h]` | emit an extra DOM patch without changing the stack |
| `(s/patch-signals m)` | `[:patch-signals m]` | push a Datastar signal patch (writes signal values back to the browser) |
| `(s/execute-script js)` | `[:execute-script js]` | run literal JS in the browser (last-resort escape hatch) |
| `(s/history :replace url)` / `(s/history :push url)` | `[:history op url]` | sync the browser URL with `replaceState` / `pushState` |
| `(s/io thunk)` | `[:io fn]` | ask the active runtime to run `(thunk)` off the request thread; pure replay leaves it inert |
| `(s/after ms event)` | `[:after ms event]` | dispatch `event` to this instance after `ms` |
| `(s/subscribe topic event)` | `[:subscribe topic event]` | subscribe this instance to `topic`; messages arrive as `event` |
| `(s/unsubscribe)` / `(s/unsubscribe topic)` | `[:unsubscribe]` etc. | remove subscription(s) for this instance |
| `(s/set-keyed-children slot pairs)` | `[:set-keyed-children slot pairs]` | reconcile an ordered set of keyed child instances |
| `(s/back)` | `[:back]` | walk one step backward through the conversation's `:conv/history` |
| `(s/end value)` | `[:end v]` | terminate the conversation with a final value; closes SSE |

**Resume keys.** `(s/call (s/prompt "Name?") :on-name)` records
`:on-name` on the *child's* `:instance/resume`. When the child
`:answer`s, the kernel looks up `:on-name` on the parent's component
definition and invokes it with the answered value. The parent's
resume key isn't on the parent's instance, it's on the cdef — so any
component that calls the prompt can name its own resume.

**Structured event payloads.** `(s/on self :click :as [:pick item-id])`
ships the rest of the vector as `:payload`. The handler sees
`{:event :pick :payload item-id}`.

### Failure routing from a child

When a child needs to report a structured failure to its parent
without encoding it in the answered value, emit
`[(s/answer-error ex)]` instead of `[(s/answer …)]`:

```clojure
(s/defcomponent :feature/edit-form
  :handle (fn [self {:keys [event]}]
            (case event
              :save (try
                      (db/update! …)
                      [(s/answer :saved)]
                      (catch Exception ex
                        [(s/answer-error ex)])))))

(s/defcomponent :feature/column
  :on-saved        (fn [self _]  (assoc self :edit-open? false))
  :on-error-saved  (fn [self ex] (assoc self :banner (ex-message ex))))
```

The kernel uses a three-tier lookup on the parent:

1. **Parent declares `:on-error-<key>`.** The exception is passed
   verbatim; the success-side `:on-<key>` does **not** fire. Cleanest
   case; both branches stay separate.
2. **Parent declares only `:on-<key>`.** Falls back with the wrapped
   value `[:error ex]` and logs a one-time deprecation warning per
   `(parent-cdef, resume-key)` pair. Use this only for incremental
   adoption — long-term, declare the explicit mirror.
3. **Parent declares neither.** Surfaces the default error banner
   (`errors/build-fragment`) on the parent's instance, identical to
   an intra-component throw. The SSE channel stays open.

Other notes:

- The exception is passed **as-is**. The parent decides whether to
  call `(.getMessage ex)`, recover, or re-throw upward with another
  `(s/answer-error ex)`.
- The error-frame fallback (S-5) catches intra-component throws.
  `s/answer-error` is the *symmetric* child→parent failure path —
  use it when the child caught the exception itself and wants the
  parent to decide what to do.
- Cancellation and "user said no" do not need `answer-error`. Stick
  with `s/cancel` and a boolean from `s/confirm` for those cases —
  they're not exceptional, just one branch of normal flow.

### `(s/publish! topic msg)`

A regular function, not an effect. From component code it targets the
active runtime kernel; outside a dispatch it targets the standalone
server kernel. Delivers `msg` asynchronously to every live subscriber
of `topic`. Returns the number of subscribers targeted. Stale
subscribers are ignored.

### `(s/embed type)`  /  `(s/embed type args)`

Returns an embed spec map: `{:embed/type type :embed/args args}`.
The kernel uses these to instantiate children. `s/call`, `s/become`
and `s/call-in-slot` accept either a component id (+ optional args) or
an existing embed spec. `:children` declarations, stock UI helpers, and
`s/await` inside a `defflow` are where embed specs show up most often.

---

## URL as a projection of state

Components whose state belongs in the browser URL can declare it
directly with a `:url` fn alongside `:render` and `:handle`:

```clojure
(s/defcomponent :demo/url-counter
  :init   (fn [{:keys [n]}] {:n (or (some-> n str parse-long) 0)})
  :url    (fn [self] [:push (str "/counter?n=" (:n self))])
  :render (fn [self] …)
  :handle (fn [self {:keys [event]}]
            (case event
              :inc (update self :n inc)
              :dec (update self :n dec))))
```

After every successful dispatch on the **root** component, the kernel
calls `(url-fn self)` against the post-dispatch state. If the result
differs from `:conv/last-url`, it auto-emits a `:history` effect; no
need to thread `(s/history …)` through every handler. The handler can
still mutate state freely — the URL is a projection, not authoritative.

**Return shapes**:

```clojure
nil                       ; leave the URL alone
"/path?q=…"               ; equivalent to [:replace "/path?q=…"]
[:replace "/path?q=…"]    ; history.replaceState  (default — in-place mutation)
[:push    "/path/42"]     ; history.pushState     (Back/Forward should walk this)
```

**Contract**:

- `:url` must be **pure** of `self`. No side effects, no DB reads —
  derived data belongs in `:render`.
- An explicit `(s/history :push url)` emitted by the handler *wins*;
  the kernel suppresses its own auto-emit when any `[:history …]`
  effect is in the dispatch's effect vector.
- Only the **root** frame's `:url` is consulted. Nested instances'
  `:url` is ignored — the address bar is a singleton.
- First-load seeding: on the first dispatch, the pre-state value is
  recorded silently into `:conv/last-url`. The browser already shows
  the URL it loaded via GET; no redundant history emit.
- Pairs with `:init-args-fn` on `s/mount!` to read the URL back in
  on a fresh mount — see [Lifecycle and mounting](#lifecycle-and-mounting).

Worked example: `examples/dev/zeko/stube/examples/url_state_counter.clj`.
A hand-rolled equivalent for comparison lives next to it as
`url_state_counter_manual.clj`.

---

## Hiccup helpers

### `(s/root-attrs self & attr-maps)`

Returns the attribute map for the root element of a `:render`. Merges
in the instance id (mandatory — Datastar morphs by id) plus anything
else you pass:

```clojure
[:div (s/root-attrs self
        {:class "stube-card"}
        (s/on self :submit))
 …]
```

### `(s/on self dom-event)`  /  `(s/on self dom-event :as route-event)`

Wires a real DOM event on the surrounding element to a stube event.
`dom-event` is the actual DOM event name (`:click`, `:submit`,
`:input`, `:change`, …). `:as route-event` is the logical name
your handler sees in `:event`.

```clojure
[:form   (s/on self :submit)                "…"]   ; route name = :submit
[:button (s/on self :click :as :inc)        "+"]
[:button (s/on self :click :as [:pick id])  "Pick"]
```

Datastar registers listeners under the colon form (`data-on:<event>`).
`data-on:submit` automatically calls `preventDefault`, so forms
never trigger a full‑page reload.

### `(s/on-target target-iid dom-event)`  /  `(s/on-target target-iid dom-event :as route-event)`

Like `on`, but posts to an explicit instance id instead of `self`.
Use it sparingly for cross-instance controls: for example, a child
rendering a link that should notify a stable parent without answering
and disappearing.

```clojure
[:button (s/on-target (:parent-iid self) :click :as [:open (:id note)])
 "Open"]
```

### `(s/event-url iid route-event)`

Low-level helper used by `on` and `on-target`. It returns the URL that
Datastar should POST to for `iid` and `route-event`, including the EDN
payload query parameter for structured events. Most code should prefer
`on`; reach for `event-url` only when writing a custom Datastar
expression.

### `(s/preserve self label)` / `(s/on-mount self label expr)` / `(s/on-unmount self label expr)`

Use these together for third-party widgets that own their child DOM.
`preserve` marks the host element with `data-stube-preserve`; stube's
stock shell bridge lets future morphs merge host attributes while
skipping the child subtree. `on-mount` emits a Datastar
`data-init` expression only before this instance has rendered, so the
widget constructor runs once. `on-unmount` attaches a
`data-stube-on-unmount` expression that fires once when the host
element is detached from the DOM — the place to call
`editor.destroy()`, `chart.dispose()`, `removeEventListener`, or any
other tear-down the widget owns.

```clojure
[:div (merge {:class "editor-host"}
             (s/preserve   self :editor)
             (s/on-mount   self :editor "el.cmView = new EditorView({parent:el})")
             (s/on-unmount self :editor "el.cmView?.destroy()"))]
```

**`on-unmount` semantics.**

- Runs **exactly once** for a real removal. The bridge defers via
  `queueMicrotask` so an Idiomorph detach+reattach during a swap
  does not fire the expression.
- The expression must be **synchronous and idempotent**. It must not
  emit events back to the server — by the time it fires the host
  conversation may have already moved on, and the SSE channel for
  this frame is being torn down.
- `el` is bound to the detaching element, matching `on-mount`'s
  binding. Exceptions in the expression are logged to `console` and
  do not block the morph.
- Pairs naturally with `:keep` if the widget needs to flush state
  (e.g. CM6 cursor position) to a signal before destruction. The
  flush lands on the *next* event, not this one.

### `(s/bind signal)`

Two-way binding for an input. Datastar updates the signal
client-side as the user types; the next event ships the value back
to the server.

```clojure
[:input (merge {:name "draft"} (s/bind :draft))]
```

Combine with `:keep #{:draft}` so the value is merged onto `self`
before `:handle` runs.

### `(s/local-bind self signal)`  /  `(s/local-signal self signal)`

Like `bind`, but the wire signal name is suffixed with the instance
id. Use this whenever the same logical signal name might appear in
two instances on the same page (the editor pattern, multi-row
forms, etc.). Read the value back from `self` under the *logical*
name — `:keep #{:answer}` lifts the local signal onto `:answer`
for you.

`local-signal` returns just the namespaced wire key, useful if you
need to build a Datastar expression by hand.

### `(s/render-slot self slot-key)`

Inline an embedded child inside the parent's render:

```clojure
[:section (s/render-slot self :slot/header)
 …]
```

The slot must be declared in `:children` (or by `call-in-slot`).

### `(s/keyed-children self slot)`  /  `(s/set-keyed-children slot pairs)`

Use keyed children when a parent owns an ordered collection of child
instances, identified by stable application keys instead of fixed slot
names. The render helper emits the container; the effect reconciles its
contents.

```clojure
:render
(fn [self]
  [:section (s/root-attrs self)
   (s/keyed-children self :slot/cols)])

:handle
(fn [self _]
  [self [(s/set-keyed-children
           :slot/cols
           (mapv (fn [id]
                   [id (s/embed :board/column {:id id})])
                 (:column-ids self)))]])
```

Diff rules are intentionally small: a new key appends/prepends/inserts
one child fragment, a removed key removes that child subtree, changed
embed args re-initialise the child in place while preserving its root
iid, and a pure reorder emits one outer patch for the container.

**Restore-from-URL** lives at the intersection of keyed-children and
`:init-args-fn`. Because the slot doesn't exist until a
`:set-keyed-children` effect fires, components that re-create columns
from a query string emit the setup from `:start` based on the
just-initialised ids. See [Shareable views — URL as durable
state](tutorial.md#65--shareable-views--url-as-durable-state).

### `(s/context self)`

Return the application context injected by an embeddable kernel's
`:context-fn`. Standalone apps get nil unless they build their own
kernel. Host apps use this to pass dependencies such as DB handles to
component handlers without globals. See [Reading
dependencies](#reading-dependencies--app-vs-context-vs-principal) for
which primitive to pick.

### `(s/app)`

Return the opaque host value the embedder attached to the kernel via
the `:app` option. Typically a small map of long-lived dependencies
(`{:db ds :mail-fn …}`) that you do not want to serialise into
conversation state. Returns nil outside a runtime dispatch/render.
See the *Application boundaries* section under
[Embedding in a host Ring app](#embedding-in-a-host-ring-app) for the
contract, and [Reading
dependencies](#reading-dependencies--app-vs-context-vs-principal) for
when to reach for it instead of `s/context` or `s/principal`.

### `(s/principal)`

Return the authenticated principal stamped onto the current
conversation by `:principal-fn` at mint time. Returns nil for
anonymous conversations. The principal is fixed for the life of the
conversation — re-mint after login or logout. See the same
*Application boundaries* section for the rationale, and [Reading
dependencies](#reading-dependencies--app-vs-context-vs-principal) for
how it compares to `s/app` / `s/context`.

### `(s/back-button label)` / `(s/back-button label attrs)`

A small button wired to the conversation-level `[:back]`. Pops one
entry off `:conv/history`. Per-component wizard-style back buttons
should use `(s/on self :click :as :back-step)` plus an
`[:answer ::back]` instead.

### `(s/upload-attrs self)`  /  `(s/upload-frame self)`

Form attributes for a zero-JS multipart upload, plus the hidden
iframe target it posts into:

```clojure
[:form (s/upload-attrs self)
 [:input {:type "file" :name "file"}]
 [:button "Upload"]]
(s/upload-frame self)
```

The HTTP layer turns the multipart POST into a regular
`:upload-received` event dispatched to `self`; the parsed payload
arrives under `:payload`.

---

## Reading dependencies — `app` vs `context` vs `principal`

Three primitives, three lifecycles. Pick by where the value comes from
and how long it should live.

| primitive | source | lifecycle | EDN-serialised on the conversation? | available in `:init`? |
|---|---|---|---|---|
| `(s/app)` | `make-kernel :app` | kernel-lifetime | no | yes |
| `(s/context self)` | `:context-fn req` | per-conversation (captured at mint, fixed for life of the conversation) | yes (whatever `:context-fn` returned) | **no** — `self` doesn't exist yet during `:init` |
| `(s/principal)` | `:principal-fn req` (at mint) | conversation-lifetime | yes | yes (via the runtime; no `self` needed) |

### Pick by question

- *Is the value a long-lived JVM resource — a DB handle, a mail
  function, the system clock, an HTTP client?* → put it on `:app`
  via `make-kernel`. Read with `(s/app)`. It lives for as long as
  the kernel does and never touches the wire.
- *Does it change per conversation — a tenant id read from a
  subdomain, a feature-flag set parsed from headers, a request
  attribute the kernel should remember for the rest of the
  session?* → return it from `:context-fn`. Read with
  `(s/context self)` once `self` exists. It gets EDN-serialised
  onto the conversation, so it must be `pr-str` /
  `read-string`-clean.
- *Is the value the user identity, fixed for the conversation and
  swapped only by re-minting?* → return it from `:principal-fn`.
  Read with `(s/principal)`. Stored under `:conv/principal`; also
  EDN-clean.

`:app` is for *the host*. `:context-fn` is for *this conversation*.
`:principal-fn` is for *who is on the other end*.

### Common mistakes

- **Reading `(s/context self)` from `:init`.** Doesn't work — `:init`
  receives `args`, not `self`. The conversation's `:conv/context` is
  not in scope yet. Two answers:
  - If the value belongs to the kernel (a DB handle, etc.), read it
    with `(s/app)` from `:init`. `*current-app*` is bound during
    instantiation.
  - If it's per-conversation context, parse it from the request in
    `:init-args-fn` on `mount!` and pass it as `args` to `:init`:

    ```clojure
    (s/mount! "/dash" :app/dashboard
      {:init-args-fn (fn [req] {:tenant (s/query-value req "tenant")})})
    ```

    Don't try to thread `:conv/context` into `:init` itself — that's
    what `:init-args-fn` is for.
- **Putting a DB connection (or anything stateful and EDN-unclean)
  in `:context-fn`'s return or in `:principal-fn`'s return.**
  Both are persisted on the conversation and round-tripped through
  the store; an opaque JDBC connection crashes the file store.
  Put the connection on `:app`; `:context-fn` and `:principal-fn`
  return values must be plain data.
- **Reading `(s/app)` outside a dispatch — e.g. in a top-level
  helper function or a test that never booted a kernel.** Returns
  `nil`. For tests, either run via `s/replay` against a registered
  flow inside `(s/with-app {:db stub} …)`, or pass the dependency in
  as a function argument.

### A worked migration

Suppose a component reads the database through `:app` everywhere:

```clojure
(s/defcomponent :notes/list
  :init   (fn [{:keys [filter-tag]}]
            (let [{:keys [db]} (s/app)]
              {:rows       (db/query db {:tag filter-tag})
               :filter-tag filter-tag}))
  :render (fn [self]
            [:ul (s/root-attrs self)
             (for [row (:rows self)] [:li (:title row)])]))
```

Switch the DB choice to per-conversation (multi-tenant: each
conversation talks to a different shard, picked by host header):

```clojure
;; In the host's :context-fn:
(fn [request]
  {:db-key (tenant-of (get-in request [:headers "host"]))})

;; The component now reads the shard from context once self exists.
;; :init can't see context, so it carries the key in via init args:
(s/mount! "/notes" :notes/list
  {:init-args-fn (fn [req] {:db-key (tenant-of (-> req :headers (get "host")))})})

(s/defcomponent :notes/list
  :init   (fn [{:keys [db-key filter-tag]}]
            ;; :app still holds the connection pool, looked up by key.
            (let [pools (:db-pools (s/app))]
              {:rows       (db/query (get pools db-key) {:tag filter-tag})
               :filter-tag filter-tag
               :db-key     db-key}))
  :handle (fn [self {:keys [event payload]}]
            ;; In handlers, self is in scope — context is readable directly:
            (let [{:keys [db-key]} self
                  pool             (get (:db-pools (s/app)) db-key)]
              …)))
```

Two takeaways: keep the *connection* on `:app`; let the *choice*
(`db-key`) flow through `:context-fn` + `:init-args-fn` and onto
`self`. Component code becomes serialisable without dragging the
JDBC pool through the file store.

For the architectural reasoning behind this split, see
[`docs/decisions/0004-app-store-and-principal.md`](decisions/0004-app-store-and-principal.md).

---

## Stock UI components

Four canonical dialogs, registered automatically on first use. Each
function returns an embed spec for a regular component — you can pass it
to `s/call` or `s/await` exactly like your own embeds.

| function | answers with | purpose |
|---|---|---|
| `(s/confirm "Save?")` | `true` / `false` | Yes/No |
| `(s/prompt "Name?")` / `(s/prompt "Name?" "default")` | typed string, the supplied default if the user submits unchanged, or `s/cancel` | text input |
| `(s/choose ["a" "b"] "Pick one:")` | the picked element or `s/cancel` | one-of-N |
| `(s/info "Saved.")` | `:ok` | informational, single OK |

`s/cancel` is the sentinel for cancelled prompts. Compare with `=`:

```clojure
(when (= answer s/cancel) …)
```

All four are visually styled by the stock `/stube/ui.css`, which the
shell links by default. Pass `(s/start! {:ui-css? false})` to
disable and ship your own.

---

## Lifecycle and mounting

### `(s/mount! path flow-id)` / `(s/unmount! path)` / `(s/mounts)`

Register a flow at a URL path. `path` is a string like `"/wizard"`;
`flow-id` is the namespaced keyword of a registered component.
`mounts` returns the current standalone path→flow map.

`mount!` also accepts `{:init-args-fn f}`. The function receives the
Ring request for the shell GET and returns the init args passed to the
root component:

```clojure
(s/mount! "/counter" :demo/counter
  {:init-args-fn (fn [req]
                   {:n (parse-long (or (s/query-value req "n") "0"))})})
```

For restore-from-URL into a keyed-children slot, pair `:init-args-fn`
with `:start` so the slot exists on first render —
see [URL as a projection of state](#url-as-a-projection-of-state) and
the [Shareable views tutorial](tutorial.md#65--shareable-views--url-as-durable-state).

`unmount!` removes a previously-mounted path. It does **not** end live
conversations rooted there — those keep running until the SSE channel
closes or you call `s/end!`. The use case is dev REPL workflow: drop a
mount before re-registering at the same path with different opts. In a
production app you typically mount once at boot and never call
`unmount!`.

### `(s/query-value request param-name)`

Read one decoded query parameter directly from a Ring request, without
requiring params middleware. It is mostly useful inside `:init-args-fn`.

### `(s/start!)`  /  `(s/start! opts)`

Start the http-kit server. Idempotent: a second call stops the old
one first.

Options:

| key | default | meaning |
|---|---|---|
| `:port` | 8080 | TCP port |
| `:store` | `(s/in-memory-store)` | persistence backend |
| `:ui-css?` | `true` | link the stock `/stube/ui.css` |
| `:halos?` | `false` | enable dev halos (per-conv via `?halos=1`) |
| `:app` | `nil` | host-app value returned by `(s/app)` |
| `:principal-fn` | `nil` | `(fn [request] principal)` stamped at mint time and returned by `(s/principal)` |
| `:conversation-ttl` | `nil` | reaper TTL (`java.time.Duration` or millis) |
| `:reaper-interval` | 60000 | reaper interval |

### `(s/stop!)`

Stop the running server.

### `(s/active-conversations)`  /  `(s/end! cid)`

Inspect or forcibly end a live conversation.

---

## Embedding in a host Ring app

This surface lives in `dev.zeko.stube.embed` and
`dev.zeko.stube.adapter.ring` rather than `dev.zeko.stube.core`, because
it is for host-framework integration rather than component authorship.

```clojure
(require '[dev.zeko.stube.adapter.ring :as stube-ring]
         '[dev.zeko.stube.embed :as stube])

(def k
  (stube/make-kernel
    {:base-path "/widget"
     :session-id-fn (fn [request] (get-in request [:session :id]))
     :context-fn    (fn [request] {:db (:db request)})}))

(stube-ring/ring-routes k)
```

Stable functions:

| function | purpose |
|---|---|
| `(stube/make-kernel opts)` | Create an isolated runtime instance. |
| `(stube/mint-conversation! k root-id init-args request)` | Register a new conversation and return its cid. |
| `(stube/shell-for k cid)` | Return a Hiccup fragment for the host layout. |
| `(stube/head-tags k)` | Return the CSS/script Hiccup nodes required by `shell-for`. |
| `(stube/dispatch! k cid event)` | Dispatch into live state and return produced fragments. |
| `(stube/publish! k topic msg)` | Publish from host code into this runtime kernel. |
| `(stube/replay-with k root-id events)` | Pure replay against the kernel configuration; no live state mutation. Distinct from `core/replay` which is the kernel-less convenience used by component-author tests. |
| `(stube/halt! k)` | Close streams and clear runtime registries. |
| `(stube-ring/ring-routes k)` | Reitit route data for SSE/event/back/upload/assets. |
| `(stube-ring/ring-handler k)` | Plain Ring handler wrapping those routes. |

`opts` supports `:context-fn`, `:app`, `:principal-fn`, `:store`,
`:base-path`, `:session-id-fn`, `:on-conv-mint`, `:on-error`,
`:ui-css?`, `:halos?`, and `:root-selector`. Values
returned by `:context-fn` are available to handlers and lifecycle
hooks with `(s/context self)`. For when to pick which primitive,
see [Reading
dependencies](#reading-dependencies--app-vs-context-vs-principal).

`stube-ring/ring-routes` also accepts `{:mounts {"/path" :root/id}}`
or `{:mounts {"/path" {:flow-id :root/id :opts {:init-args-fn f}}}}`
to add shell routes beside the adapter endpoints. `:base-path` prefixes
the generated stube endpoints/assets (`/sse`, `/event`, `/stube/ui.css`,
etc.); mount paths are left exactly as supplied by the host app.

### Application boundaries: `:app` and `:principal-fn`

The kernel deliberately owns very little of the host's world. Two
embedder options pass the rest through cleanly.

`:app` is an opaque host value the kernel carries with itself for the
life of the kernel. It is typically a small map of dependencies that
component code would otherwise reach for at top level:

```clojure
(embed/make-kernel
  {:app {:db        datasource
         :mail-fn   send-mail!
         :now-fn    #(java.time.Instant/now)}})
```

Component code reads it via `(s/app)`:

```clojure
(s/defcomponent :app/invoice-preview
  :init (fn [{:keys [invoice-id]}]
          {:invoice ((:fetch-invoice (s/app)) invoice-id)})
  :render ...)
```

The value is **not persisted with the conversation** — it's the live
kernel's responsibility. Build it from JVM state on each
`make-kernel` call; do not store database connections or file handles
in conversation EDN.

`:principal-fn` is a `(fn [request] principal-or-nil)` called once
when a conversation is minted. The result is persisted on the
conversation under `:conv/principal` and surfaced through
`(s/principal)`:

```clojure
(embed/make-kernel
  {:principal-fn (fn [request] (get-in request [:session :user]))})

(s/defcomponent :app/dashboard
  :render
  (fn [self]
    (if-let [user (s/principal)]
      [:section "Hi, " (:name user)]
      [:a {:href "/login"} "Sign in"])))
```

The principal is **fixed at mint time**. If your app needs the user
to log out, change accounts, or otherwise switch identity, the host
should end the conversation (`(s/end nil)` from a handler, or
`(s/end! cid)` from admin code) and let the next request mint a
fresh one. The framework deliberately does not offer a
`(set-principal!)` operation; reusing one conversation across two
identities is the kind of bug `:principal-fn`-at-mint exists to
prevent.

Both options are also accepted by `s/start!` for the standalone
server:

```clojure
(s/start! {:app          {:db datasource}
           :principal-fn (fn [req] (-> req :session :user))})
```

---

## REPL / testing surface

### `(s/inspect cid)`

Pretty-print a compact summary of conversation `cid` and return it.
Returns nil if the conversation isn't active.

### `(s/tree cid)`  /  `(s/instance cid iid)`  /  `(s/conv-history cid)`  /  `(s/where type-kw)`

Halos REPL views:

- `tree` prints the component tree.
- `instance` returns the instance map for `iid`.
- `conv-history` summarises `:conv/history`.
- `where` returns the `{:file … :line …}` source location captured
  for `type-kw` at `defcomponent` time.

### `(s/dispatch conv event)`

Pure event dispatch. Returns `[conv' fragments]`. Useful from tests:

```clojure
(let [conv     (-> {} build-baseline)
      [c' fr]  (s/dispatch conv {:instance-id "ix-1"
                                 :event       :submit
                                 :signals     {:draft "hi"}})]
  …)
```

### `(s/boot flow-id)`

Returns the initial effects vector for a fresh conversation rooted at
`flow-id`. Pure; used by the http layer on first SSE connect and by
`s/replay`.

### `(s/replay events)`  /  `(s/replay baseline events)`

Walk a sequence of events through a baseline, returning
`[conv' fragments]`. `baseline` is either an existing conversation
map *or* a flow keyword (in which case `replay` boots a fresh
conversation first).

```clojure
(s/replay :standup/board
  [{:event :add :signals {:draft "ship docs"}}
   {:event :add :signals {:draft "open PR"}}])
```

Event maps may omit `:instance-id` (current top frame) and
`:signals` (`{}` by default). An event may be a function of the
current conv returning such a map, which is handy when you need to
read an iid that didn't exist until the previous event ran.

---

## Persistence

### `(s/in-memory-store)`

The default. No-op `save!`/`delete!`; the in-process atom in the
server is the only copy of the truth. Good for tests, demos, and
deployments where crash-resume isn't required.

### `(s/file-store dir)`

One EDN file per conversation under `dir`. Atomic temp-file +
rename; reads use `clojure.edn/read-string` with no eval.
Conversations that contain non-EDN values (almost always a
`defflow` cloroutine continuation) are skipped with a warning to
`*err*` — the live conversation is unaffected, only its disk copy
is stale. This is the documented `defflow` durability boundary;
see *Durable flows: defflow vs. task components* in the tutorial
for the EDN-clean shape.

```clojure
(s/start! {:store (s/file-store "/var/lib/stube/convs")})
```

---

## Cheat sheet

```clojure
;; Component shape
(s/defcomponent :my/widget
  :init   (fn [args] state-map)
  :keep   #{:signal-keys}
  :render (fn [self] hiccup)
  :handle (fn [self {:keys [event payload signals]}] …)
  :start  (fn [self] …)        ; once at instantiation
  :stop   (fn [self] …)        ; just before removal
  :wakeup (fn [self] …)        ; after history/persistence restore
  :url    (fn [self] "/x?q=…") ; root-only; auto-emits :history on change
  :on-foo (fn [self answer] …) ; resume key
  )

;; Effects
[(s/call :child)]              [(s/call :child args :on-key)]
[(s/become :other)]            [(s/call-in-slot :slot :child args :on-key)]
[(s/answer v)]                 [(s/end v)]
[(s/answer-error ex)]          ; parent declares :on-error-<key>
[(s/patch hiccup)]             [(s/patch-signals m)]
[(s/history :replace "/x")]    [(s/io #(…))]
[(s/after 1000 :tick)]
[(s/subscribe :topic :ev)]     [(s/unsubscribe)]
[(s/back)]                     [(s/execute-script "…")]
[(s/set-keyed-children :slot/x [[id (s/embed :child args)]])]

;; Hiccup
(s/root-attrs self {…} (s/on self :submit))
(s/on self :click :as [:pick id])
(s/on-target parent-iid :click :as [:pick id])
(s/bind :draft)                (s/local-bind self :text)
(s/preserve self :widget)      (s/on-mount   self :widget "mount(el)")
                               (s/on-unmount self :widget "el.cm?.destroy()")
(s/render-slot self :slot/x)
(s/keyed-children self :slot/x)
(s/back-button "Back")
(s/upload-attrs self)          (s/upload-frame self)

;; Stock dialogs
(s/confirm "?")    (s/prompt "?" "default")
(s/choose [..])    (s/info "Done.")

;; Lifecycle
(s/mount! "/x" :my/flow)
(s/start! {:port 8080 :store (s/file-store "convs")})

;; REPL
(s/inspect cid)
(s/tree cid)
(s/replay :my/flow [{:event :submit :signals {:draft "x"}}])
```

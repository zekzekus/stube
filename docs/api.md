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
| `(s/dispatch-to target event)` | `[:dispatch-to iid event]` | asynchronously deliver `event` to a known instance in the same conversation — see [Direct cross-instance dispatch](#direct-cross-instance-dispatch) |
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

### Direct cross-instance dispatch

`(s/dispatch-to target route-event)` is the small primitive for "this
child wants the parent (or some other known peer) to do something."
`target` is either an instance map or an iid string;
`route-event` follows the same shape as `(s/on … :as route-event)` —
a keyword or `[event & payload]` vector. The event lands on the
target's `:handle` exactly as if a button wired with
`(s/on-target target :click :as route-event)` had been clicked.

```clojure
(s/defcomponent :notes/search-result
  :render
  (fn [self]
    [:li (s/root-attrs self)
     [:button (s/on self :click :as [:pick (:note-id self)]) (:title self)]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      ;; The child closes itself *and* tells the parent to open
      ;; the chosen note — both in one handler, no global pub/sub,
      ;; no fake DOM siblings, no second POST chained in data-on:click.
      :pick [(s/answer :closed)
             (s/dispatch-to (:instance/parent self) [:open payload])])))
```

Why an effect instead of a synchronous function call? The runtime
schedules the dispatch on a background thread so the current handler
completes first; this keeps the per-cid lock non-reentrant and avoids
fan-out amplification under contention. If the target instance is
gone by the time the future runs the event is dropped silently (the
standard stale-event path) — no special-casing needed.

Pick the right tool:

- `s/answer` — pop this frame and deliver a value up the call stack.
  The cleanest answer-up channel; use it when the caller `(s/call …)`-ed
  this frame and is waiting for the result.
- `s/dispatch-to` — notify a *known* peer (parent or sibling) in the
  same conversation without unmounting. Use [`s/child-iid`](#schild-iid-self-slot-key)
  to find the iid of a fixed slot child, `:instance/parent` for the
  parent.
- `s/publish-local!` — notify zero-or-many anonymous peers in the same
  conversation; the sender does not know (or care) who is listening.
- `s/publish!` — like the local form, but every subscriber of `topic`
  on the *kernel* hears it (across conversations).

### `(s/publish! topic msg)`

A regular function, not an effect. From component code it targets the
active runtime kernel; outside a dispatch it targets the standalone
server kernel. Delivers `msg` asynchronously to every live subscriber
of `topic`. Returns the number of subscribers targeted. Stale
subscribers are ignored.

### `(s/publish-local! topic msg)`

Like `s/publish!` but only delivers to subscribers in the **current
conversation** — every other conversation's subscribers on the same
topic stay silent. Reads the active cid from the runtime binding;
throws when called outside a dispatch/render context.

Use this for parent/child or sibling channels that must not leak
across browser tabs or users — for example, a notes shell that
publishes `:note-changed` so its open columns can refresh, without
shouting at every other open browser tab on the same kernel:

```clojure
(s/defcomponent :notes/shell
  :handle (fn [self {:keys [event]}]
            (case event
              :save (do (db/save! …)
                        (s/publish-local! :note-changed (:note-id self))
                        self))))

(s/defcomponent :notes/column
  :start (fn [self] [self [(s/subscribe :note-changed :on-note-changed)]])
  :on-note-changed (fn [self _ev] (assoc self :stale? true)))
```

Returns the number of subscribers targeted in the current
conversation. Stale subscribers are ignored, same as `s/publish!`.

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

**Event modifiers.** A trailing map (optional 5-arity) becomes Datastar
event modifiers in the attribute name — `debounce`, `throttle`,
`stop`, `prevent`, etc.

```clojure
[:input  (s/on self :input :as :search {:debounce "300ms"})]
[:button (s/on self :click :as :submit {:stop true :prevent true})]
```

Map values may be strings, numbers, keywords, or `true` for
flag-only modifiers. Map entries are sorted by key name so the
generated attribute is stable across renders; pass a vector of
`[k v]` pairs when you need to preserve order (e.g. for
`__debounce.300ms.leading`-style positional arguments to one
Datastar modifier).

### `(s/on-target target dom-event)`  /  `(s/on-target target dom-event :as route-event [modifiers])`

Like `on`, but posts to an explicit target instead of `self`. `target`
may be either a bare instance-id string or an instance map. Use this
for cross-instance controls: for example, a child rendering a link
that should notify a stable parent without answering and
disappearing. Accepts the same `modifiers` map as `on`.

```clojure
[:button (s/on-target (s/instance-id parent) :click :as [:open (:id note)])
 "Open"]
[:button (s/on-target parent :click :as :close) "x"]    ; instance map also OK
```

### `(s/on-parent self dom-event)`  /  `(s/on-parent self dom-event :as route-event [modifiers])`

Shorthand for the most common cross-instance pattern: post to
`self`'s parent. Equivalent to
`(s/on-target (:instance/parent self) …)` but reads as a stable seam
rather than reaching into the instance map. Throws if `self` has no
parent.

```clojure
[:button (s/on-parent self :click :as [:close (:id note)]) "Close"]
```

### `(s/instance-id self)`

Return `self`'s `:instance/id`, throwing with a clear message if it is
absent. Use this in pure rendering helpers that pass an iid out instead
of destructuring `:instance/id` directly — the framework owns the wire
shape, and the public name is the stable seam.

### `(s/child-iid self slot-key)`

Return the iid of the embedded child mounted under `slot-key` on
`self`, or nil when the slot is unknown. Reads the same data as
`(get-in self [:instance/children slot-key])` but documents the
contract so call sites don't have to reach into framework-managed
instance keys directly.

Useful when a parent needs to address its child by id — for example,
to route a `(s/dispatch-to)` effect at a known slot child or to build
an `s/event-url` / `s/on-target` against it:

```clojure
:render
(fn [self]
  (let [search-iid (s/child-iid self :slot/search)]
    [:div (s/root-attrs self)
     [:button (s/on-target search-iid :click :as :focus) "Find"]
     (s/render-slot self :slot/search)]))
```

### `(s/event-url iid route-event)`

Low-level helper used by `on` and `on-target`. It returns the URL that
Datastar should POST to for `iid` and `route-event`, including the EDN
payload query parameter for structured events. Most code should prefer
`on`; reach for `event-url` only when writing a custom Datastar
expression.

## Client-side seam

Most stube components render purely from the server: SSE patches morph
the DOM, Datastar wires events back. When a feature genuinely needs
in-browser JS — a code-editor, a drag-and-drop list, a chart, an
autocomplete — stube pins *where* that code lives and *how* it
connects to a component. Hosts write plain JavaScript; the framework
owns the seam.

### Every component root is addressable

`s/root-attrs` always emits two extra attributes derived from the
component's registered id:

```html
<div id="ix-1f"
     data-stube-component="notes/shell"
     class="stube-c-notes-shell …">
```

CSS selectors can target either the `data-` attribute or the
`stube-c-<ns>-<name>` class. Behaviors and module code can locate
every instance with one query selector. Append your own `:class` and
it's concatenated, never replaced.

### `(s/behavior self behavior-id args)`

Attach a client-side behavior to this element.

```clojure
[:div (s/behavior self :notes/cm6-editor {:doc-id (:doc-id self)})]
```

renders as

```html
<div data-stube-behavior="notes/cm6-editor" data-stube-arg-doc-id="…">
```

The behaviors bridge loaded with the shell discovers the marker on
every `stube:patched`, lazy-imports the matching module from
`<base-path>/behaviors/notes/cm6-editor.js`, and drives its
lifecycle. Behaviors are the canonical seam for non-trivial client
code.

The module's default export is a small object:

```js
// resources/stube_behaviors/notes/cm6_editor.js
export default {
  mount(el, ctx)    { /* run once when el first appears */ },
  patched(el, ctx)  { /* run on each subsequent patch that left el alive */ },
  unmount(el, ctx)  { /* run once when el detaches from the DOM */ }
}
```

Every callback is optional. `ctx` is one blessed shape:

| field | value |
|---|---|
| `ctx.el` | the host element |
| `ctx.args` | `{camelCasedKey: stringValue}` parsed from `data-stube-arg-*` |
| `ctx.basePath` | the kernel's base path, for building further URLs |
| `ctx.signals` | `{get(name), set(name, value), patch(map)}` Datastar accessor |
| `ctx.setSignal(name, value)` | alias for `ctx.signals.set` |
| `ctx.patchSignals(map)` | alias for `ctx.signals.patch` — write many signals at once |
| `ctx.fetch(eventUrl, opts?)` | POST to a stube event URL (`event-url` on the server side) |

Writes (`set` / `patch` / `setSignal` / `patchSignals`) flow through
Datastar's **public attribute API** rather than any Datastar-internal
handle. The component must render
[`s/signal-mirror`](#ssignal-mirror-signal--ssignal-mirror-signal-case-)
once per signal a behavior intends to write; the bridge locates that
hidden `<input>` by its `data-stube-signal-mirror="<wire>"` marker,
sets `.value`, and dispatches a standard DOM `input` event. Datastar's
own `data-bind` handler propagates the value into the signal store —
no coupling to any Datastar-internal symbol, no version-fragile ESM
import.

Reads (`get`) prefer the mirror's `.value` (same DOM source of truth
as writes) and fall back to a best-effort `globalThis.ds` lookup.

The signal name follows the kernel's `:signal-case` choice — `:kebab`
hosts write `ctx.setSignal("edit-markdown", v)`, `:camel` hosts write
`ctx.setSignal("editMarkdown", v)`. If no matching mirror is found,
the bridge logs a `console.warn` pointing at the missing
`s/signal-mirror` site rather than silently no-op'ing.

`args` values stringify on the way out (`name` for keywords, `str`
for numbers/booleans, `pr-str` for anything else). Pass small scalars
that mirror the server's view of the element — avoid round-tripping
large blobs through DOM attributes.

Use `ctx.setSignal` / `ctx.patchSignals` when the behavior owns a
live widget (CodeMirror, a chart, a drag-and-drop tracker) whose
internal state should mirror into Datastar signals without a sibling
hidden-input shim. The signal must already exist on the page —
either declare it on the server with `:signals` / `s/bind` or seed
it from the same render that attaches the behavior.

### `(s/preserve self label)` / `(s/on-mount self label expr)` / `(s/on-unmount self label expr)`

`preserve` marks the host element with `data-stube-preserve`; stube's
preserve bridge lets future morphs merge host attributes while
skipping the child subtree — the right tool when a behavior owns DOM
children outside the server's render tree. `on-mount` emits a Datastar
`data-init` expression only before this instance has rendered;
`on-unmount` attaches a `data-stube-on-unmount` expression that fires
once when the host element detaches. Use these for ad-hoc, one-off
glue where setting up a full behavior file would be overkill.

```clojure
[:div (merge {:class "editor-host"}
             (s/behavior   self :notes/cm6-editor {:doc-id (:doc-id self)})
             (s/preserve   self :editor))]
```

For tiny escape-hatch widgets that don't deserve a behavior file:

```clojure
[:div (merge (s/preserve   self :sparkline)
             (s/on-mount   self :sparkline "renderSparkline(el)")
             (s/on-unmount self :sparkline "el.spark?.destroy()"))]
```

### CSS: component conventions

Three layers, smallest first.

**Per-component stylesheets.** If
`resources/stube_styles/<ns>/<name>.css` exists, the shell auto-emits
a `<link>` for it. Selectors target the component by its
auto-attribute:

```css
/* resources/stube_styles/notes/shell.css */
@layer stube.notes {
  [data-stube-component="notes/shell"] { display: grid }
  [data-stube-component="notes/shell"] > .column { width: 18rem }
}
```

**Inline `:styles` on `defcomponent`.** For small components that
don't justify a file:

```clojure
(s/defcomponent :foo/bar
  :styles "& { display: grid } & > .row { gap: .5rem }"
  :render …)
```

Each `&` is replaced with the component's
`[data-stube-component="foo/bar"]` selector at head-emit time. The
combined block is rendered once in `<head>`.

**Plain global CSS.** Anything in your host's normal stylesheet
tree. Hangs off the auto class (`stube-c-notes-shell`) or the data
attribute — both are stable, both are framework-owned.

### JS: `:modules` on `defcomponent`

A component can declare module dependencies that should be loaded as
`<script type="module">` on every page where the component is
registered:

```clojure
(s/defcomponent :notes/shell
  :modules ["notes/zoom"]
  :render …)
```

resolves to `<base-path>/modules/notes/zoom.js`, served from
`resources/stube_modules/notes/zoom.js`. Modules are deduped across
all registered components — declare the same id from two components
and you still get one script tag. Use modules for global setup
(keyboard handlers, Datastar plugin registration, third-party SDK
warm-up). Use behaviors for per-element work.

### Asset layout

| convention | served at | served from |
|---|---|---|
| component stylesheet | `/<base>/styles/<ns>/<name>.css` | `resources/stube_styles/<ns>/<name>.css` |
| component module | `/<base>/modules/<id>.js` | `resources/stube_modules/<id>.js` |
| component behavior | `/<base>/behaviors/<ns>/<name>.js` | `resources/stube_behaviors/<ns>/<name>.js` |

Asset names are restricted to `[A-Za-z0-9_-]`; traversal segments
return 400 and never touch the filesystem.

### Browser lifecycle: `stube:patched`

Stube's preserve bridge dispatches a `CustomEvent` on `document` after
every successful Datastar `patch-elements` morph driven by an SSE
event. Host pages can listen for it to hook scroll/focus restoration,
title measurement, third-party widget reflow, or optimistic UI cleanup
without inventing their own MutationObserver:

```javascript
document.addEventListener("stube:patched", (event) => {
  // event.detail.selector — CSS selector the patch targeted (or null
  //                          when the patch addressed elements by id)
  // event.detail.mode     — Datastar morph mode (outer/inner/…) or null
});
```

The event is best-effort and fires after the morph has landed. It
bubbles `false`, so attach the listener on `document`.

### `on-unmount` semantics

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

## More Hiccup helpers

### `(s/bind signal)` / `(s/bind signal {:case …})`

Two-way binding for an input. Datastar updates the signal
client-side as the user types; the next event ships the value back
to the server.

```clojure
[:input (merge {:name "draft"} (s/bind :draft))]
```

Combine with `:keep #{:draft}` so the value is merged onto `self`
before `:handle` runs.

**Wire casing.** Two valid casings, picked once per host on
`make-kernel`'s `:signal-case` option:

- `:kebab` (default) keeps Clojure kebab keywords identical on the
  wire — `(s/bind :edit-markdown)` sends `edit-markdown` and the
  handler reads it back as `:edit-markdown`.
- `:camel` lets Datastar's default camel-casing apply —
  `(s/bind :edit-markdown)` sends `editMarkdown` and the handler
  reads it back as `:editMarkdown`. Pick this when any inline
  Datastar expression references the signal
  (`data-on:input="$editMarkdown = ..."`), because JS identifiers
  can't contain dashes.

A per-call `{:case :camel}` / `{:case :kebab}` opt overrides the
kernel default for one binding; otherwise the kernel default
applies. The same resolution covers `s/local-bind`, `s/$`, and
`s/signal`, so the read and write sides stay in lock-step.

### `(s/local-bind self signal)`  /  `(s/local-signal self signal)`

Like `bind`, but the wire signal name is suffixed with the instance
id. Use this whenever the same logical signal name might appear in
two instances on the same page (the editor pattern, multi-row
forms, etc.). Read the value back from `self` under the *logical*
name — `:keep #{:answer}` lifts the local signal onto `:answer`
for you.

Accepts the same `{:case …}` opt as `bind`; `local-signal` returns
just the namespaced wire key, useful if you need to build a Datastar
expression by hand.

### `(s/signal-mirror signal)` / `(s/signal-mirror signal {:case …})`

Returns attrs for a hidden two-way-bound `<input>` that gives a
client-side behavior a write seam for `signal`:

```clojure
[:input (s/signal-mirror :edit-markdown)]
```

Emits `<input type="hidden" data-bind:<wire>
data-stube-signal-mirror="<wire>">` with the wire name resolved
through the same casing rules as `s/bind`. The marker attribute is
what the `behaviors.js` bridge looks up when a behavior calls
`ctx.setSignal(<wire>, value)` — the bridge sets the input's
`.value` and dispatches `input`, and Datastar's `data-bind` handler
propagates into the signal store. This is the canonical write path
for widget-owning behaviors (CodeMirror, Chart.js, drag-and-drop)
because it uses only Datastar's public `data-bind` attribute API —
no coupling to any Datastar-internal handle.

Render one mirror per signal a behavior wants to write. If a
behavior calls `ctx.setSignal` for a signal with no matching mirror
in scope, the bridge logs a `console.warn` pointing at the missing
helper call rather than silently no-op'ing.

### `(s/$ signal)` / `(s/signal event signal)` / `(s/signal-wire-name signal)`

Casing-aware companions for the signal helpers above. All three
follow the same per-call → kernel-default → `:kebab` resolution as
`s/bind`.

- `(s/$ :create-title)` returns the Datastar `$ref` string —
  `"$create-title"` under `:kebab`, `"$createTitle"` under `:camel`
  — for use in inline expressions:

  ```clojure
  [:input {:data-on:input (str (s/$ :create-slug) " = slugify("
                               (s/$ :create-title) ".value)")}]
  ```

- `(s/signal event :edit-markdown)` reads a posted signal off an
  event in the active casing — `:edit-markdown` under `:kebab`,
  `:editMarkdown` under `:camel`. Use it in `:handle` so the read
  side mirrors `s/bind` without each handler having to know which
  casing is active.

- `(s/signal-wire-name :edit-markdown)` is the underlying
  translation: returns the wire-side string. Mostly useful when a
  host has to build an attribute key or a Datastar expression by
  hand.

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

**`:rerender-parent?` opt.** By default the reconcile's per-child
fragments satisfy the kernel's "this dispatch already rendered" check
and the parent's auto-render is skipped — the right call when the
parent's hiccup outside the container has not changed. When the
parent also displays state that depends on the reconciled set
(a topbar count, an empty-state vs ledger toggle, a "save"
button's enabled state), pass `{:rerender-parent? true}` so the
kernel renders the parent on top of the reconcile with the updated
slot state in scope:

```clojure
[self [(s/set-keyed-children :slot/cols pairs
                             {:rerender-parent? true})]]
```

This is a no-op on the parent's very first paint — the normal
render-frame on the way out already picks up the populated slot
state in one shot — so it is always safe to pass.

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

### `(s/conversation-id)`

Return the id of the conversation currently being dispatched or
rendered, or nil outside a runtime binding (e.g. `core/replay` with
no kernel attached). Useful when a component needs to namespace a
pub/sub topic or asset URL by the active conversation without
threading the cid through `:children` args. Pairs with
[`(s/publish-local! topic msg)`](#spublish-local-topic-msg) for
intra-conversation messaging.

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

All four are visually styled by the stock `/ui.css`, which the
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
| `:ui-css?` | `true` | link the stock `/ui.css` |
| `:base-css` | `[]` | extra stylesheet URLs `head-tags` emits on every shell |
| `:eager-scripts` | `[]` | inline JS snippets emitted as a synchronous `<script>` before any module |
| `:signal-case` | `:kebab` | wire casing for `s/bind` / `s/local-bind` / `s/$` / `s/signal`; pick `:camel` if any inline Datastar expression references a signal |
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
`:ui-css?`, `:base-css`, `:eager-scripts`, `:signal-case`, `:halos?`,
and `:root-selector`. Values
returned by `:context-fn` are available to handlers and lifecycle
hooks with `(s/context self)`. For when to pick which primitive,
see [Reading
dependencies](#reading-dependencies--app-vs-context-vs-principal).

`stube-ring/ring-routes` also accepts `{:mounts {"/path" :root/id}}`
or `{:mounts {"/path" {:flow-id :root/id :opts {:init-args-fn f}}}}`
to add shell routes beside the adapter endpoints. `:base-path` prefixes
the generated stube endpoints/assets (`/sse`, `/event`, `/ui.css`,
etc.); mount paths are left exactly as supplied by the host app.

### Host-level head injection: `:base-css` and `:eager-scripts`

Use these when the host has assets that must be on the page from the
first byte — independent of which components happen to be registered.

`:base-css` is a vector of stylesheet URLs. Every call to
`head-tags` (and the standalone shell) emits one `<link
rel="stylesheet">` per entry, in order, before component-derived
stylesheets. Use it when a host's CSS is shared between stube
routes and non-stube routes (an `/about` page that doesn't go
through `embed/head-tags`), so head-tags alone can't cover both.

```clojure
(embed/make-kernel
  {:base-css ["/css/notes.css"]
   :base-path "/notes"})
```

`:eager-scripts` is a vector of inline JS snippets. They are
concatenated into a single synchronous `<script>` block emitted
*before* any `type="module"` script — the only way to seed
`window.<X>` for inline Datastar expressions
(`data-on:input="window.X.foo($value)"`) that may evaluate before
the deferred ESM graph finishes parsing. Snippets are emitted
verbatim, so the host owns the contents (no escaping is performed).

```clojure
(embed/make-kernel
  {:eager-scripts
   ["window.NotesAttrs = {createForm:'create-form'};"
    "window.NotesSignals = {editTitle:'editTitle'};"]})
```

For most apps neither option is needed: the per-component
stylesheet convention and the `:modules` declaration on
`defcomponent` are the preferred seams.  Reach for these when the
component-scoped placement isn't expressive enough.

**Renderer constraint.** `embed/head-tags` returns a Hiccup tree
whose `<script>` / `<style>` bodies (`:eager-scripts`, inline
`:styles`) are wrapped in chassis `RawString`. Hosts that render
through chassis get the bodies emitted verbatim. Hosts that render
through hiccup2 / rum / reagent SSR must re-wrap those instances in
their own raw primitive before emitting, or the bodies are
HTML-escaped and inline scripts fail to parse.

### Signal wire casing: `:signal-case`

Datastar's `data-bind:<key>` attribute normalises the wire key
(`<key>` is case-insensitive HTML), so the framework has to pick a
canonical form. `:signal-case` picks it once for the whole kernel
instead of leaking the decision into every `s/bind` call.

```clojure
(embed/make-kernel
  {:signal-case :camel})           ; or :kebab (default)
```

- `:kebab` (default) keeps Clojure kebab-case keywords identical on
  the wire — `(s/bind :edit-markdown)` ships `edit-markdown` and the
  handler reads it back as `:edit-markdown`. Pick this when all
  signal access is from Clojure.
- `:camel` lets Datastar's default camel-casing apply —
  `(s/bind :edit-markdown)` ships `editMarkdown` and the handler
  reads it back as `:editMarkdown`. **Pick this when any inline
  Datastar expression references a signal**
  (`data-on:input="$editMarkdown = ..."`), because JS identifiers
  can't contain dashes.

The same casing covers `s/local-bind`, `s/$` (inline-expression
`$ref`), and `s/signal` (event lookup). A per-call `{:case ...}` opt
on any of those helpers wins over the kernel default for that one
call. See [`s/bind`](#sbind-signal--sbind-signal-case-) and
[`s/$` / `s/signal`](#s-signal--ssignal-event-signal--ssignal-wire-name-signal)
for the read-side helpers that mirror it.

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
  :init    (fn [args] state-map)
  :keep    #{:signal-keys}
  :render  (fn [self] hiccup)
  :handle  (fn [self {:keys [event payload signals]}] …)
  :start   (fn [self] …)        ; once at instantiation
  :stop    (fn [self] …)        ; just before removal
  :wakeup  (fn [self] …)        ; after history/persistence restore
  :url     (fn [self] "/x?q=…") ; root-only; auto-emits :history on change
  :styles  "& { … }"             ; inline CSS, & = this component's selector
  :modules ["foo/bar"]           ; eager <script type=module> deps
  :on-foo  (fn [self answer] …)  ; resume key
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
(s/root-attrs self {…} (s/on self :submit))      ; emits id + data-stube-component + class
(s/on self :click :as [:pick id])
(s/on-target parent-iid :click :as [:pick id])
(s/bind :draft)                (s/local-bind self :text)
(s/behavior self :ns/name {:k v})                ; attach a client-side behavior
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

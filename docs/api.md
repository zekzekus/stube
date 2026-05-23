# API reference

Component-author APIs documented here live in the
`dev.zeko.stube.core` namespace — the surface I try to keep stable
while the rest of the project evolves. Aliased as `s` by convention:

```clojure
(require '[dev.zeko.stube.core :as s])
```

Anything outside `dev.zeko.stube.core` is **internal** and may move
without notice, except the embedder surface documented in
[`dev.zeko.stube.kernel`](#embedding-in-a-host-ring-app). If you find
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
| `:init` | `(fn [args] state-map)` | once at instantiation |
| `:keep` | `#{:k1 :k2 …}` | listed Datastar signals are merged onto `self` on every event |
| `:render` | `(fn [self] hiccup)` | whenever a frame needs re-rendering |
| `:handle` | `(fn [self {:keys [event payload signals]}] …)` | on every dispatched event |
| `:start` | `(fn [self] …)` | once, right after instantiation |
| `:stop` | `(fn [self] …)` | just before the frame/subtree is removed |
| `:wakeup` | `(fn [self] …)` | when a persisted/history-restored frame becomes live again |
| `:on-<key>` | `(fn [self answer-value] …)` | when a child `:answer`s under that resume key |
| `:children` | `{slot embed-spec}` or `(fn [self] …)` | declared once; the kernel instantiates eagerly |

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
- `try`/`catch` *across* an `await` is not supported in slice 1.
- The continuation is not EDN-serialisable, so flow conversations
  are skipped by `file-store`. Hand-rolled task components are
  EDN-clean.

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
| `(s/patch hiccup)` | `[:patch h]` | emit an extra DOM patch without changing the stack |
| `(s/patch-signals m)` | `[:patch-signals m]` | push a Datastar signal patch (writes signal values back to the browser) |
| `(s/execute-script js)` | `[:execute-script js]` | run literal JS in the browser (last-resort escape hatch) |
| `(s/history :replace url)` / `(s/history :push url)` | `[:history op url]` | sync the browser URL with `replaceState` / `pushState` |
| `(s/io thunk)` | `[:io fn]` | ask the active runtime to run `(thunk)` off the request thread; pure replay leaves it inert |
| `(s/after ms event)` | `[:after ms event]` | dispatch `event` to this instance after `ms` |
| `(s/subscribe topic event)` | `[:subscribe topic event]` | subscribe this instance to `topic`; messages arrive as `event` |
| `(s/unsubscribe)` / `(s/unsubscribe topic)` | `[:unsubscribe]` etc. | remove subscription(s) for this instance |
| `(s/set-keyed-children slot pairs)` | `[:set-keyed-children slot pairs]` | reconcile an ordered set of keyed child instances |
| `s/back` | `[:back]` | walk one step backward through the conversation's `:conv/history` |
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

### `(s/preserve self label)` / `(s/on-mount self label expr)`

Use these together for third-party widgets that own their child DOM.
`preserve` marks the host element with `data-stube-preserve`; stube's
stock shell bridge lets future morphs merge host attributes while
skipping the child subtree. `on-mount` emits a Datastar
`data-init` expression only before this instance has rendered, so the
widget constructor runs once.

```clojure
[:div (merge {:class "editor-host"}
             (s/preserve self :editor)
             (s/on-mount self :editor "mountEditor(el)"))]
```

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

### `(s/context self)`

Return the application context injected by an embeddable kernel's
`:context-fn`. Standalone apps get nil unless they build their own
kernel. Host apps use this to pass dependencies such as DB handles to
component handlers without globals.

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

## Stock UI components

Four canonical dialogs, registered automatically on first use. Each
function returns an embed spec for a regular component — you can pass it
to `s/call` or `s/await` exactly like your own embeds.

| function | answers with | purpose |
|---|---|---|
| `(s/confirm "Save?")` | `true` / `false` | Yes/No |
| `(s/prompt "Name?")` / `(s/prompt "Name?" "default")` | typed string or `s/cancel` | text input |
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
| `:conversation-ttl` | `nil` | reaper TTL (`java.time.Duration` or millis) |
| `:reaper-interval` | 60000 | reaper interval |

### `(s/stop!)`

Stop the running server.

### `(s/active-conversations)`  /  `(s/end! cid)`

Inspect or forcibly end a live conversation.

---

## Embedding in a host Ring app

This surface lives in `dev.zeko.stube.kernel` and
`dev.zeko.stube.adapter.ring` rather than `dev.zeko.stube.core`, because
it is for host-framework integration rather than component authorship.

```clojure
(require '[dev.zeko.stube.adapter.ring :as stube-ring]
         '[dev.zeko.stube.kernel :as stube])

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
| `(stube/replay k root-id events)` | Pure replay against the kernel configuration; no live state mutation. |
| `(stube/halt! k)` | Close streams and clear runtime registries. |
| `(stube-ring/ring-routes k)` | Reitit route data for SSE/event/back/upload/assets. |
| `(stube-ring/ring-handler k)` | Plain Ring handler wrapping those routes. |

`opts` supports `:context-fn`, `:store`, `:base-path`,
`:session-id-fn`, `:on-conv-mint`, `:on-error`, `:ui-css?`,
`:halos?`, `:route-style`, and `:root-selector`. Values returned by
`:context-fn` are available to handlers and lifecycle hooks with
`(s/context self)`.

`stube-ring/ring-routes` also accepts `{:mounts {"/path" :root/id}}`
or `{:mounts {"/path" {:flow-id :root/id :opts {:init-args-fn f}}}}`
to add shell routes beside the adapter endpoints. `:base-path` prefixes
the generated stube endpoints/assets (`/sse`, `/event`, `/stube/ui.css`,
etc.); mount paths are left exactly as supplied by the host app.

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
Conversations that contain non-EDN values (the common case is a
`defflow` cloroutine continuation) are skipped with a warning to
`*err*` — the live conversation is unaffected, only its disk copy
is stale.

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
  :on-foo (fn [self answer] …) ; resume key
  )

;; Effects
[(s/call :child)]              [(s/call :child args :on-key)]
[(s/become :other)]            [(s/call-in-slot :slot :child args :on-key)]
[(s/answer v)]                 [(s/end v)]
[(s/patch hiccup)]             [(s/patch-signals m)]
[(s/history :replace "/x")]    [(s/io #(…))]
[(s/after 1000 :tick)]
[(s/subscribe :topic :ev)]     [(s/unsubscribe)]
[s/back]                       [(s/execute-script "…")]
[(s/set-keyed-children :slot/x [[id (s/embed :child args)]])]

;; Hiccup
(s/root-attrs self {…} (s/on self :submit))
(s/on self :click :as [:pick id])
(s/on-target parent-iid :click :as [:pick id])
(s/bind :draft)                (s/local-bind self :text)
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

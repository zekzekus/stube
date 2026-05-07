# stube — design document

> A Seaside-inspired Clojure web framework where UI flows read as linear code,
> resumed across HTTP via virtual threads and pushed to the browser over
> Datastar SSE.

Status: **draft v0.1** — pre-prototype. Open questions are flagged ⚠.

---

## 1. Motivation

Most Clojure web stacks treat each HTTP request as an isolated event, leaving
the developer to thread application state through routes, handlers, and
templates. Seaside (Smalltalk) demonstrated a radically different model:

> A user-facing workflow is **one function**. When it needs input from the
> user, it *calls* a component the way you would call any other function.
> The user clicks; the function returns; execution continues.

In Smalltalk this is implemented with first-class continuations. In Clojure we
have three plausible substitutes:

1. **Virtual threads + promises** — the conversation is a real JVM thread
   that parks on a `Promise` between user interactions.
2. **CPS / coroutine macros** (`cloroutine`, custom CPS transform) — the
   conversation is a serialisable state machine.
3. **Reactive graphs** (Hyperfiddle Electric) — a different paradigm
   altogether.

`stube` chooses option 1 for v1. The user-facing API is designed so that
option 2 can be slotted in later as an alternative kernel without changing
flow code.

The transport is **Datastar** (long-lived SSE + signal-driven events). Datastar
maps onto the framework's natural lifecycle — one channel per conversation,
events resume the parked thread, fragments are pushed back as patches —
without any impedance mismatch.

---

## 2. The user's mental model

A `flow` is a Clojure function. It runs top to bottom. When it needs the user
to do something it calls `ask` with a component; `ask` blocks until the user
submits, then returns the value the component produced. `show` pushes a
fragment to the page without blocking. `answer` is how a component, from
inside its event handler, returns a value to the `ask` that produced it.

```clojure
(s/defflow calc []
  (let [a (s/ask (ui/number-input "First number"))
        b (s/ask (ui/number-input "Second number"))]
    (s/show [:div.result "Sum: " (+ a b)])))
```

That is the whole "Holy Grail" of web programming: one expression, two human
interactions, no callback inversion, real stack frames.

Composition works the same way. A component's event handler may itself `ask`
a sub-component:

```clojure
(s/defcomponent confirm-delete [item]
  :render (fn [_]
            [:div [:p "Delete " item "?"]
             [:button (s/on :click ::go) "Delete"]])
  :on
  {::go (fn [_ _]
          (let [reason (s/ask (ui/text-input "Why?"))]
            (s/answer {:confirmed? true :reason reason})))})
```

That is Seaside's `call:`/`answer:` protocol, in Clojure, with no objects.

---

## 3. The three core concepts

### 3.1 Flow

A function written with `defflow`. Started by an HTTP route; runs on a virtual
thread; lives until it returns, throws, or is reaped.

```clojure
(s/defflow checkout [cart] ...)
(s/mount! "/checkout" #'checkout)
```

A flow is *not* a component. It produces no markup of its own; everything the
user sees comes from `ask`/`show` calls inside it.

### 3.2 Component

A value (a map) describing a chunk of UI plus its event handlers and per-
instance state. Produced by `defcomponent`. Components are pure data so they
can be composed, memoised, and inspected.

```clojure
{:stube/component   :ui/number-input
 :stube/instance-id "ni-7e2"
 :stube/state       {:value ""}
 :stube/render      (fn [self] ...)
 :stube/handlers    {::submitted (fn [self event] ...)}}
```

### 3.3 Conversation

A live execution of a flow, bound to one browser session. Owns:

- the virtual thread running the flow,
- the SSE handle pushing patches to the browser,
- the **single pending `ask`** (component + promise) — if any,
- a registry of mounted components (so events route to the right handler),
- bookkeeping (last-touched timestamp, flow name, started-at).

A session may host many simultaneous conversations (e.g. one per browser tab).
Each conversation gets its own SSE channel.

---

## 4. Architecture

### 4.1 High-level

```diagram
╭─────────────────────────────────────────────────────────────────╮
│                          Browser                                │
│                                                                 │
│   ╭─────────────╮         ╭───────────────────────────╮         │
│   │   Datastar  │◀───SSE──│  conversation event feed  │         │
│   │   client    │         ╰───────────────────────────╯         │
│   │   (signals, │                                               │
│   │    patches) │─POST /flow/:cid/event ─────────╮              │
│   ╰─────────────╯                                │              │
╰──────────────────────────────────────────────────┼──────────────╯
                                                   ▼
╭─────────────────────────────────────────────────────────────────╮
│                          Server                                 │
│                                                                 │
│   ╭───────────╮    ╭──────────────────────────────────────╮     │
│   │  Reitit   │───▶│  Conversation registry  (atom map)   │     │
│   │  routes   │    │   cid → conversation                 │     │
│   ╰───────────╯    ╰──────────────────────────────────────╯     │
│         │                       │                               │
│         │ /start                │ lookup                        │
│         ▼                       ▼                               │
│   ╭───────────────────────────────────────────────────────╮     │
│   │            Conversation                               │     │
│   │  ╭───────────────╮   ╭─────────────────────────────╮  │     │
│   │  │ Virtual thread│   │   SSE handle (Datastar)     │  │     │
│   │  │  running flow │──▶│   patch-elements!, ...      │  │     │
│   │  ╰──────┬────────╯   ╰─────────────────────────────╯  │     │
│   │         │ ask → parks on Promise                       │     │
│   │         │ answer → delivers Promise                    │     │
│   │  ╭──────▼────────────────────────────────────────╮    │     │
│   │  │  Pending ask: {component-id, promise, comp}   │    │     │
│   │  ╰────────────────────────────────────────────────╯    │     │
│   ╰───────────────────────────────────────────────────────╯     │
╰─────────────────────────────────────────────────────────────────╯
```

### 4.2 The flow lifecycle (sequence)

```diagram
Browser           Router          Conversation        Vthread (flow)
   │                 │                  │                    │
   │ GET /calc       │                  │                    │
   ├────────────────▶│ start-flow!      │                    │
   │                 ├─────────────────▶│ spawn vthread      │
   │                 │                  ├───────────────────▶│
   │                 │                  │                    │ run body
   │ ◀──── HTML shell + open SSE ◀──────┤                    │ ask(c1)
   │                 │                  │◀───── park ────────┤
   │ ◀── SSE: patch  │                  │                    │
   │                 │                  │                    │
   │ POST /event     │                  │                    │
   │ {cid, c1.submit}│                  │                    │
   ├────────────────▶│ dispatch         │                    │
   │                 ├─────────────────▶│ run handler        │
   │                 │                  │ → answer(42)       │
   │                 │                  │ deliver promise ──▶│
   │                 │                  │                    │ resume
   │                 │                  │                    │ ask(c2)
   │ ◀── SSE: patch  │                  │◀───── park ────────┤
   │ ...             │                  │                    │
```

### 4.3 The kernel (pseudocode)

```clojure
(def ^:dynamic *conversation* nil)

(defn ask [component]
  (let [conv  *conversation*
        cid   (:id component)
        p     (promise)]
    (swap! conv assoc :pending {:component-id cid :promise p :component component})
    (push-fragment! (:sse @conv) (render component))
    @p))                                ; vthread parks here

(defn show [hiccup]
  (push-fragment! (:sse @*conversation*) hiccup))

(defn answer [value]
  (let [{:keys [pending]} @*conversation*]
    (swap! *conversation* dissoc :pending)
    (deliver (:promise pending) value)))

(defn start-flow! [flow-fn args session-id sse-handle]
  (let [conv (atom {:sse sse-handle :session session-id :touched (now)})]
    (.start (Thread/ofVirtual)
            #(binding [*conversation* conv]
               (try (apply flow-fn args)
                    (finally (close-conversation! conv)))))
    conv))
```

### 4.4 Why virtual threads, not core.async / cloroutine

| concern               | virtual threads                         | cloroutine / CPS               |
|-----------------------|------------------------------------------|--------------------------------|
| stack traces          | real, full                                | rewritten, partial             |
| debugger support      | works                                     | poor                            |
| forms allowed in flow | any Clojure                               | restricted (no `try` across yields, etc.) |
| serialisable state    | no                                        | yes                             |
| survives restart      | no                                        | yes                             |
| cost per conversation | ~1 KB stack initially                     | one closure                    |

For a v1 framework whose target user runs one or two app servers and accepts
"server restart drops in-flight conversations," vthreads win on every axis
that affects daily development. The CPS path is left as a future alternative
**kernel** — the `ask`/`show`/`answer` API does not need to change to support
it.

---

## 5. Components

### 5.1 `defcomponent`

```clojure
(s/defcomponent number-input
  [prompt]
  :state   {:value ""}
  :render  (fn [self]
             [:form (s/on :submit ::submit)
              [:label prompt]
              [:input (s/bind self :value)]
              [:button "OK"]])
  :on
  {::submit (fn [self _]
              (s/answer (parse-long (:value self))))})
```

Expands to a function that returns a fresh component map each time it is
called. The map is the contract — the macro is a convenience.

### 5.2 Per-instance identity

Every component instance produced by an `ask` gets a stable `instance-id`
(short ULID). This id is:

- the DOM `id` of its root element (so Datastar patches replace it cleanly),
- the routing key for events (`POST /event` payloads carry it),
- the lookup key in the conversation's component registry.

### 5.3 State

Component state is held in the conversation, keyed by instance-id. Handlers
receive the current `self` (state merged with metadata) and an `event` (the
Datastar signal payload + event name). They mutate via:

- `(s/update-state self f & args)` — replace state in the registry, re-render,
  push a patch.
- `(s/answer self v)` — fulfil the parking ask with `v`.
- (return value is ignored.)

This explicitness avoids the Seaside ambiguity where returning a value from a
handler and calling `answer:` both did similar things.

### 5.4 Composition

A handler may call `ask` on another component. Because handlers run on the
flow's vthread (we *route* the event onto it — see §6.4), nested asks work the
same as top-level ones.

### 5.5 The component registry

```clojure
{:components
 {"ni-7e2" {:def number-input :state {:value "42"} :handlers {...}}
  "wz-001" {:def wizard       :state {:step 2}     :handlers {...}}}}
```

Cleared per-component on `answer`, fully cleared on conversation end.

---

## 6. Datastar integration

### 6.1 Why Datastar specifically

A `stube` conversation needs:

1. A **persistent server-to-browser channel** to push fragments mid-flow
   without the user navigating. → Datastar SSE.
2. A way for the browser to **send events** without reloading the page. →
   Datastar `@post` / `@get` actions.
3. **Two-way bound input state** so handlers receive what the user typed. →
   Datastar signals.

All three exist in Datastar as first-class primitives. Building on top of it
is "use the protocol", not "graft something onto it."

### 6.2 Mapping

| stube concept        | Datastar mechanism                                     |
|----------------------|--------------------------------------------------------|
| Open a conversation  | `GET /flow/:name/start` returns shell HTML that opens an SSE to `/flow/:cid/sse` |
| `show` / re-render   | `patch-elements!` on the conversation's SSE             |
| `ask` blocks         | (kernel-side) — Datastar doesn't know; it just stops receiving patches |
| Component event      | `data-on:submit="@post('/flow/:cid/event?c=<iid>&e=<evt>')"` |
| Two-way input bind   | `data-bind:value="$<scoped-signal>"` reading & writing into a per-instance signal |
| Final result page    | A last `patch-elements!` then `close-stream!`           |

### 6.3 The shell

A single static HTML shell loads the Datastar JS bundle and opens the SSE.
All subsequent UI comes from patches; no full page reload across the
conversation's lifetime (except the user manually reloading, which **resumes**
— see §8.2).

### 6.4 Event dispatch

```clojure
(defn handle-event [{:keys [params session]}]
  (let [{:keys [cid c e]} params
        signals           (read-signals params)
        conv              (lookup-conv cid)
        component         (get-in @conv [:components c])
        handler           (get-in component [:handlers (keyword e)])]
    (run-on-flow-thread! conv
      #(handler (assoc component :state (latest-state conv c) :signals signals)
                {:event (keyword e) :signals signals}))
    (sse-empty-200)))
```

Two design notes:

- **Handlers run on the flow's vthread**, not on the HTTP worker. The HTTP
  worker just enqueues the call and returns immediately. This is what allows
  nested `ask` to work — the handler is *part of* the flow.
- **One handler at a time per conversation.** Events that arrive while a
  handler is running are queued. This is the right default — Seaside makes the
  same choice — and removes a class of races.

### 6.5 Signals

Signals are namespaced per component instance to avoid collisions:

```
$cmp.ni-7e2.value       ; bound to number-input #ni-7e2's :value
$cmp.wz-001.step        ; wizard internal
```

`(s/bind self :value)` expands to `{:data-bind:value "$cmp.<iid>.value"}` and
the event payload deserialises that signal back into the component's state.

---

## 7. HTTP surface

| route                          | method | purpose                                      |
|--------------------------------|--------|----------------------------------------------|
| `/flow/:name/start`            | GET    | Mint cid, render shell, open SSE            |
| `/flow/:cid/sse`               | GET    | (Re)attach SSE — internal                    |
| `/flow/:cid/event`             | POST   | Dispatch component event onto flow vthread  |
| `/flow/:cid/end`               | POST   | Cancel a conversation explicitly (optional) |

All `/event` routes require CSRF (anti-forgery). Conversation ids are random
and unguessable; ownership is double-checked against the session id.

---

## 8. Lifecycle & operations

### 8.1 Start

```diagram
  GET /flow/calc/start
        │
        ▼
  ╭──────────────────────────────────────╮
  │ 1. mint cid                          │
  │ 2. allocate conversation atom        │
  │ 3. render shell HTML with cid baked  │
  │    in (and SSE connect URL)          │
  │ 4. spawn vthread → calc-flow         │
  │    (it parks on first ask)           │
  ╰──────────────────────────────────────╯
        │
        ▼  HTML shell to browser
        │  browser opens SSE → /flow/:cid/sse
        ▼
  ╭──────────────────────────────────────╮
  │ 5. attach SSE handle to conversation │
  │ 6. flush queued fragments produced   │
  │    by the parked first ask           │
  ╰──────────────────────────────────────╯
```

### 8.2 Reconnect (refresh / network blip)

The conversation is still alive on the server. The browser opens a new SSE,
the server reattaches, and **re-pushes the latest frame** (the rendering of
the currently-pending ask, plus any latest `show` content). The flow does not
restart.

This is what makes refresh "do the right thing." It is *not* the back button —
that requires history (see §11).

### 8.3 Reaper

```
on SSE disconnect → start 30s grace timer
on reconnect within grace → cancel timer
on grace expiry → reap (interrupt vthread, drop conversation)

idle conversation (no event, no SSE) > 30 minutes → reap
flow returns or throws → reap
```

⚠ Tunables. The numbers above are sensible defaults, not law.

### 8.4 Errors

A flow that throws is reaped. The browser receives a "this conversation
ended" patch with a button to start a new one. Stack traces are logged
server-side with the cid.

### 8.5 Server restart

All conversations die. The shell on the browser detects SSE close + fail-to-
reconnect and shows "the server restarted; please refresh." This is a stated
limitation of v1. (See §11 for the persistence track.)

---

## 9. Public API

```clojure
;; Definition
(s/defflow      name [params*] body*)
(s/defcomponent name [params*] :state m :render f :on m)

;; Mounting
(s/mount! path #'flow-var)

;; Inside a flow or handler
(s/ask        component)        ; → user-supplied value
(s/show       hiccup)           ; → nil, pushes fragment
(s/answer     value)            ; → never returns from handler's POV
(s/update-state self f & args)  ; mutate component state, re-render

;; Hiccup helpers
(s/on   event-kw handler-kw & opts)   ; → data-on:event="@post(...)"
(s/bind self attr)                    ; → data-bind:attr="$cmp.<iid>.<attr>"
(s/scoped-signal self k)              ; → "$cmp.<iid>.<k>"

;; Introspection / ops
(s/active-conversations)
(s/end! cid)
```

---

## 10. Worked example: branching wizard

```clojure
(s/defflow signup []
  (let [email (s/ask (ui/text-input "Email"))
        plan  (s/ask (ui/select "Plan" [:free :pro :enterprise]))]
    (case plan
      :free       (s/show (ui/welcome email))
      :pro        (let [card (s/ask (ui/card-form))]
                    (charge! card 19)
                    (s/show (ui/welcome email)))
      :enterprise (let [contact (s/ask (ui/text-input "Phone"))]
                    (notify-sales! email contact)
                    (s/show (ui/will-be-in-touch))))))
```

What's notable:

- The branch is *plain Clojure*. No state machine, no router config.
- `card-form` may itself `ask` — e.g. for an OTP — without any change here.
- Adding a step is one `let` line.

Compare to the same logic written with explicit handlers and stored state
machines: 5–10× the code, every transition is a named function.

---

## 11. Out of scope for v1 (explicit non-goals)

- **Back-button time travel.** Vthreads can't rewind. If a flow has 5 asks
  and the user hits Back, they currently re-attach to ask #5. Real fix is the
  CPS kernel + per-step checkpointing.
- **Persistence across server restart.** Same fix.
- **Multi-server failover.** Conversation lives on one node. A sticky session
  / consistent-hash router is the workaround; true mobility needs serialisable
  state.
- **Parallel asks** (one flow showing two waiting components at once). The
  pending-ask map is a single slot. Useful, not v1.
- **Authoring components in CLJS.** Components are server-side; signals + SSE
  fragments are the only client-side machinery. No isomorphic story.

---

## 12. Comparison

| feature               | Seaside              | stube                              | HTMX/handlers           | Electric              |
|-----------------------|----------------------|------------------------------------|--------------------------|-----------------------|
| linear flow code      | yes (continuations)  | yes (vthreads)                     | no                       | yes (reactive)        |
| component composition | objects              | values                             | partials                 | functions             |
| persistent state      | image                | session-only                       | URL/db                   | reactive db           |
| back button           | works (continuations)| no (v1)                            | works (stateless)        | n/a                   |
| restart resilience    | image                | none (v1)                          | full                     | depends               |
| transport             | full pages           | Datastar SSE patches               | hx-* attrs               | proprietary           |

---

## 13. Implementation plan

### Slice 1 — vertical kernel (1 week)

- `defflow` macro (capture body into a fn, register).
- Conversation registry (atom of cid→conversation atom).
- Vthread spawn + `*conversation*` dynamic var.
- `ask` / `show` / `answer` against in-memory state.
- Datastar adapter: SSE handle, `patch-elements!` wrapper.
- Two routes: `/flow/:name/start`, `/flow/:cid/event`.
- One demo: the calculator. End-to-end, in browser.

Defer: `defcomponent` macro (write components by hand as maps), reaper,
sub-component nesting, back button.

### Slice 2 — components (1 week)

- `defcomponent` macro.
- Component registry per conversation.
- Signal binding (`s/bind`, scoped names, signal read on event).
- `s/on`, `s/update-state`.
- Demo 2: signup wizard with branching.

### Slice 3 — robustness (1 week)

- Reaper (SSE-disconnect grace, idle timeout).
- Reconnect / re-attach (latest-frame replay).
- Error → friendly end-of-conversation patch.
- CSRF wired through.
- Ops: `(active-conversations)`, `(end! cid)`, basic logging.

### Slice 4 — DX (open-ended)

- Better error pages (interactive REPL into a paused flow?).
- Test harness (`run-flow-headless` driving asks programmatically).
- Devtools panel (list conversations, peek state, force-end).

---

## 14. Open questions

1. **Naming.** `stube` is a placeholder. Alternatives: `parlor`, `salon`,
   `dialog`, `seance`.
2. **Macro vs fn for flows.** Is the macro buying anything beyond `(def
   calc (s/flow [] ...))`? Probably just symmetry with `defcomponent`.
3. **Should `show` return anything?** Currently `nil`. Could return the
   rendered hiccup for testing convenience.
4. **Reaper defaults.** 30s grace, 30min idle — needs validation against a
   real app.
5. **Multi-tab.** If the same session opens `/calc` twice in two tabs, do
   they share a conversation or each get their own? Default: each tab gets
   its own (cid is per-start, not per-session).
6. **Error in a handler.** Does it kill the conversation or just the
   handler? Default: kill (matches Seaside). Could soften to "handler error,
   stay alive, surface a banner."
7. **Anti-forgery on the SSE GET.** Standard Ring anti-forgery skips GETs;
   that's fine, but we should think about whether the cid alone is enough
   capability.

---

## 15. Glossary

- **Flow** — top-level Clojure fn run on a vthread, the body of a
  conversation.
- **Component** — value describing renderable UI + handlers + per-instance
  state.
- **Conversation** — live instance of a flow; one cid, one vthread, one SSE.
- **Ask** — block the flow until a component answers.
- **Answer** — fulfil the parking ask from inside a handler.
- **Show** — push a fragment without blocking.
- **Reattach** — bind a new SSE handle to an existing conversation after
  disconnect / refresh.
- **Reap** — terminate a conversation (interrupt vthread, drop registry
  entry).

---

*End of design doc.*

# A Clojure + Datastar component framework — fresh design

This is an independent take, written after reading primary material on
Seaside (Pharo) and UnCommon Web (Common Lisp), and the Datastar Clojure
SDK in its current shape (`dev.data-star.clojure/sdk`,
`…/http-kit`, `…/ring`).

It throws away the assumptions of the previous DESIGN.md and starts from the
sources.

---

## 1. What the originals actually do

### 1.1 Seaside

Stripped to essentials, the model is four things:

1. `WAComponent` — a class with mutable instance state, a
   `renderContentOn: html` method, an optional `children` method (returning
   sub-components currently rendered inside it), an optional `states`
   method (returning objects to snapshot for back-button support), and an
   optional `updateRoot:` / `updateUrl:` for `<head>` and URL fixup.

2. `call: / answer:` — `self call: aChildComponent` swaps the receiver
   out, in place, for `aChildComponent`. The receiver's process is
   suspended (Smalltalk continuation). When the child eventually evaluates
   `self answer: aValue`, the parent unblocks with `aValue` and resumes
   exactly where it was. The "place" being swapped can be the page root,
   in which case the user sees a whole new page, or any embedded slot.

3. `WATask` (subclass of `WAComponent` with no `renderContentOn:`) — a
   component that exists only to *sequence* other components via `call:`.
   The whole workflow lives inside one `go` method:
   ```smalltalk
   go
       | n guess |
       n := 100 atRandom.
       [ guess := self request: 'Your guess?'.
         guess = n ] whileFalse: [
           self inform: (guess > n ifTrue: ['too high'] ifFalse: ['too low']) ].
       self inform: 'You won.'
   ```
   Plain control flow — `whileFalse:`, `ifTrue:`, locals. The framework
   makes the HTTP round-trips invisible. This is the famous "linear page
   flow logic."

4. `WASnapshot` — Seaside takes a deep snapshot of every object listed
   under `#states` after each render. The back button restores the
   appropriate snapshot, so going back actually rewinds the model.

### 1.2 UnCommon Web

UCW is the closest predecessor in spirit. The relevant primitives:

- `defcomponent` — a CLOS class, by convention with the
  `standard-component-class` metaclass; slots that hold sub-components are
  marked `:component t`.
- `defmethod render` — render method, dispatched on the component class.
- `defaction` — a method that runs when the user clicks/submits. Inside
  the body you may call `(call 'some-component …)` and the surrounding
  computation is suspended; whatever the called component eventually
  passes to `(answer …)` becomes the value of the `(call …)` form.
- `defentry-point` — binds a URL to the initial `(call 'some-window …)`.
- `window-component` / `standard-window-component` — top-level wrapper
  that renders `<html><head></head><body>…</body></html>`. Subcomponents
  are stored in slots and rendered through them.

Mechanically, UCW used a CPS transform of action bodies and stored the
continuations in the session — so a continuation was a value, not a
thread.

### 1.3 The transport we get for free

Datastar's Clojure SDK (current as of 1.0.0-RC10) gives us:

- `(d*/patch-elements! sse html)` — push a fragment; default behaviour
  morphs the live DOM by matching top-level element id.
- `(d*/patch-signals! sse json)` — push a signal patch.
- `(d*/read-signals request)` — pull the current client signals out of an
  event request (POST body or `datastar` query param on GET).
- `(->sse-response request {on-open … on-close …})` — turn a Ring request
  into a long-lived SSE response.
- `data-on:click="@post('/path')"` (and `@get`, `@put`, `@patch`,
  `@delete`) on the client side — fires a request and ships the current
  signals along automatically.
- `data-bind:foo`, `data-signals:foo`, `data-computed:foo` — signal
  plumbing on the client.

Datastar morphs by id and preserves DOM state across patches. That is
*exactly* the operation Seaside's `call:` needs in the embedded case
("swap *this* slot, leave the rest alone").

---

## 2. Thesis

> **The conversation is a value.**

Seaside and UCW both went to extraordinary lengths to fake a captured
program counter (continuations, image snapshots, CPS transforms). In a
language with persistent data structures, we can simply *be* the data:

- A component instance is a map.
- A conversation is a stack of frames.
- A frame is a component instance plus a continuation key (where the
  parent should resume when this frame answers).
- "History" is the seq of past conversations; the back button is `nth`.
- Event handlers are pure functions `(state, event) → [state', effects]`.
  Effects are data (`[:call …]`, `[:answer …]`, `[:render …]`, `[:io …]`).

Everything reactive happens through the kernel evaluating effects against
this value. Datastar is the wire: it carries events in, patches out.

The result is a framework that reads like Seaside (linear, callable
components), is implemented like re-frame (effects as data, pure
handlers), and runs over Datastar's morph-by-id model with no
continuations, no parked threads, no Smalltalk image.

---

## 3. The model

```clojure
;; A component definition (registered globally by id)
{:component/id      :auth/login           ; namespaced keyword
 :component/init    (fn [args] {:state})  ; build initial state from call args
 :component/render  (fn [self] hiccup)    ; pure render
 :component/handle  (fn [self event] [self' effects])  ; pure transition
 :component/keep    #{:username}}          ; signal keys to read from client

;; A live component instance inside a conversation
{:instance/id       "ix-7e2"               ; unique within a conversation
 :instance/type     :auth/login
 :instance/state    {:attempts 1}
 :instance/parent   "ix-7c1"               ; or nil if root frame
 :instance/resume   :on-login              ; continuation key in parent
 :instance/children {:slot/header "ix-001"}} ; named embedded subs

;; A frame on the stack — same shape as instance, just a vocabulary choice
;; (the call stack is just a vector of instance ids)

;; A conversation
{:conv/id        "cv-019"
 :conv/instances {"ix-7e2" {…}, "ix-7c1" {…}, "ix-001" {…}}
 :conv/stack     ["ix-7c1" "ix-7e2"]      ; bottom→top: root … current
 :conv/history   [previous-conv …]         ; for back-button
 :conv/created   #inst "…"
 :conv/touched   #inst "…"}
```

Two non-obvious choices:

- **Conversations are immutable values** held in an atom. Every event
  produces a new conversation. The previous values stay in
  `:conv/history`, which Clojure's persistent maps make essentially free
  in space (structural sharing).
- **Components are data; behaviour is registered separately**. A
  component instance is `{:type :auth/login, :state {…}}`. The function
  table lives in a registry keyed by `:type`. Instances are therefore
  serialisable — straight EDN — which makes persistence and multi-server
  resume trivial.

---

## 4. Effects vocabulary

A handler returns `[self' effects]`. Effects are data, executed by the
kernel after the handler runs:

| effect                              | meaning                                                                                       |
|-------------------------------------|-----------------------------------------------------------------------------------------------|
| `[:call <component> :resume <key>]` | Push a frame: instantiate component, current frame becomes parent, will receive answer at key |
| `[:answer <value>]`                 | Pop this frame; deliver `value` to the parent's handler at the parent's `:resume` key         |
| `[:replace <component>]`            | Pop this frame and push a new one in its place (Seaside `become:`-style)                      |
| `[:patch <hiccup>]`                 | Side patch — render an extra fragment without changing the stack                              |
| `[:patch-signals <map>]`            | Push a Datastar signal patch                                                                  |
| `[:execute-script <js>]`            | Push raw JS to run in the browser                                                             |
| `[:io <fn>]`                        | Run an arbitrary side-effecting fn (db write, email send) off-thread                          |
| `[:end <value>]`                    | Terminate the conversation                                                                    |

The `[:answer v]` effect is the single mechanism by which a child
"returns" to a parent. The kernel:

1. pops the current top frame,
2. looks at its `:instance/resume` key (e.g. `:on-login`),
3. finds the now-top frame's component definition, gets the function
   under that resume key,
4. calls `(resume-fn parent-self v)` to obtain `[parent-self' effects']`,
5. continues evaluating effects.

This is a direct, data-driven implementation of Seaside's `call:/answer:`
with no thread blocking and no CPS transform.

---

## 5. The kernel loop

The whole engine fits in a few dozen lines.

```clojure
(defn step
  "Pure: apply one effect to a conversation, return [conv' fragments]."
  [registry conv [op & args :as effect]]
  (case op
    :call    (let [[child-def opts] args
                   parent-id    (peek (:conv/stack conv))
                   instance     (instantiate registry child-def
                                  {:instance/parent parent-id
                                   :instance/resume (:resume opts)})
                   conv'        (-> conv
                                    (assoc-in [:conv/instances (:instance/id instance)] instance)
                                    (update :conv/stack conj (:instance/id instance)))]
               [conv' [(render-frame conv' instance)]])

    :answer  (let [[v] args
                   stack       (:conv/stack conv)
                   leaving-id  (peek stack)
                   stack'      (pop stack)
                   parent-id   (peek stack')
                   parent      (get-in conv [:conv/instances parent-id])
                   resume-fn   (get-resume registry parent)
                   [parent' fx] (resume-fn parent v)
                   conv'       (-> conv
                                   (assoc :conv/stack stack')
                                   (update :conv/instances dissoc leaving-id)
                                   (assoc-in [:conv/instances parent-id] parent'))
                   [conv'' more-frags] (run-effects registry conv' fx)]
               [conv'' (cons (render-frame conv'' parent') more-frags)])

    :replace        …
    :patch          [conv [(first args)]]
    :patch-signals  [conv [{::signals (first args)}]]
    :execute-script [conv [{::script  (first args)}]]
    :io             (do (future ((first args))) [conv []])
    :end            [(assoc conv :conv/ended? true) [::close]]))

(defn run-effects [registry conv effects]
  (reduce (fn [[c frags] eff]
            (let [[c' fs] (step registry c eff)]
              [c' (into frags fs)]))
          [conv []]
          effects))

(defn dispatch
  "Receive an event from the client, return [conv' fragments]."
  [registry conv {:keys [instance-id event signals]}]
  (let [inst       (get-in conv [:conv/instances instance-id])
        cdef       (get registry (:instance/type inst))
        merged     (merge-signals inst signals (:component/keep cdef))
        [inst' fx] ((:component/handle cdef) merged event)
        conv'      (-> conv
                       (assoc-in [:conv/instances instance-id] inst')
                       (update :conv/history conj conv))]
    (run-effects registry conv' fx)))
```

That is the whole runtime, modulo error handling and a couple of helpers.
There is no parking, no continuation capture, no CPS — `dispatch` is a
plain function from `(conv, event) → (conv', fragments)` and would happily
run inside a unit test with no SSE in sight.

---

## 6. Defining components

A small `defcomponent` macro for ergonomics. It does *no* magic — it just
emits a `def` of a registry map.

```clojure
(s/defcomponent :auth/login
  :init   (fn [{:keys [redirect-to]}]
            {:redirect-to redirect-to :error nil})

  :keep   #{:username :password}     ; signal names to lift into state on each event

  :render (fn [self]
            [:form {:id (:instance/id self)
                    :data-on:submit "@post('/conv/event')"}
             [:input {:data-bind:username true :name "username"}]
             [:input {:data-bind:password true :name "password" :type "password"}]
             (when-let [e (:error self)] [:p.error e])
             [:button "Sign in"]])

  :handle (fn [self {:keys [event] :as e}]
            (case event
              :submit (if-let [user (auth/check (:username self) (:password self))]
                        [self [[:answer user]]]
                        [(assoc self :error "Bad credentials") []])
              [self []])))
```

Three things to notice:

- The component's `:render` returns Hiccup whose root element id is the
  instance id. That is what makes Datastar's morph-by-id "just work":
  every patch the kernel emits is exactly the right thing to morph.
- `data-on:submit="@post('/conv/event')"` is the *only* event-routing
  pattern. The single endpoint figures out which instance and which event
  by reading the request — see §9.
- `:keep` lists the signals we want lifted into the component's state
  before the handler sees them. This is how two-way binding closes the
  loop: the user types → Datastar updates the signal client-side →
  on submit the handler sees `(:username self)` already populated. There
  is no per-keystroke server round-trip.

---

## 7. Composition

Two compositional verbs. Both Seaside-honest.

### 7.1 Embed (Seaside's `children`)

A parent renders a sub-component inline by mounting it as a child:

```clojure
(s/defcomponent :checkout/page
  :init   (fn [_]
            {:children {:slot/header (s/embed :ui/site-header)
                        :slot/cart   (s/embed :cart/summary {:editable? true})}})

  :render (fn [self]
            [:div {:id (:instance/id self)}
             (s/render-slot self :slot/header)
             [:h1 "Checkout"]
             (s/render-slot self :slot/cart)
             [:button {:data-on:click "@post('/conv/event')"} "Pay"]]))
```

`s/embed` returns a placeholder; the kernel instantiates it on first
mount and stores it under `:instance/children`. `s/render-slot` looks the
child up and renders it inline. Embedded children survive across the
parent's re-renders because morphing by id preserves them.

### 7.2 Call (Seaside's `call:`)

Composition by handler effect. The current frame is replaced (visually)
by the called component until it answers:

```clojure
:handle (fn [self {:keys [event]}]
          (case event
            :delete-item
            [self [[:call (s/embed :ui/confirm
                            {:question "Really delete?"})
                    :resume :on-confirm]]]))

;; …and the resume:
:on-confirm (fn [self confirmed?]
              (if confirmed?
                [(update self :items pop) [[:patch (toast "Deleted")]]]
                [self []]))
```

`:on-confirm` is just another key in the component definition's handler
map. The kernel looks it up by name when answering. Naming continuations
this way (instead of capturing them anonymously) is the intentional
Clojure-shaped trade: you give up the "anonymous lambda" feel of
Smalltalk continuations, you gain serialisable state and inspectable
flows.

---

## 8. Tasks (Seaside's `WATask`)

A task is a component whose only job is to drive a sequence of `call`s.
You can write one by hand:

```clojure
(s/defcomponent :booking/wizard
  :init   (fn [_] {:step 0})
  :handle (fn [self _]
            [self [[:call (s/embed :booking/dates) :resume :got-dates]]])
  :on-got-dates    (fn [self dates]   [(assoc self :dates dates)
                                       [[:call (s/embed :booking/room  {:dates dates}) :resume :got-room]]])
  :on-got-room     (fn [self room]    [(assoc self :room room)
                                       [[:call (s/embed :booking/pay   {:price (price-for room)}) :resume :got-pay]]])
  :on-got-pay      (fn [self receipt] [self [[:answer {:dates (:dates self) :room (:room self) :receipt receipt}]]]))

(s/mount! "/book" :booking/wizard)
```

That's idiomatic Clojure — explicit, debuggable, totally serialisable —
but it's verbose compared to Seaside's `go` method. So we provide a
macro, `defflow`, that compiles linear-looking code into the same
state machine. It uses [`cloroutine`](https://github.com/leonoel/cloroutine)
under the hood (a real, well-maintained CPS transform):

```clojure
(s/defflow :booking/wizard []
  (let [dates   (s/await (s/embed :booking/dates))
        room    (s/await (s/embed :booking/room  {:dates dates}))
        receipt (s/await (s/embed :booking/pay   {:price (price-for room)}))]
    {:dates dates :room room :receipt receipt}))
```

`s/await` is the only special form inside a flow body. Every `await`
becomes a `:call`+resume pair; the `let` bindings become resumed values;
the final expression becomes an `[:answer …]`. The macro emits a normal
component definition with auto-generated `:on-step-N` resume keys, so
flows compose with regular components and with each other.

This is the closest thing to Seaside's `go` you can write in Clojure
without losing the data model.

---

## 9. Wiring to Datastar

### 9.1 The HTTP surface

Three endpoints, one per conversation, identified by the cid in the URL:

| route                       | method | purpose                                                     |
|-----------------------------|--------|-------------------------------------------------------------|
| `/conv/start/:flow`         | POST   | mint conversation, render shell, return cid                 |
| `/conv/:cid/sse`            | GET    | open the long-lived SSE stream for this conversation        |
| `/conv/:cid/event`          | POST   | dispatch an event; body carries signals + instance + event  |

The shell is a tiny HTML doc:

```html
<!doctype html>
<html>
  <head><script type="module" src="/datastar.min.js"></script></head>
  <body data-on:load="@get('/conv/CID/sse')">
    <div id="root"></div>
  </body>
</html>
```

Once the SSE is open, every push from the kernel is a `patch-elements!`
keyed by instance id — the morpher takes care of the rest. There is no
client-side framework to ship: Datastar is the entire client.

### 9.2 The event handler

```clojure
(require '[starfederation.datastar.clojure.api :as d*]
         '[starfederation.datastar.clojure.adapter.http-kit :as hk])

(defn event-handler [{:keys [path-params] :as req}]
  (let [{:keys [cid]} path-params
        signals       (d*/read-signals req)
        ev            (-> signals :_meta)        ; {:instance "ix-7e2" :event :submit}
        [conv' frags] (swap-conv! cid #(dispatch registry % {:instance-id (:instance ev)
                                                              :event       (:event ev)
                                                              :signals     (dissoc signals :_meta)}))]
    (push-frags! cid frags)
    {:status 204}))
```

`push-frags!` looks up the live SSE-gen for the cid and calls
`d*/patch-elements!` once per fragment. Conversations and SSE-gens live
side by side in two small atoms keyed by cid.

### 9.3 The Hiccup → Datastar bridge

Two helpers cover the common cases so flow code never types literal
attribute strings:

```clojure
(s/on  :submit)
;; => {:data-on:submit "@post('/conv/event')"}

(s/bind :username)
;; => {:data-bind:username true}
```

The `:_meta` signal that carries `{:instance, :event}` is set
automatically when an `(s/on …)` attribute is rendered, by attaching a
`data-on:<event>` expression that writes those values into the signal
before the post:

```html
data-on:submit="$_meta = {instance:'ix-7e2', event:'submit'}; @post('/conv/event')"
```

(Yes, that's a one-line Datastar expression — they support semicolon
sequencing.)

That's the entire transport story. No WebSockets, no JSON-RPC, no custom
client.

---

## 10. The back button is free

Because every event call appends the previous `conv` to `:conv/history`,
"going back" is one line:

```clojure
(defn back! [cid]
  (swap-conv! cid (fn [c]
                    (if-let [prev (peek (:conv/history c))]
                      (assoc prev :conv/history (pop (:conv/history c)))
                      c))))
```

Triggered however you like — a button, the browser back button via a
tiny client-side handler that pushes a `popstate` event to the server,
or both.

This is the feature Seaside paid for with `WASnapshot`'s deep-copy ritual
and that UCW could only fake by keeping every continuation alive forever.
Persistent data structures hand it to us with no engineering at all.

---

## 11. Worked example, end to end

A two-step "guess the number" task — Seaside's classic — written in this
framework, in full:

```clojure
(ns demo.guess
  (:require [stube.core :as s]))

(s/defcomponent :ui/prompt
  :init   (fn [{:keys [text]}] {:text text :answer ""})
  :keep   #{:answer}
  :render (fn [self]
            [:form {:id (:instance/id self) :data-on:submit (s/on :submit)}
             [:label (:text self)]
             [:input (s/bind :answer)]
             [:button "OK"]])
  :handle (fn [self _]
            [self [[:answer (parse-long (:answer self))]]]))

(s/defcomponent :ui/info
  :init   (fn [{:keys [text]}] {:text text})
  :render (fn [self]
            [:div {:id (:instance/id self) :data-on:click (s/on :ack)}
             [:p (:text self)]
             [:button "Continue"]])
  :handle (fn [self _] [self [[:answer :ack]]]))

(s/defflow :demo/guess []
  (let [target (rand-int 100)]
    (loop []
      (let [g (s/await (s/embed :ui/prompt {:text "Guess 1–100"}))]
        (cond
          (< g target) (do (s/await (s/embed :ui/info {:text "Too low"}))  (recur))
          (> g target) (do (s/await (s/embed :ui/info {:text "Too high"})) (recur))
          :else        (s/await (s/embed :ui/info {:text "You won."})))))))

(s/mount! "/guess" :demo/guess)
```

That is comparable to the Smalltalk version line-for-line, runs over
Datastar with no client code, supports the back button, survives a
restart if the conversation atom is backed by a kv store, and is a pure
data pipeline you can step through in a REPL.

---

## 12. Compared to the originals

| concern                          | Seaside              | UCW                  | this design                      |
|----------------------------------|----------------------|----------------------|----------------------------------|
| how a "call" suspends            | native continuation  | CPS transform        | data: push a stack frame         |
| how an "answer" resumes          | native continuation  | call captured cont.  | data: pop frame, run resume key  |
| linear flow code                 | direct (`go` method) | direct (`defaction`) | via `defflow` (cloroutine CPS)   |
| state of a stopped conversation  | image / VM heap      | session table        | EDN value                        |
| back button                      | `WASnapshot` deep copy| continuation forest | persistent history vector        |
| restart resilience               | image save           | none                 | persist the value                |
| multi-server                     | sticky session       | sticky session       | shared kv store, no affinity     |
| transport                        | full pages           | full pages           | Datastar SSE morph-by-id         |
| extension to the model           | subclass `WAComponent`| subclass component  | add a key to the registry map    |

The novel thing is not any single ingredient — it's that none of them
are inventions. Persistent data structures, effect-as-data, SSE, morph,
CPS via `cloroutine`. The framework is the *integration*: a small data
model plus the mapping into Datastar's wire protocol.

---

## 13. Dependencies

The framework is small because it stands on a few well-chosen libraries.

| concern          | library                                                                | why                                                                                                  |
|------------------|------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| HTML rendering   | [Chassis](https://github.com/onionpancakes/chassis)                    | Hiccup-compatible, faster than `hiccup`/`hiccup2`, supports `Appendable` streaming for SSE writes    |
| JSON for signals | [Charred](https://github.com/cnuernber/charred)                        | Fastest pure-Clojure JSON, no Jackson dep, clean keyword-keys mode — exactly what signal patches need |
| Linear flow CPS  | [cloroutine](https://github.com/leonoel/cloroutine)                    | Clojure macro CPS transform; powers `defflow` so `let` + `s/await` reads as straight-line code        |
| SSE transport    | [datastar-clojure](https://github.com/starfederation/datastar-clojure) | Official Datastar Clojure SDK + http-kit / ring adapters                                              |
| HTTP server      | http-kit (default) — Ring-compliant servers also supported              | Long-lived SSE without thread-per-connection                                                          |
| Routing          | [Reitit](https://github.com/metosin/reitit) (recommended, not required) | Three routes is small, but Reitit's data-driven style matches the framework's ethos                  |

Notes on how they slot in:

- **Chassis at the render boundary.** `:render` returns Hiccup; the
  kernel hands it to `chassis/html` immediately before calling
  `d*/patch-elements!`. We never hold serialised HTML in the conv value.
- **Charred at the signals boundary.** Signals enter as JSON (request
  body or `datastar` query param) and leave as JSON (`patch-signals!`).
  Conv state itself stays EDN — keywords, sets, instants matter for
  modelling, and JSON would lose them.
- **Cloroutine is opt-in.** Slice 0 ships without it; people who want to
  hand-roll tasks as plain components can. Slice 1 adds `defflow` for
  those who want Seaside's `go`-method ergonomics.

---

## 14. Implementation plan

### Slice 0 — primitives, no flow macro (≈ 2 days)

- Component registry, `defcomponent`.
- Conversation atom, `dispatch`, `step`, `run-effects`.
- HTTP wiring (start, sse, event) on http-kit.
- `s/on` and `s/bind` Hiccup helpers, `_meta` signal convention.
- Hand-rolled tasks (no macro yet).
- One demo (guess-the-number, written by hand without `defflow`).

### Slice 1 — `defflow` macro (≈ 2 days)

- Add `cloroutine` dependency.
- Macro that turns `let` + `s/await` into a state machine of resume keys.
- Rewrite the demo using `defflow`, confirm parity.

### Slice 2 — embedding & decorations (≈ 2 days)

- `s/embed` placeholders, `s/render-slot`, `:instance/children`.
- Decoration wrappers (e.g. an `:ui/with-banner` that wraps any
  child) — Seaside's behavioural decorations as plain HOFs over component
  defs.
- Demo 2: a wizard with a persistent header/footer.

### Slice 3 — history & persistence (≈ 2 days)

- `:conv/history` vector + `back!`.
- Pluggable conversation store: in-memory atom (default), file, redis.
- Crash-resume: on startup, restore live convs; on first SSE reattach,
  re-render the current top frame.

### Slice 4 — operations (≈ 1 day)

- Reaper for idle conversations.
- `(active-conversations)`, `(end! cid)` ops.
- Anti-forgery on `/conv/:cid/event` (the cid alone is not a capability;
  enforce session ownership).
- Logging with cid context.

After slice 0–1 you have something publishable. The rest is layered
features.

---

## 15. Things deliberately not in v1

- **Time-travel UI**. The history exists; UI for browsing it doesn't.
- **Optimistic updates**. Datastar can do them client-side via signals;
  the framework doesn't model them server-side.
- **Streaming flows** (a flow that pushes intermediate output). Achievable
  with `[:patch …]` effects from inside a long-running `:io` callback,
  but not part of the core vocabulary yet.
- **Component-level per-instance CSS / JS scope**. A component renders
  Hiccup; styles are global. Tailwind or CSS modules at the build layer
  is the intended path.

---

## 16. Open questions

1. **Cloroutine + `try` blocks inside flows.** Cloroutine restricts the
   forms allowed across yield points. Exact ergonomic limits need a spike.
2. **Signal naming under embedding.** Two embedded `:ui/prompt`s on the
   same page would both bind `$answer`. Either mangle into
   `$cmp.<iid>.answer` automatically or require the developer to choose
   distinct names. The first is friendlier; the second is more
   transparent.
3. **Resume keys vs anonymous closures.** The current design names every
   resume (`:on-confirm`, `:on-step-3`). That is what makes state
   serialisable. Worth confirming there's no escape hatch we want where
   you can pass an anonymous `(fn [v] …)` and lose persistence as a
   trade.
4. **Where the conversation lives during a single event.** The atom +
   `swap-conv!` model implies one conversation = one writer at a time.
   That is correct (events to the same conv must serialise) but rules out
   parallel asks. Acceptable? Probably yes; defer.
5. **Datastar Pro features** (e.g. `data-custom-validity`). Not needed for
   the kernel; worth a thin wrapper namespace once the core ships.

---

*End — DESIGN-V2.*

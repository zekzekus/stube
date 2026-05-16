# stube

> Seaside‑style callable components for Clojure, on top of
> [Datastar](https://data-star.dev/). Components are plain data. The
> conversation is a value. The server does the rendering; the browser
> just morphs the patches.

```clojure
(require '[dev.zeko.stube.core :as s])

(s/defcomponent :demo/counter
  :init   (fn [_] {:n 0})
  :render (fn [self]
            [:div (s/root-attrs self)
             [:button (s/on self :click :as :dec) "−"]
             [:span " " (:n self) " "]
             [:button (s/on self :click :as :inc) "+"]])
  :handle (fn [self {:keys [event]}]
            (case event
              :inc (update self :n inc)
              :dec (update self :n dec))))

(s/mount! "/" :demo/counter)
(s/start! {:port 8080})
```

Open <http://localhost:8080/>. That's a counter — one component, no
JavaScript, no client/server contract to manage by hand.

The real payoff shows up when one component *calls* another:

```clojure
(s/defcomponent :demo/save-or-cancel
  :render (fn [self]
            [:div (s/root-attrs self)
             [:button (s/on self :click :as :save)   "Save"]
             [:button (s/on self :click :as :cancel) "Cancel"]])
  :handle (fn [self {:keys [event]}]
            (case event
              :save   [(s/call (s/confirm "Save changes?") :on-confirmed)]
              :cancel [(s/answer :cancelled)]))
  :on-confirmed
  (fn [self yes?]
    [(s/answer (if yes? :saved :cancelled))]))
```

`(s/confirm "Save changes?")` is a component. Calling it stacks it on
top of the current frame. When the user clicks Yes or No it answers
back — and your `:on-confirmed` resume fires with the value. The
component doing the asking never thinks about callbacks, promises, or
modal state machines: it just calls a question and reads the answer.
That is the Seaside model, rebuilt for 2026.

---

## Documentation

|   |   |
|---|---|
| [**Tutorial**](docs/tutorial.md) | Build a real app, step by step. Start here. |
| [**API reference**](docs/api.md) | Every public function in `dev.zeko.stube.core`. |
| [**Internals**](docs/internals.md) | How the kernel, conversation and effects fit together. |
| [**Rationale**](docs/rationale.md) | Why stube exists. Seaside, the uncommon web, and where the model came from. |

The design notes that drove the implementation live in
[`docs/v2.md`](docs/v2.md) and [`docs/v2_1.md`](docs/v2_1.md).

---

## Why you might care

- **Components are values.** A component is a map of pure functions.
  The kernel is one multimethod and a fold. There is no class hierarchy,
  no lifecycle interface, no special object identity.

- **Effects are values.** Handlers return `[self' effects]`. Every
  interaction is inspectable at the REPL with no server running —
  `(s/replay :my/root [{:event :submit}])` walks the same code path the
  browser does.

- **Call and answer.** Components can call other components like
  functions and read their answer like return values. Wizards,
  confirmations, in‑place editors, login flows: all the same primitive.

- **Linear flows.** `(s/defflow ...)` lets you write what would have
  been a state machine as straight‑line Clojure with
  `(s/await child-embed)` for suspend points. The macro compiles down
  to a regular component using
  [cloroutine](https://github.com/leonoel/cloroutine).

- **Datastar over the wire.** SSE patches, morph by id. The only
  client‑side code is the Datastar runtime itself. Two‑way input
  bindings go through `s/bind`; events through `s/on`.

- **Persistent history.** Every dispatch snapshots the previous
  conversation to `:conv/history`. The conversation‑level Back button
  is one line.

- **Small async surface.** Scheduled events (`s/after`),
  publish/subscribe (`s/publish!` / `s/subscribe`), and zero‑JS
  multipart uploads route back into normal component handlers.

---

## Installation

stube is currently consumed as a git dep. Add to `deps.edn`:

```clojure
{:deps
 {dev.zeko/stube
  {:git/url "https://github.com/zekus/stube"   ;; placeholder
   :git/sha "<sha>"}}}
```

Then in code:

```clojure
(require '[dev.zeko.stube.core :as s])
```

A Maven coordinate will arrive once the API freezes at 1.0.

---

## Running the bundled examples

This project assumes the development environment provided by
`flake.nix`.

```bash
nix develop          # enter dev shell
clojure -M:examples  # open http://localhost:8080/ in your browser
clojure -X:test      # run the test suite
```

The example browser at `/` indexes every shipped demo. Individual
URLs:

| path | what it shows |
|------|---------------|
| `/guess`         | `defflow` — guess‑the‑number as straight‑line code |
| `/multicounter`  | three embedded counters; one click re‑renders one widget |
| `/todo`          | edit‑in‑place todo list using `call-in-slot` |
| `/wizard`        | multi‑step form with a real Back button |
| `/chat`          | pub/sub across browser tabs |
| `/calendar`      | structured event payloads |
| `/file-upload`   | zero‑JS multipart uploads |
| `/clock`         | scheduled `after` events |
| `/seaside-todo`  | a fuller port of the HPI *Introduction to Seaside* tutorial |

Install the [Datastar Inspector](https://data-star.dev/) browser
extension to watch the SSE stream live; on the REPL side,
`(s/inspect cid)` prints the live conversation summary and
`(s/replay :some/root [{:event :submit}])` walks the same path without
a browser.

---

## Status

stube is pre‑1.0. The public surface — everything in
`dev.zeko.stube.core` — is intended to stay stable; anything outside
that namespace is internal until 1.0.

Bug reports, design discussions and Seaside‑veteran war stories are
all welcome.

## License

MIT.

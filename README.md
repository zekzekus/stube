# stube

> stube is a personal research project — a Clojure exploration of an
> idea about web frameworks that has been pulling at me for nearly
> twenty years: that page boundaries should be invisible, that HTML
> should be data in the host language, and that one component should
> be able to *call* another like a function and read its *answer*
> like a return value. The lineage is Seaside and the UCW family; the
> wire is [Datastar](https://data-star.dev/); the implementation is a
> small effect kernel over plain Clojure maps, written hand-in-hand
> with LLM assistants. [The rationale page](docs/rationale.md) has
> the full story.
>
> Components are plain data. The conversation is a value. The server
> does the rendering; the browser just morphs the patches.

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
              :save   [(s/call :ui/confirm {:question "Save changes?"} :on-confirmed)]
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

## What stube explores

A handful of long-standing ideas, combined now that the missing
pieces finally exist:

- **Components are values.** A component is a map of pure functions.
  The kernel is one multimethod and a fold. No class hierarchy, no
  lifecycle interface, no special object identity.

- **Effects are values.** Handlers return `[self' effects]`. Every
  interaction is inspectable at the REPL with no server running —
  `(s/replay :my/root [{:event :submit}])` walks the same code path
  the browser does.

- **Call and answer.** Components call other components like functions
  and read the answer like return values. Wizards, confirmations,
  in-place editors, login flows: one primitive. This is the Seaside
  idea, rebuilt.

- **Linear flows.** `(s/defflow ...)` writes what would have been a
  state machine as straight-line Clojure with `(s/await child-embed)`
  for suspend points. The macro compiles down to a regular component
  using [cloroutine](https://github.com/leonoel/cloroutine).

- **Datastar over the wire.** SSE patches, morph by id. The only
  client-side code is the Datastar runtime itself. Two-way bindings
  through `s/bind`; events through `s/on`.

- **Persistent history.** Every dispatch snapshots the previous
  conversation onto `:conv/history`. The conversation-level Back
  button is one line.

- **Small async surface.** Scheduled events (`s/after`),
  publish/subscribe (`s/publish!` / `s/subscribe`), and zero-JS
  multipart uploads route back into normal component handlers.

---

## Host widget integration

Third-party widgets such as CodeMirror, Monaco, Chart.js and Leaflet
often own the DOM under one host element. Mark that host with
`s/preserve`: stube still lets Datastar merge attributes on the host,
but leaves the live child nodes alone across future morphs. Pair it
with `s/on-mount` to run the widget constructor once, when the element
first appears.

```clojure
[:div (merge {:class "editor-host"
              :data-doc-id (:doc-id self)}
             (s/preserve self :editor)
             (s/on-mount self :editor
               "(() => {
                  if (el.cmView) return
                  el.cmView = new EditorView({
                    doc: el.dataset.initialDoc || '',
                    extensions: [basicSetup],
                    parent: el
                  })
                })()"))]
```

The stock stube shell loads `/stube/preserve.js` before Datastar, so
standalone apps get the bridge automatically. If you embed stube with
`shell-for`, include that script in your host page before the Datastar
runtime:

```html
<script type="module" src="/widget/stube/preserve.js"></script>
<script type="module" src="https://cdn.jsdelivr.net/gh/starfederation/datastar@main/bundles/datastar.js"></script>
```

If you run your own Idiomorph bridge outside Datastar, use the same
`data-stube-preserve` marker and merge attributes before telling
Idiomorph to skip the subtree:

```js
function mergeAttributes(oldEl, newEl) {
  for (const attr of Array.from(oldEl.attributes)) {
    if (!newEl.hasAttribute(attr.name)) oldEl.removeAttribute(attr.name)
  }
  for (const attr of Array.from(newEl.attributes)) {
    if (oldEl.getAttribute(attr.name) !== attr.value) {
      oldEl.setAttribute(attr.name, attr.value)
    }
  }
}

Idiomorph.morph(oldRoot, newRoot, {
  callbacks: {
    beforeNodeMorphed(oldNode, newNode) {
      if (!(oldNode instanceof Element) || !(newNode instanceof Element)) return true
      const key = oldNode.getAttribute('data-stube-preserve')
      if (!key || newNode.getAttribute('data-stube-preserve') !== key) return true
      mergeAttributes(oldNode, newNode)
      return false
    }
  }
})
```

---

## Trying it

stube is on Clojars. Add to `deps.edn`:

```clojure
{:deps
 {dev.zeko/stube {:mvn/version "0.0.2"}}}
```

Then in code:

```clojure
(require '[dev.zeko.stube.core :as s])
```

The version will move freely while the API settles; nothing in
`dev.zeko.stube.core` is expected to change shape, but anything
outside it is internal.

---

## Embedding in an existing Ring app

For host apps that already own their HTTP stack, use the stable
embedder surface in `dev.zeko.stube.kernel` plus the Ring adapter:

```clojure
(require '[dev.zeko.stube.adapter.ring :as stube-ring]
         '[dev.zeko.stube.kernel :as stube])

(def stube-kernel
  (stube/make-kernel
    {:base-path "/widget"
     :session-id-fn (fn [request] (get-in request [:session :id]))
     :context-fn    (fn [request] {:db (:db request)})}))

;; Add these routes to your Reitit/Ring route table:
(stube-ring/ring-routes stube-kernel)
```

Stable embedder API:

- `stube/make-kernel` creates an isolated runtime instance.
- `stube/mint-conversation!` registers a root component for a request.
- `stube/shell-for` returns a Hiccup fragment for the host layout.
- `stube/dispatch!` dispatches into a live conversation and returns fragments.
- `stube/replay` runs the same interaction path without mutating runtime state.
- `stube/halt!` closes open streams and clears runtime registries.

`examples/dev/zeko/stube/examples/embedded_ring.clj` shows a plain Ring
host serving `/healthz` and `/api/foo` beside a stube widget under
`/widget`.

### kit-clj / Integrant

Already on [kit-clj](https://kit-clj.github.io/)?  Three lines in
`system.edn` and your project mounts a stube widget alongside the rest
of your routes:

```clojure
;; system.edn
{:stube/kernel
 {:base-path     "/stube"
  :context-fn    #ig/ref :app/context-fn
  :session-id-fn #ig/ref :app/session-id-fn}

 :reitit.routes/stube
 {:kernel #ig/ref :stube/kernel}}
```

Then `:require` the adapter once during system load so the Integrant
multimethods are installed:

```clojure
(require 'dev.zeko.stube.kit)
```

stube core has no Integrant dependency; the adapter ns is the only
place `integrant.core` is referenced.  Add `integrant/integrant` to
your own `deps.edn` to use it.

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

This is a personal research project, not a product. The implementation
is real and the bundled examples work, but the API will move as the
underlying experiment evolves. Anything in `dev.zeko.stube.core` is
treated as the stable surface; anything else is internal.

Bug reports, design discussions and Seaside-veteran war stories are
all welcome — the more company on this thread, the better.

## License

MIT.

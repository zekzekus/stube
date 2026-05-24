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
              :save   [(s/call (s/confirm "Save changes?") :on-confirmed)]
              :cancel [(s/answer :cancelled)]))
  :on-confirmed
  (fn [self yes?]
    [(s/answer (if yes? :saved :cancelled))]))
```

`(s/confirm "Save changes?")` returns an embed spec for the stock
confirmation component. Calling it stacks that component on top of the
current frame. When the user clicks Yes or No it answers back — and
your `:on-confirmed` resume fires with the value. The component doing
the asking never thinks about callbacks, promises, or modal state
machines: it just calls a question and reads the answer. That is the
Seaside model, rebuilt for 2026.

---

## Documentation

|   |   |
|---|---|
| [**Tutorial**](docs/tutorial.md) | Build a real app, step by step. Start here. |
| [**API reference**](docs/api.md) | Every public function in `dev.zeko.stube.core`. |
| [**Internals**](docs/internals.md) | How the kernel, conversation and effects fit together. |
| [**Rationale**](docs/rationale.md) | Why stube exists. Seaside, the uncommon web, and where the model came from. |
| [**Changelog**](CHANGELOG.md) | Big-rock changes by release/development pass. |

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
`shell-for`, include `(stube/head-tags kernel)` in your host page's
`<head>`; it returns the stock CSS (unless disabled), the preserve
bridge, Datastar, and optional halos tooling with the right
`:base-path` applied:

```clojure
[:html
 (into [:head [:title "Host app"]]
       (stube/head-tags stube-kernel))
 [:body
  (stube/shell-for stube-kernel cid)]]
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
 {dev.zeko/stube {:mvn/version "0.1.0"}}}
```

Then in code:

```clojure
(require '[dev.zeko.stube.core :as s])
```

The version will move freely while the API settles. Component authors
should stay in `dev.zeko.stube.core`. Host frameworks may also use the
embedder surface in `dev.zeko.stube.kernel` and
`dev.zeko.stube.adapter.ring`; other namespaces are internal.

### Datastar SDK pinning

stube pins the Datastar SDK transitively via its
`dev.data-star.clojure/http-kit` dependency. **Do not** add
`dev.data-star.clojure/sdk` as a direct dep in your project — let the
transitive pin do its job, otherwise a version mismatch between the
SDK and the http-kit adapter can produce silent SSE breakage that is
painful to track down. If you need a newer SDK, upgrade stube.

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
- `stube/head-tags` returns the CSS/script tags required by that fragment.
- `stube/dispatch!` dispatches into a live conversation and returns fragments.
- `stube/publish!` publishes from host code into that runtime kernel.
- `stube/replay-with` runs the same interaction path against a kernel configuration without mutating runtime state.
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

The example browser at `/` indexes every shipped demo with a blurb
and a deep link. A few highlights, grouped by what each demo exercises
first:

| group | path | what it shows |
|-------|------|---------------|
| Foundation | `/guess`             | `defflow` — guess‑the‑number as straight‑line code |
| Foundation | `/multicounter`      | three embedded counters; morph‑by‑id keeps siblings intact |
| Foundation | `/wizard`            | multi‑step form whose Back button rewinds the conversation |
| Tier 1     | `/calc`              | dense button‑event routing in one component |
| Tier 1     | `/dialogs`           | reusable confirm/prompt/choose via call/answer |
| Tier 1     | `/tabs`              | inactive embedded children stay alive off‑screen |
| Tier 1     | `/calendar`          | structured per‑cell click payloads |
| Tier 1     | `/todo`              | edit‑in‑place todo list using `call-in-slot` |
| Tier 2     | `/paginated-list`    | pagination plus an EDN‑safe row‑render callback |
| Tier 2     | `/table-report`      | column config maps and click‑to‑sort headers |
| Tier 2     | `/tree`              | recursive rendering and per‑node expansion |
| Tier 2     | `/breadcrumb`        | a base page wrapped with `s/decorate` |
| Tier 2     | `/example-browser`   | dynamic mount/registry lookup plus detail child swapping |
| Tier 2     | `/url-counter`       | `s/history` syncs counter value into the address bar |
| Tier 3     | `/file-upload`       | zero‑JS multipart uploads via `:upload-received` |
| Tier 3     | `/clock`             | cid‑scoped scheduled events via `s/after` |
| Tier 3     | `/shared-counter`    | shared app state plus topic subscriptions |
| Tier 3     | `/chat`              | pub/sub across browser tabs |
| Tier 3     | `/protected-counter` | app login composed with cid owner cookies |
| Tier 3     | `/preserved-widget`  | `s/preserve` keeps third‑party DOM children alive |
| Tier 3     | `/error-frame`       | a throwing handler turns into a local banner, SSE intact |
| Tier 3     | `/columns`           | `s/keyed-children` adds/removes/replaces a column |
| Book app   | `/seaside-todo`      | a fuller port of the HPI *Introduction to Seaside* tutorial |
| Book app   | `/kasten`            | horizontal stack of open note columns, wiki‑links, slot‑local editing |

Install the [Datastar Inspector](https://data-star.dev/) browser
extension to watch the SSE stream live; on the REPL side,
`(s/inspect cid)` prints the live conversation summary and
`(s/replay :some/root [{:event :submit}])` walks the same path without
a browser.

---

## Status

This is a personal research project, not a product. The implementation
is real and the bundled examples work, but the API will move as the
underlying experiment evolves. Component definitions currently live in
a process-global registry; that is an intentional hot-reload-friendly
trade-off for now. Runtime state (conversations, SSE sessions, timers,
subscriptions) lives on isolated kernel values, so embedded kernels do
not share live state.

Bug reports, design discussions and Seaside-veteran war stories are
all welcome — the more company on this thread, the better.

## License

MIT.

# stube

A Clojure component framework over [Datastar](https://data-star.dev/) — Seaside-style
callable components, modelled as plain values, evaluated by a small effect kernel.

> **Status:** early, but the core slices are in place: `defflow`, embedded
> children, history/back, persistence, stock UI helpers, basic operations,
> timers, topic delivery, and multipart uploads.
> See [`docs/v2_1.md`](./docs/v2_1.md) for the current design notes.

## Highlights

- **Components are data** — a component is a map of pure functions. The kernel
  is one multimethod and a fold.
- **Effects as data** — handlers return `[self' [[:call …] [:patch …] …]]`.
  Every interaction is inspectable in the REPL with no server running.
- **Datastar over the wire** — morph-by-id SSE patches; no client code beyond
  the Datastar runtime.
- **Persistent history** — every dispatch snapshots the previous conversation
  to `:conv/history`. The back button is one line (slice 3).
- **Small async surface** — scheduled events, live topic delivery, and
  zero-JS multipart uploads route back into normal component handlers.

## Quick look

```clojure
(require '[stube.core :as s])

(s/defcomponent :ui/prompt
  :init   (fn [{:keys [text]}] {:text text :answer ""})
  :keep   #{:answer}
  :render (fn [self]
            [:form (merge {:id (:instance/id self)} (s/on self :submit))
             [:p (:text self)]
             [:input (merge {:type "number" :name "answer"} (s/bind :answer))]
             [:button "OK"]])
  :handle (fn [self _]
            [self [[:answer (parse-long (str (:answer self)))]]]))

(s/defcomponent :demo/guess
  :init   (fn [_] {:target (inc (rand-int 100)) :attempts 0})
  :start  (fn [self]
            [self [[:call (s/embed :ui/prompt {:text "1–100?"})
                    :resume :on-guess]]])
  :on-guess (fn [self n]
              (let [self' (update self :attempts inc)]
                (cond
                  (< n (:target self'))
                  [self' [[:call (s/embed :ui/prompt {:text "too low"})
                           :resume :on-guess]]]
                  (> n (:target self'))
                  [self' [[:call (s/embed :ui/prompt {:text "too high"})
                           :resume :on-guess]]]
                  :else
                  [self' [[:end {:winner true :attempts (:attempts self')}]]]))))

(s/mount! "/guess" :demo/guess)
(s/start! {:port 8080})
```

Then visit <http://localhost:8080/guess>.

## Running

This project assumes the development environment provided by `flake.nix`.

```nu
# enter dev shell
nix develop

# run the bundled example browser, including Tier 3 demos, then visit http://localhost:8080/
clojure -M:examples

# run tests
clojure -X:test
```

The shell links the tiny stock stylesheet at `/stube/ui.css` by default;
start with `{:ui-css? false}` if you want a completely unstyled shell.
Production starts can also set `:conversation-ttl` (a `java.time.Duration`
or millisecond integer) to enable the background conversation reaper.

Debugging tip: install the browser's Datastar Inspector extension and
watch the `/conv/<cid>/sse` stream while clicking through an example.
On the REPL side, `(s/inspect cid)` prints the live conversation summary
and `(s/replay :some/root [{:event :submit}])` replays pure events
without starting a server.

## Layout

| path                  | purpose                                                 |
|-----------------------|---------------------------------------------------------|
| `src/stube/core.clj`  | public API surface                                      |
| `src/stube/kernel.clj`| pure `step` / `run-effects` / `dispatch`                |
| `src/stube/conversation.clj` | conversation/instance data model and helpers     |
| `src/stube/registry.clj`     | component registry                               |
| `src/stube/render.clj`       | hiccup → HTML, Datastar attribute helpers        |
| `src/stube/ui.clj`           | stock dialogs and default UI classes             |
| `src/stube/http.clj`         | ring handlers (`/`, SSE, event, back, upload)   |
| `src/stube/server.clj`       | http-kit lifecycle, in-memory stores             |
| `examples/`                   | runnable demos                                  |
| `docs/v2.md`, `docs/v2_1.md`  | design notes and current revision               |

## Design

See [`docs/v2.md`](./docs/v2.md) and the current revision in
[`docs/v2_1.md`](./docs/v2_1.md). The implementation keeps the same
shape: component definitions are maps, conversations are EDN-oriented
values, and the HTTP layer is the only Datastar-specific boundary.

## License

MIT.

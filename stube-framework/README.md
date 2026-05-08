# stube

A Clojure component framework over [Datastar](https://data-star.dev/) — Seaside-style
callable components, modelled as plain values, evaluated by a small effect kernel.

> **Status:** very early. This repository contains *slice 0* of the design in
> [`v2.md`](./v2.md): primitives, hand-rolled tasks, no `defflow` macro yet.

## Highlights

- **Components are data** — a component is a map of pure functions. The kernel
  is one multimethod and a fold.
- **Effects as data** — handlers return `[self' [[:call …] [:patch …] …]]`.
  Every interaction is inspectable in the REPL with no server running.
- **Datastar over the wire** — morph-by-id SSE patches; no client code beyond
  the Datastar runtime.
- **Persistent history** — every dispatch snapshots the previous conversation
  to `:conv/history`. The back button is one line (slice 3).

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

# run the bundled example
clojure -M:examples

# run tests
clojure -X:test
```

## Layout

| path                  | purpose                                                 |
|-----------------------|---------------------------------------------------------|
| `src/stube/core.clj`  | public API surface                                      |
| `src/stube/kernel.clj`| pure `step` / `run-effects` / `dispatch`                |
| `src/stube/conversation.clj` | conversation/instance data model and helpers     |
| `src/stube/registry.clj`     | component registry                               |
| `src/stube/render.clj`       | hiccup → HTML, Datastar attribute helpers        |
| `src/stube/http.clj`         | ring handlers (`/`, `/conv/:cid/sse`, `…/event`) |
| `src/stube/server.clj`       | http-kit lifecycle, in-memory stores             |
| `examples/`                   | runnable demos                                  |
| `v2.md`                       | the design this implements                      |

## Design

See [`v2.md`](./v2.md). Slice 0's scope is:

- Component registry, `defcomponent`.
- Conversation atom, `dispatch`, `step`, `run-effects`.
- HTTP wiring (start, sse, event) on http-kit.
- `s/on` and `s/bind` Hiccup helpers, `_meta` signal convention.
- Hand-rolled tasks (no `defflow` macro yet).
- One demo (guess-the-number).

The macro `defflow`, embedding/decorations, persistent history UI, and
operations come in subsequent slices.

## License

MIT.

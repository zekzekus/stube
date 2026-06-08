# stube as a Kit module

[kit-clj](https://kit-clj.github.io/) projects can add stube the same
way they add SQL, HTML, or any other capability: through the
[module system](https://kit-clj.github.io/docs/modules.html). This repo
ships a `:stube/kernel` module so that one REPL call drops a working,
server-driven stube widget into an existing Kit app — and one call
takes it back out.

If you'd rather wire stube by hand (it's only three lines of
`system.edn`), skip to [The manual equivalent](#the-manual-equivalent)
below; the module just automates exactly that.

---

## What the module does

Installing `:stube/kernel` performs four idempotent changes to your Kit
project:

| Target | Change |
|---|---|
| `deps.edn` | merges `dev.zeko/stube {:mvn/version "0.9.1"}` into `:deps` |
| `resources/system.edn` | merges `:stube/kernel` and `:reitit.routes/stube` Integrant keys |
| `src/clj/<proj>/core.clj` | appends requires for `dev.zeko.stube.kit` and `<proj>.stube` |
| `src/clj/<proj>/stube.clj` | new file — a starter counter component |

`:stube/kernel` builds an isolated stube runtime (see
[`dev.zeko.stube.embed/make-kernel`](api.md)). `:reitit.routes/stube`
is contributed by stube's Integrant adapter,
[`dev.zeko.stube.kit`](../src/dev/zeko/stube/kit.clj); because it
`derive`s from `:reitit/routes`, Kit's `#ig/refset :reitit/routes`
picks it up automatically with no extra wiring.

The `:mounts` entry maps a page URL to a registered root component, so
GET `/stube` returns a complete stube shell (HTML page + Datastar +
the SSE bootstrap) out of the box.

```diagram
resources/system.edn
─────────────────────────────────────────────────────────────
 :stube/kernel  { :base-path "/stube" }
       │  dev.zeko.stube.kit  →  embed/make-kernel
       ▼
 :reitit.routes/stube  { :kernel #ig/ref :stube/kernel
                         :mounts { "/stube" :<proj>/counter } }
       │  derive :reitit.routes/stube :reitit/routes
       ▼
 :router/routes  { :routes #ig/refset :reitit/routes }   ← auto-collected
       ▼
 :router/core → :handler/ring → :server/http

src/clj/<proj>/core.clj  (:require …)
─────────────────────────────────────────────────────────────
  [dev.zeko.stube.kit]   ; registers the two ig/init-keys
  [<proj>.stube]         ; registers the :<proj>/counter component
```

---

## Quick start

### 1. Point your project at this repo

Kit reads module repositories from your project's `kit.edn`. Add the
stube repo alongside the official one:

```clojure
;; kit.edn
{:full-name "com.example/myapp"
 :ns-name   "com.example.myapp"
 :sanitized "com/example/myapp"
 :name      "myapp"
 :modules
 {:root "modules"
  :repositories
  [{:url  "https://github.com/kit-clj/modules.git" :tag "master" :name "kit-modules"}
   {:url  "https://github.com/zekzekus/stube.git"  :tag "master" :name "stube"}]}}
```

Kit clones this repo to `modules/stube/` and finds the
[`modules.edn`](../modules.edn) at its root, which registers
`:stube/kernel` and resolves its files under `kit/stube/`.

### 2. Sync and install from the REPL

Kit aliases its generator as `kit` in the `user` namespace:

```clojure
user=> (kit/sync-modules)
:done

user=> (kit/list-modules)
;; … the official modules …
:stube/kernel - embeds a stube (Datastar) widget — kernel, Reitit routes, and a starter component mounted at /stube
:done

user=> (kit/install-module :stube/kernel)
updating file: deps.edn
updating file: resources/system.edn
updating file: src/clj/com/example/myapp/core.clj
applying
 action: :append-requires
 value: ["[dev.zeko.stube.kit]" "[com.example.myapp.stube]"]
stube installed. Restart the REPL, run (go), and open http://localhost:3000/stube
:stube/kernel installed successfully!
restart required!
```

### 3. Restart and visit

Because the module adds a Maven dependency, the JVM must be restarted:

```clojure
user=> (go)   ; after a REPL restart
```

Open <http://localhost:3000/stube> — that's the starter counter, served
entirely by stube. No JavaScript build step, no client/server contract
to hand-maintain.

---

## Building your own components

Edit the generated `src/clj/<proj>/stube.clj`. A component is a map of
pure functions; the kernel is one fold over plain Clojure maps:

```clojure
(s/defcomponent :myapp/greeting
  :init   (fn [_] {:name "world"})
  :render (fn [self]
            [:div (s/root-attrs self)
             [:input (s/bind self :name)]
             [:p "Hello, " (:name self) "!"]]))
```

Then mount it by adding to `:reitit.routes/stube`'s `:mounts` map in
`resources/system.edn`:

```clojure
 :reitit.routes/stube
 {:kernel #ig/ref :stube/kernel
  :mounts {"/stube"  :myapp/counter
           "/hello"  :myapp/greeting}}
```

New components in *other* namespaces only need to be `require`d
somewhere that loads at startup (e.g. add them to the `:require` block
in `core.clj`, next to `[<proj>.stube]`) so `s/defcomponent` registers
them before `ig/init` runs.

See the [tutorial](tutorial.md) for the full component model —
call/answer, `defflow`, history, uploads, pub/sub — and the
[API reference](api.md) for every public function.

---

## Configuration

`:stube/kernel` accepts the full option set of
[`embed/make-kernel`](api.md#application-boundaries). The defaults are
deliberately complete (in-memory store, cookie-based `stube_sid`
sessions, nil app context), so the module only sets `:base-path`. Common
additions:

```clojure
 :stube/kernel
 {:base-path     "/stube"
  ;; opaque per-request app value, read with (s/app):
  :context-fn    #ig/ref :app/context-fn
  ;; persisted identity, read with (s/principal):
  :principal-fn  #ig/ref :app/principal-fn
  ;; reuse your app's session id instead of stube's own cookie:
  :session-id-fn #ig/ref :app/session-id-fn}
```

Each `#ig/ref` points at an Integrant key you define in your own app
that returns a function value. See the *Application boundaries* section
of the [API reference](api.md) for the `:context-fn` / `:principal-fn`
contract.

`:base-path` only prefixes stube's generated SSE/asset/event URLs; the
page mount paths in `:mounts` are independent and can live anywhere in
your route tree.

### Rendering stube inside your own Kit pages

The `:mounts` shell is a standalone HTML page. To embed a widget inside
a page your app already renders (e.g. a `:kit/html` Selmer page), drop
the `:mounts` entry and instead, from a page handler, call
`embed/mint-conversation!`, splice `embed/shell-for` into the body, and
`embed/head-tags` into the `<head>`. The
[README's embedding section](../README.md#embedding-in-an-existing-ring-app)
and `examples/dev/zeko/stube/examples/embedded_ring.clj` show the
pattern. Non-chassis renderers (hiccup2/rum/reagent) must re-wrap the
chassis `RawString` nodes `head-tags` returns — see
[Rendering head-tags through hiccup2 / rum / reagent](../README.md#rendering-head-tags-through-hiccup2--rum--reagent).

---

## Removing the module

```clojure
user=> (kit/remove-module :stube/kernel)
```

Kit reverses the injections and deletes the asset it created (using the
install-log checksums to avoid clobbering files you've since edited). If
you customized `stube.clj`, Kit warns instead of deleting; remove it by
hand if you no longer want it, then drop the `dev.zeko/stube` dep and
the `:stube/*` keys if the remover left them.

---

## The manual equivalent

The module is pure convenience. Wiring stube into a Kit/Integrant app by
hand is three lines of `system.edn` plus one `require`:

```clojure
;; resources/system.edn
{:stube/kernel
 {:base-path "/stube"}

 :reitit.routes/stube
 {:kernel #ig/ref :stube/kernel
  :mounts {"/stube" :myapp/counter}}}
```

```clojure
;; src/clj/myapp/core.clj — require once so the ig multimethods install
(:require … [dev.zeko.stube.kit] [myapp.stube])
```

plus `dev.zeko/stube` in `deps.edn`. stube core has no Integrant
dependency; `dev.zeko.stube.kit` is the only namespace that touches
`integrant.core`, and Kit projects already depend on Integrant.

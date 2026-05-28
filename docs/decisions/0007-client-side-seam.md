# 0007 — Behaviors, component-scoped CSS, and the client-side seam

## Context

Stube is a server-driven UI framework: SSE patches morph the DOM,
Datastar wires events, and the only piece of client code stube
historically owned was the preserve-bridge that lets third-party
widgets keep their subtree alive across morphs.

Real apps — `kasten` was the prompting one — accumulate a fair amount
of in-browser code that doesn't fit the preserve pattern: code editor
glue, drag-and-drop, autocomplete, dialog focus management. The
v0.1-era story was "host loads its own JS files in the page layout
and uses `s/on-mount` strings for setup." That worked, but pushed
every project to invent its own convention for *where the code
lives*, *how it gets attached to an element*, and *when it tears
down*. Kasten ended up with a homegrown `#notes-shell-state` channel,
a `MutationObserver`-per-feature loader, and ad-hoc file naming.

The same loose-stitching applies to CSS. Component class names were
free-floating, and the host carried responsibility for guaranteeing
that selectors did not collide across components.

The framework had hooks (`preserve`, `on-mount`, `on-unmount`,
`stube:patched`) but no opinion on the shape of larger client-side
code. We needed a posture — Rails-Stimulus-tight rather than
Django-loose — that pinned the seam without leaving the framework to
own the JavaScript itself.

## Decision

Three layered additions, all framework conventions, all opt-in:

1. **Every component root carries `data-stube-component="ns/name"`
   and `class="stube-c-<ns>-<name>"`**, emitted automatically by
   `s/root-attrs` from the registered component id. CSS and behaviors
   can address every instance by its keyword.

2. **`s/behavior self behavior-id args`** — a Stimulus-shaped
   primitive. Renders `data-stube-behavior="ns/name"` plus one
   `data-stube-arg-<k>` per arg. The shell loads `behaviors.js`,
   which on each `stube:patched` sweeps the document for the marker,
   lazy-imports the module from `<base>/behaviors/<ns>/<name>.js`,
   and calls `mount` / `patched` / `unmount` on a fixed `ctx` shape.

3. **File-convention CSS and JS** served from the host's classpath:

   | role | path served | source on classpath |
   |---|---|---|
   | per-component stylesheet | `<base>/styles/<ns>/<name>.css` | `resources/stube_styles/<ns>/<name>.css` |
   | declared module | `<base>/modules/<id>.js` | `resources/stube_modules/<id>.js` |
   | behavior module | `<base>/behaviors/<ns>/<name>.js` | `resources/stube_behaviors/<ns>/<name>.js` |

   `head-tags` walks the registry, emits one `<link>` per component
   whose stylesheet exists on the classpath, one `<script
   type="module">` per distinct `:modules` entry, and a single inline
   `<style>` block containing concatenated `:styles` chunks with `&`
   replaced by `[data-stube-component="ns/name"]`.

`s/preserve`, `s/on-mount`, and `s/on-unmount` stay as escape hatches
for one-off glue too small to deserve a behavior file.

## Considered and rejected

- **Cljs/sci compilation of Clojure to JS.** Tempting, rejected on
  taste. Stube's value is the seam; host devs already write JS.
- **Hashed/scoped class names à la CSS Modules.** Requires a build
  pipeline and breaks "host writes plain CSS that you can read at
  the inspector."
- **Auto-discovery of behaviors from component id alone (Stimulus's
  full convention).** Tighter, but introduces invisible coupling
  during async loaders and tests. Kept behaviors explicit on the
  element via `s/behavior`.
- **Eager-bundle every behavior into a single page-load script.**
  Discarded in favour of lazy `import()` so the head stays small and
  behaviors used in only one corner of the app cost nothing on the
  rest.

## Consequences

- The framework now ships two bridge scripts (`preserve.js`,
  `behaviors.js`) instead of one. They are versioned together with
  the SDK; hosts include both via `head-tags`.
- `s/root-attrs` is now load-bearing for CSS and JS, not just morph.
  Components that hand-build their root attribute map (without
  calling `s/root-attrs`) opt out of the auto-attrs — that's
  acceptable because every example and test goes through the
  helper.
- Asset segments are restricted to `[A-Za-z0-9_-]`. Components named
  with characters outside that set (already disallowed by Clojure
  keyword syntax for `ns/name`) are fine; URL traversal is rejected
  at the route layer before any `io/resource` call.
- The bridges add two document-wide `MutationObserver`s
  (preserve.js's detach detection and behaviors.js's unmount sweep).
  Both observe `document.body` with `childList + subtree`; for the
  scale of DOM stube touches this is negligible.
- `head-tags` does `io/resource` per registered component on every
  page load. In production the JVM resource lookup is cheap and
  cacheable behind the static-assets cache header; we have not yet
  needed a registry-side memoization.

## Where to look

- `src/dev/zeko/stube/render.clj` — `root-attrs`, `behavior`,
  `component-style-url`, `behavior-module-url`.
- `src/dev/zeko/stube/shell.clj` — `head-tags` registry walk.
- `src/dev/zeko/stube/http.clj` — asset handlers and the
  `safe-asset?` segment check.
- `src/dev/zeko/stube/adapter/ring.clj` — the catchall route
  declarations.
- `resources/dev/zeko/stube/behaviors.js` — the bridge.
- `test/dev/zeko/stube/shell_test.clj`,
  `test/dev/zeko/stube/render_test.clj` — exercise the surface.

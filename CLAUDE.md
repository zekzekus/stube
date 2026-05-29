# CLAUDE.md

Working notes for Claude Code when collaborating on this repository.
The full project-wide preferences live in [`AGENTS.md`](AGENTS.md);
read that first.  This file calls out the few stube-specific
conventions that are easy to get wrong without prior context.

## Framework conventions

- **Signal wire casing is a kernel-wide choice.** Always reach for
  `s/bind` / `s/local-bind` / `s/$` / `s/signal` rather than
  hand-rolling `data-bind:…` attributes or reading `:signals` off
  events directly. Hosts pick `:signal-case` once on `make-kernel`
  (`:kebab` default; `:camel` when inline Datastar expressions
  reference a signal, because JS identifiers can't contain dashes)
  and the helpers stay in lock-step on both sides. A per-call
  `{:case ...}` opt overrides for one site.
- **Behaviors write signals via `ctx.setSignal` / `ctx.patchSignals`,
  which flow through Datastar's public `data-bind` attribute API.**
  The component renders `(s/signal-mirror :foo)` once per writable
  signal — that emits a hidden `<input data-bind:<wire>>` marked with
  `data-stube-signal-mirror="<wire>"`, and the bridge writes to it
  by setting `.value` and dispatching a DOM `input` event. Datastar's
  own `data-bind` handler does the rest. Do not import Datastar's
  ESM exports or read `globalThis.ds` from the bridge: both couple us
  to Datastar internals and have already broken twice (0.3.1's
  `globalThis.ds` poke, 0.3.3's outbound `datastar-signal-patch`
  dispatch). The `data-bind` seam works against the public attribute
  surface every Datastar app already relies on. Reads
  (`ctx.signals.get`) read from the mirror's `.value` first with a
  best-effort `globalThis.ds` fallback.
- **`embed/head-tags` is chassis-flavoured.** The returned Hiccup tree
  wraps inline `<script>` / `<style>` bodies in chassis `RawString`.
  Hosts rendering through hiccup2 / rum / reagent SSR must re-wrap
  those instances in the renderer's own raw primitive before
  emitting, or the bodies are HTML-escaped and inline scripts fail
  to parse.

## When to update which file

- `docs/api.md` — the reference; describes the **latest** state of
  every public surface. Update here when adding or changing a public
  function/option.
- `CHANGELOG.md` — append to the `## Unreleased` section. Describe
  the change, not the history of how we got there.
- `todo.md` — track open framework work. Move resolved items out;
  archived history lives under `docs/archive/`.
- `kasten/stube_notes.md` (sibling repo, not in this tree) — the
  evidence trail from a real embedder. Reach for it when designing
  framework changes that should be useful beyond one host.

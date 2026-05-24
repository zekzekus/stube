# Changelog

Big-rock changes to stube. Released versions may batch more than one
development entry.

## Unreleased

- API polish pass before 1.0:
  - `s/back` is now a zero-arity function `(s/back)` returning the
    `[:back]` effect, matching every other effect constructor. Call
    sites that used the bare value need a pair of parens.
  - `dev.zeko.stube.kernel/replay` is now `kernel/replay-with` so the
    kernel-aware host helper no longer collides in name with the
    kernel-less `core/replay` used by component-author tests.
  - `registry/register!` now lifts every colocated author key —
    `:start`, `:stop`, `:wakeup`, `:children` in addition to the
    previous `:init`/`:render`/`:handle`/`:keep`/`:doc`/`:state` — to
    its `:component/<name>` home. Component definitions registered via
    any entry point (`defcomponent`, `register-component!`,
    `decorate!`) are now uniform; the kernel reads them under a single
    namespace.
  - Updated the Seaside-todo menu example to use `s/on-target`
    against the parent iid instead of synthesising a fake parent
    instance map.
- Hardened stale-event handling: POSTs for missing instances inside a
  live conversation are now harmless `204` no-ops instead of ending the
  whole conversation; missing/ended conversations still surface the
  stale-page response.
- Tightened runtime isolation for embedders. Runtime state now lives on
  kernel values, shell/head helpers are public, and `s/publish!` routes
  to the active embedded kernel when called from component code.
- Made `:io` a runtime-interpreted effect. Pure `dispatch`/`replay`
  keep it inert unless a runtime hook is bound, preserving the kernel's
  value-oriented testability.
- Hardened keyed children: keyed metadata survives handler state
  replacement, children inherit conversation context, and changed embed
  args rebuild the child subtree while preserving the root iid.
- Added public cross-instance event targeting via `s/on-target`, with
  `s/event-url` documented as the low-level escape hatch.
- Made `s/call`, `s/become`, and `s/call-in-slot` accept existing embed
  specs directly, so stock helpers like `(s/confirm "?")` compose
  without reaching into internal effect constructors.
- Improved shell embedding: `stube/head-tags` now returns the stock CSS,
  preserve bridge, Datastar, and optional halos assets for the current
  kernel/base path; examples no longer reach into shell internals.
- Improved error targeting so first-render failures patch the shell root
  correctly instead of assuming the failing instance already exists in
  the DOM.
- Removed the unused legacy async registry namespace; timers, pub/sub,
  SSE sessions, pending roots, and shutdown state are owned by the
  embeddable runtime.
- Refreshed the public documentation set: README, tutorial, API
  reference, internals guide, historical design notes, roadmap, and this
  changelog now describe the current framework surface.

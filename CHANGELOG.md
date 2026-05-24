# Changelog

Big-rock changes to stube. Released versions may batch more than one
development entry.

## Unreleased

- Documentation: cross-process pub/sub is now called out as
  single-JVM-by-design in `docs/internals.md`, with a sketched
  `Publisher` protocol for the eventual seam if a real bus is ever
  wanted, plus a working-today recipe using `:app` as a bring-your-own
  bus. New `docs/decisions/` folder with four short ADRs covering
  resume-key naming, EDN-clean conversation state, the embed/call
  split, and the `:app` + `:principal-fn` contract.
- SSE heartbeat for reverse-proxy idle timeouts. Every kernel now runs
  a per-conversation keepalive thread that sends a `stube-keepalive`
  event (an SSE event-type Datastar ignores) every
  `:sse-keepalive-ms` milliseconds, default 15000. The heartbeat
  stops when the SSE channel unregisters or the kernel halts. Pass
  `nil` or `0` to disable. `docs/internals.md` carries an "SSE behind
  a reverse proxy" section with nginx, ALB, Caddy, and HAProxy knobs.
- `defflow` durability is now documented as a deliberate property,
  not a pending gap. A conversation containing a `defflow` is
  in-memory only by design (its cloroutine continuation is not
  EDN-serialisable). For long-running flows that must survive a
  restart, write the same shape as a hand-rolled task component with
  `:start` + named resume keys; the tutorial now shows the two side
  by side. The store's skip-on-defflow warning is more pointed about
  the workaround.
- Application-boundary primitives for embedders:
  - New `:app` option on `make-kernel` carries an opaque host value
    (typically a map of long-lived dependencies) that component code
    reads via `(s/app)`. The value is not serialised with the
    conversation; rebuild it from live JVM state on each
    `make-kernel` call.
  - New `:principal-fn` option is invoked once when a conversation is
    minted; its result is persisted on the conversation under
    `:conv/principal` and surfaced through `(s/principal)`. The
    principal is fixed for the life of the conversation — end and
    re-mint to change identity. There is no `set-principal!`
    operation by design.
  - Both options are accepted by `s/start!` for the standalone
    server. The `protected_counter` example now reads its principal
    via `(s/principal)` instead of carrying app-level login state on
    the conversation.
- Extracted `dev.zeko.stube.embed` as the documented host-embedder
  namespace. `dev.zeko.stube.kernel` is back to being just the pure
  effect fold (`step`, `run-effects`, `dispatch`, `boot`,
  `resume-top`, `redraw-top`). Internal callers, tests, examples, and
  docs have been updated. The §15.4 line-count invariant has been
  replaced by a structural one: the runtime stays organised around a
  single effect multimethod.
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

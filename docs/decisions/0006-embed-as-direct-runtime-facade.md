# 0006 — `dev.zeko.stube.embed` is a direct runtime facade

## Context

`dev.zeko.stube.embed` is the host-facing API: drop stube into a Ring
app, an Integrant system, or a test harness without taking on the
standalone `dev.zeko.stube.server` lifecycle.

Until R1, every public fn in `embed.clj` reached into
`dev.zeko.stube.runtime` through a `runtime-var` helper that called
`requiring-resolve`. The original goal was to keep the pure
value-language in `dev.zeko.stube.kernel` from transitively pulling
the mutable runtime in at load time: if `kernel` only sees `embed`'s
forwarders, and the forwarders resolve runtime lazily on first call,
then `(s/dispatch …)` and `(s/replay …)` stay testable from the REPL
without `runtime` ever loading.

Two things eroded that design:

- **The constraint stopped applying.** `kernel.clj` does not require
  `embed.clj` either way. Side-effecting hooks (timers, pub/sub, IO)
  are dynamic vars rebound by the runtime at dispatch time, not
  static dependencies. The pure kernel can be exercised in isolation
  whether or not `embed` forwards to `runtime` directly, and the new
  `load_direction_test` (R1-12) codifies that the pure namespaces
  must not transitively load runtime/server/http/adapter.
- **The surface accreted.** 25 of `embed.clj`'s ~37 fns were
  `^:no-doc` plumbing — `swap-conv!`, `register-sse!`, `apply-conv!`,
  `enable-halos!`, and so on — that existed only because `http.clj`,
  `halos/http.clj`, and `server.clj` happened to call them. They
  were not part of the documented embedder surface, but they sat
  inside the embedder namespace because that's where the indirection
  shim lived.

## Decision

Three changes:

1. **`embed.clj` is a thin, fully-public facade over
   `dev.zeko.stube.runtime`.** Replace the `runtime-var` helper with
   a normal `:require [dev.zeko.stube.runtime :as rt]`. Each public
   fn becomes a one-liner (`(rt/make-kernel opts)`, etc.). The
   namespace now exposes ten functions, all documented: `make-kernel`,
   `mint-conversation!`, `shell-for`, `head-tags`, `dispatch!`,
   `replay-with`, `halt!`, `shutting-down?`, `publish!` (and the
   docstring entry point).
2. **The `^:no-doc` adapter plumbing moves back to
   `dev.zeko.stube.runtime`** where it already lived. `http.clj`,
   `halos/http.clj`, `adapter/ring.clj`, and `server.clj` now
   `:require` the runtime directly and call `rt/foo` instead of
   `embed/foo` for these names. Hosts continue to reach for `embed`;
   adapters reach for `runtime`.
3. **`dev.zeko.stube.server` narrows to lifecycle plus a small
   default-kernel convenience surface.** It still owns the standalone
   atoms (`!kernel`, `!mounts`, `!server`, `!reaper-stop`), the
   `(s/start!)` + `(s/mount!)` flow, and a thin set of
   `(rt/foo (default-kernel) …)` wrappers for the public re-exports
   in `dev.zeko.stube.core`. The remaining ~20 wrapper fns that
   nothing outside the namespace needed are deleted.

This is not a supersession of an earlier ADR — the original
`requiring-resolve` choice was never recorded as a decision; the
rationale lived in the `embed.clj` ns docstring. ADR 0006 is the
first written form of the decision.

## Consequences

- **Smaller documented surface.** `embed.clj` shrinks from 37 fns
  (12 documented + 25 plumbing) to 10, all documented. A contributor
  reading the file no longer has to scroll past two screens of
  forwarders that adapters don't even use.
- **One less layer to follow.** Reading the dispatch path used to
  be `s/dispatch → server/dispatch! → embed/dispatch! → rt/dispatch!`.
  After R1 it's `s/dispatch → kernel/dispatch` (pure) for component
  code, or `embed/dispatch! → rt/dispatch!` for hosts and
  `rt/dispatch!` directly for adapter code.
- **Slight increase in `embed.clj`'s static dependency surface.**
  It now requires `runtime` at compile time, which means loading
  `embed` loads `runtime`. This is the cost. The pure namespaces
  still don't load it — confirmed by `load_direction_test`.
- **Adapters and hosts split clearly.** `embed` is the host's
  on-ramp; `runtime` is the adapter's. Tests that exercise the
  adapter API (`shutdown_test`, `kernel_isolation_test`,
  `server_test`, `http_test`) call `rt/foo` with an explicit kernel;
  tests of the host-facing `embed/*` surface keep using it.

# todo.md — stube, road to 1.0

This file used to track every open item between "the demos run" and a
1.0 we'd hand to someone else. As of this pass, the leverage items are
done; what remains are the small set of *deliberately deferred* design
spikes that should not be built without a real use case forcing the
shape.

Each item still here is labelled by its origin:

- **[bar §N]** — a §15 aesthetic / invariant target from `docs/v2_1.md`.
- **[carried]** — explicitly deferred in the tiered sweep
  (`docs/todo-tiers.md`); kept here because the framework should not
  ship 1.0 with it still pending.
- **[shape]** — surface noticed while reviewing the codebase. Not
  previously tracked.

> Historical record. The granular per-tier task list that drove slices
> 0–4 and the Seaside sweep lives at `docs/todo-tiers.md`. The
> finished road-to-1.0 work is captured in `CHANGELOG.md` under the
> `1.0.0` entry. The four ADRs in `docs/decisions/` record the design
> calls that fixed the noun set.

---

## 1. The §15 bar — what the codebase needs to be true *as* code

These are the non-functional bar the design explicitly set.

- [x] **Kernel reads as the effect language.** The runtime trampolines
      that used to bloat `kernel.clj` now live in
      `dev.zeko.stube.embed`; the kernel is back to step, run-effects,
      dispatch, boot, resume-top, redraw-top. The §15.4 hard line
      budget has been dropped in favour of a structural invariant —
      one effect multimethod — enforced by
      `invariant-test/kernel-stays-organized-around-one-effect-multimethod`.
      [bar §15.4]

- [x] **Errors are local, surfaced in the browser.** `dev.zeko.stube.errors`
      catches in `kernel/dispatch` and `frame/render-instance`, patches
      a `:stube-error` banner over the failing instance, logs the same
      to stderr, and keeps the SSE stream open. Optional `:on-error`
      override on `make-kernel`.
      [bar §15.3]

- [x] **Public surface is named and tight.** Component authors use
      `dev.zeko.stube.core`; host embedders use `dev.zeko.stube.embed`
      plus `dev.zeko.stube.adapter.ring`. Documented in `docs/api.md`
      and enforced by `invariant-test/examples-only-reach-the-public-surface`.
      [shape, bar §15.4]

---

## 2. Composition & flow primitives still open

Deliberately deferred design spikes. Each has no concrete use case
yet; each is a known shape we punted on. Treat them as **design
spikes**: write the smallest example that *needs* it before adding the
primitive.

- [ ] **`[:notify-parent k value]`.** A child pushing data to its
      parent without unmounting. Today the only return channel is
      `:answer`, which pops the child. Build the moment a real demo
      needs it; until then, document the deliberate gap.
      [carried §2]

- [ ] **`:rebuild-children` effect for lazy / conditional slots.**
      `:children` materialises eagerly at instantiation. A slot whose
      embed-spec depends on later state needs a kernel-level rebuild.
      `:call-in-slot` covers the local-composition case; this is for
      genuinely dynamic structural children.
      [carried §2]

- [ ] **`try` / `catch` across `s/await` in `defflow`.** Cloroutine
      restricts forms across yield points. We never spiked the exact
      limits.
      [carried §13 slice 1] [v2 §16 q1]

- [ ] **Error answers `[:answer-error e]` + `:on-error` resume.** Today
      a child can only succeed-and-answer or stay alive. A
      cancellation or thrown-from-child path needs symmetric plumbing.
      [carried §13 slice 1]

- [ ] **Fix the `:call-in-slot` previous-chain leak.** When a parent
      frame is replaced or ended before a slot child has answered,
      the slot child's `:instance/previous` pointer orphans every
      iid in that chain — they stay in `:conv/instances` with
      `:instance/parent` pointing at a removed iid. Surfaced by
      `kernel_property_test`; not yet fixed. The right shape is to
      walk previous chains in `pop-top` / `remove-subtree` and run
      their `:stop` hooks during sweep.
      [shape, surfaced by property test]

---

## 3. History & persistence

- [x] **Cloroutine continuation persistence is a documented property.**
      `defflow` instances are not EDN-serialisable; conversations
      containing them are in-memory only by design. Long-running
      flows that must survive a restart are written as hand-rolled
      task components (the tutorial has a side-by-side). The file
      store's skip-warning is pointed at the workaround.
      [carried §6] [bar §15.6]

- [ ] **Browser back-button glue (`popstate`).** A one-liner in the
      shell HTML, but only useful with a matching `pushState` policy.
      Skipping it means in-page `s/back-button` remains the only path;
      that's fine until a demo demonstrably needs the browser button.
      [carried §6]

---

## 4. Application boundaries

- [x] **App-store hook.** `:app` option on `make-kernel` carries an
      opaque host value read via `(s/app)`. Not persisted with the
      conversation; the host rebuilds it from JVM state on each
      kernel construction.
      [shape, ref `seaside-examples.md` ToDo findings]

- [x] **Application principal pass-through.** `:principal-fn` on
      `make-kernel` is invoked at mint time; the result is persisted
      on `:conv/principal` and surfaced via `(s/principal)`.
      `protected_counter.clj` now reads `(s/principal)` instead of
      keeping login state on the conversation. The framework-owner
      cookie still protects POSTs at the layer below.
      [shape, ref §16 carry-over from `v2_1.md`]

- [ ] **Non-shell HTTP routes for the same conversation.** The
      seaside_todo port called out the Atom feed chapter
      (`/atomTasks`): `start!` only mounts component shells and the
      conversation endpoints. A way to declare extra routes alongside
      a mount — same handler signature, same access to conversation
      state — would close the only "literal Seaside chapter doesn't
      port" gap.
      [shape, ref `seaside-examples.md` Atom feed]

---

## 5. Transport & deployment shape

- [x] **SSE keep-alive playbook.** A per-conversation heartbeat thread
      sends a `stube-keepalive` event every `:sse-keepalive-ms`
      (default 15s); Datastar ignores unknown event types so the
      client sees nothing while the proxy sees activity. The
      "SSE behind a reverse proxy" section of `docs/internals.md`
      covers nginx/ALB/Caddy/HAProxy config for the two distinct
      concerns (idle timeout, response buffering).
      [shape]

- [x] **Graceful shutdown.** `runtime/halt!` (called from `s/stop!`)
      freezes new mints with 503, cancels scheduled events and
      keep-alives, runs `:stop` for every live instance
      children-first, drains open SSE streams with a final `:close`
      fragment, and flushes the store before clearing registries.
      [shape]

- [x] **Datastar version pinning.** The Datastar SDK is pinned
      transitively via the http-kit adapter dep; the README warns
      hosts not to add their own SDK dep. Resolved in S-10.
      [shape]

- [x] **Cross-process pub/sub: scope documented.** `(s/publish! topic
      msg)` is single-JVM by design; `docs/internals.md` calls that
      out, sketches the `Publisher` protocol the eventual seam would
      take, and gives a working-today recipe (stand up your bus in
      `:app` and call its publish directly).
      [shape]

---

## 6. Documentation & onboarding

- [x] **Public-API reference doc.** `docs/api.md` covers the stable
      component-author API in `dev.zeko.stube.core` plus the stable
      host-embedding surface in `dev.zeko.stube.embed` /
      `dev.zeko.stube.adapter.ring`. The *Application boundaries*
      section describes the `:app` / `:principal-fn` contract.
      [shape, supports bar §15.4 "fits in your head"]

- [x] **"Build a small app" tutorial.** `docs/tutorial.md` walks a
      Clojure developer through a live todo board with bindings,
      call/answer, slot-local editing, pub/sub, `defflow`, and the
      `:app` / `:principal` wiring. The *Durable flows: defflow vs.
      task components* subsection shows the EDN-clean alternative
      side-by-side.
      [shape]

- [x] **Decision log per design question.** `docs/decisions/` carries
      ADRs for resume-key naming, EDN-clean state, embed-vs-call, and
      the app-store + principal contract.
      [shape]

---

## 7. Test & invariant coverage

- [x] **Property-style coverage of the kernel fold.**
      `kernel_property_test` generates random event sequences against
      a stub registry exercising the full effect vocabulary and
      asserts no-throw + EDN round-trip + fragment-targets-exist +
      structural soundness after every step. The first run surfaced
      the `:call-in-slot` previous-chain leak captured in §2.
      [shape, supports bar §15.6]

- [x] **`stube.invariant-test` enforces the public surface.**
      Existing rules (no `<script>` in examples, `defcomponent` only
      at top level, one effect multimethod) plus the new
      `examples-only-reach-the-public-surface` test: examples must
      not import `kernel`, `server`, `conversation`, `runtime`,
      `render`, `frame`, `fragments`, `http`, `lifecycle`, `effects`,
      or `registry`. `embedded_ring.clj` is the documented exception
      for `dev.zeko.stube.embed`.
      [shape, supports §1 above]

---

## 8. Deliberately *not* on this list

Carried forward from `v2_1.md` §16 — kept here so we don't add them by
accident:

- Time-travel UI (history exists; browsing it is an app).
- Server-side optimistic updates (Datastar does them client-side).
- First-class streaming flows (runtime `:io` plus events/publishes can
  cover it until a workload needs a primitive).
- Per-component CSS scoping (Hiccup is global; Tailwind/CSS modules
  belong at the build layer).
- WebSocket transport (SSE is the right primitive for our shape).
- Framework-owned durable chat / shared DB (Tier-3 pub/sub is
  live-only by design).
- Framework-owned application auth model — the framework owns the cid
  owner cookie, the host owns the principal via `:principal-fn`. See
  `docs/decisions/0004-app-store-and-principal.md`.

---

*End — todo.md.*

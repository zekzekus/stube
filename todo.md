# todo.md — stube, road to 1.0

The tier-1, tier-2, and tier-3 Seaside sweeps are done. The framework
boots, persists, schedules, publishes, uploads, and round-trips
hand-rolled conversations through EDN. What follows is the work between
"the demos run" and a 1.0 we'd hand to someone else.

Each item is labelled by its origin:

- **[bar §N]** — a §15 aesthetic / invariant target from `docs/v2_1.md`
  that the current implementation does not yet meet.
- **[carried]** — explicitly deferred in the tiered sweep
  (`docs/todo-tiers.md`); promoted here because the framework should not
  ship 1.0 with it still pending.
- **[shape]** — surfaces noticed while reviewing the codebase as it
  stands today (line counts, public-API surface, error reporting,
  deployment story). Not previously tracked.

Items are ordered by leverage within each group: the higher up, the more
downstream choices it unblocks or constraints it removes.

> Historical record. The granular per-tier task list that drove slices
> 0–4 and the Seaside sweep lives at `docs/todo-tiers.md`. Anything
> moved here is either still open or worth re-evaluating before 1.0.

---

## 1. The §15 bar — what the codebase needs to be true *as* code

These are not features; they're the non-functional bar the design
explicitly set. None of them is currently met.

- [ ] **Kernel ≤ 350 lines.** `src/dev/zeko/stube/kernel.clj` is **695
      lines** — almost double the budget. `invariant-test` currently
      passes only via the `:rationale` opt-out. The right move is to
      extract the parts that aren't kernel-shaped: effect interpreters
      (`:after`, `:subscribe`, `:upload-received` plumbing), the
      auto-render/diff bookkeeping that grew during slice 2, and any
      pure helpers reachable only from one effect. Goal: one
      multimethod, one `step`, one `run-effects`, one `dispatch`, under
      350 lines — and drop the rationale opt-out.
      [bar §15.4]

- [x] **Errors are local, surfaced in the browser.** Done in S-5:
      `dev.zeko.stube.errors` catches in `kernel/dispatch` and
      `frame/render-instance`, patches a `:stube-error` banner over the
      failing instance (with a `<!-- cid=… iid=… phase=… -->` comment),
      logs the same to stderr, and keeps the SSE stream open.  Optional
      `:on-error` override on `make-kernel` lets apps return a custom
      fragment.  Lifecycle hook throws are still in scope for a future
      pass.
      [bar §15.3]

- [ ] **Public surface freeze for 1.0.** `dev.zeko.stube.core` is the
      documented stable namespace, but `stube.server`, `stube.render`,
      `stube.ui`, and `stube.store` are re-exported piecemeal and used
      directly by examples in places. Decide what is API and what is
      internal, mark the rest `^:no-doc` / `:internal`, and add a
      doc-test that fails if an example reaches past `core` /`flow`
      /`ui`.
      [shape]

---

## 2. Composition & flow primitives still open

Carried-forward items from the tier sweeps. None has had a concrete use
case yet; each is a known shape we punted on. Treat them as **design
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
      limits. Pick one of (a) document the working subset and reject
      the rest at macro-expansion with a clear error, (b) compile a
      shape that lifts the catch into the resume handler so the user
      writes ordinary try/catch.
      [carried §13 slice 1] [v2 §16 q1]

- [ ] **Error answers `[:answer-error e]` + `:on-error` resume.** Today
      a child can only succeed-and-answer or stay alive. A
      cancellation or thrown-from-child path needs symmetric plumbing.
      Likely a small kernel addition once the error-surfacing story
      from §1 above is settled — both want the same "what does failure
      look like" answer.
      [carried §13 slice 1]

---

## 3. History & persistence — the cloroutine gap

`defflow` is the headline ergonomic of slice 1, but its conversations
do **not** persist. The file store skips them with a warning. This is
the largest single asterisk on "the data is the program" (§15.6).

- [ ] **Cloroutine continuation persistence.** Two designs to cost
      before committing:
      - (a) Custom `print-method` over the cloroutine state machine —
            cheapest, but couples us to cloroutine internals.
      - (b) Replay from recorded events on resume — keeps the
            EDN-clean invariant but doubles the surface area of
            "what's a conversation" (state + event log).
      Either way: pick before 1.0 or explicitly document `defflow` as
      in-memory-only and recommend hand-rolled tasks for crash-resume.
      [carried §6] [bar §15.6]

- [ ] **Browser back-button glue (`popstate`).** A one-liner in the
      shell HTML, but only useful with a matching `pushState` policy.
      The clean way: emit a `pushState` script fragment on every frame
      transition, wire `popstate` to POST `/conv/:cid/back`. Skipping
      it means in-page `s/back-button` remains the only path; that's
      fine until a demo demonstrably needs the browser button.
      [carried §6]

---

## 4. Application boundaries — what the framework declines to own

Tier 3 made several of these explicit (no shared DB, no auth principal,
no chat retention). They're correct, but a 1.0 still needs to *show*
the boundary cleanly enough that users don't reach across it.

- [ ] **Application app-store hook.** The `seaside_todo` port noted
      that a real shared database "is still outside the framework
      surface" and that examples keep the DB on the conversation to
      avoid side effects during dispatch. Document the pattern (an
      injected component, or a value on the conversation under a
      reserved key, or a host-app function passed through `start!`)
      and pick one for examples to standardise on.
      [shape, ref `seaside-examples.md` ToDo findings]

- [ ] **Non-shell HTTP routes for the same conversation.** The
      seaside_todo port also called out the Atom feed chapter
      (`/atomTasks`): `start!` only mounts component shells and the
      conversation endpoints. A way to declare extra routes alongside
      a mount — same handler signature, same access to conversation
      state — would close the only "literal Seaside chapter doesn't
      port" gap.
      [shape, ref `seaside-examples.md` Atom feed]

- [ ] **Application principal pass-through.** Slice 4 binds a cid to a
      `stube_sid` owner cookie; that is framework ownership, not app
      auth. `protected_counter.clj` shows app login as conversation
      state. The missing piece is the documented boundary: where does
      the request's authenticated principal land on the conversation,
      and who's responsible for putting it there? A `:principal` field
      derived by a host-supplied function at conversation mint time is
      the smallest workable contract.
      [shape, ref §16 carry-over from `v2_1.md`]

---

## 5. Transport & deployment shape

The kernel is transport-agnostic by design (`v2_1.md` §9.4). The HTTP
layer is the only Datastar-specific file. None of this has been
exercised by anything but our local dev server.

- [ ] **Reverse-proxy / SSE-timeout playbook.** http-kit + SSE is
      vulnerable to common proxy idle timeouts (nginx default 60s, ALB
      default 60s). A periodic keep-alive comment frame and a documented
      Nginx/Caddy/ALB snippet is enough to make "ship it to prod" not a
      surprise.
      [shape]

- [x] **Graceful shutdown.** Done in S-6: `runtime/halt!` (called from
      `s/stop!`) freezes new mints with 503, cancels scheduled events,
      runs `:stop` for every live instance children-first, drains open
      SSE streams with a final `:close` fragment, and flushes the
      store before clearing registries.  See the "Shutdown sequence"
      section of `docs/internals.md` for the full ordering.
      [shape]

- [ ] **Datastar version pinning.** The shell links a CDN bundle by
      URL. Decide between (a) pinning a known-good version constant in
      `core`, (b) vendoring the bundle into `resources/`. Either is
      fine; the current "latest on CDN" gives nondeterministic
      behaviour at install time.
      [shape]

- [ ] **Cross-process pub/sub.** `(s/publish! topic msg)` is an
      in-process delivery loop. Document the boundary (single-JVM only)
      and sketch the shape a Redis/Postgres `LISTEN` adapter would
      take — likely a small `Publisher` protocol the server can be
      handed. Don't implement until a user asks; do document the
      limitation.
      [shape]

---

## 6. Documentation & onboarding

The README is enough to get the example browser running. It is not
enough to *write* a component without reading the source.

- [ ] **Public-API reference doc.** Generated from the `core` /
      `flow` / `ui` namespaces' docstrings. The docstrings are already
      good; what's missing is the rendered, navigable surface.
      [shape, supports bar §15.4 "fits in your head"]

- [ ] **"Build a small app" tutorial.** The example browser shows
      what the framework can do; nothing yet walks a reader through
      *making* a stube app. Target audience: a Clojure developer who
      has never used Seaside. ~30 minutes from `git clone` to a
      working two-screen flow. Reuse the wizard / todo demos, don't
      invent new fiction.
      [shape]

- [ ] **Decision log per design question.** `v2_1.md` §14 already
      lists open-questions-now-resolved. A `docs/decisions/` folder
      capturing one short ADR per significant call (resume key naming,
      EDN-clean state, embed-vs-call boundary, principal model) means
      future contributors don't have to re-derive the noun set in
      §17.
      [shape]

---

## 7. Test & invariant coverage

- [ ] **Property-style coverage of the kernel fold.** `kernel_test`
      covers the happy paths. A generative test that produces random
      effect sequences and asserts (a) kernel never throws, (b)
      conversation round-trips through `pr-str`/`read-string` at every
      step (excluding `defflow` until §3), (c) every fragment carries
      a target that exists in the prior emitted HTML — would catch the
      whole class of "embedding edge case" bugs before they become
      example workarounds.
      [shape, supports bar §15.6]

- [ ] **`stube.invariant-test` should fail when an example sneaks
      something past the bar.** The current rules (no `<script>` in
      examples, `defcomponent` only at top level, kernel-size budget)
      are good. Add: every example's mid-flow conversation
      round-trips through EDN; every public-facing example uses only
      `s/...` calls (no reaching into `kernel/` / `server/` /
      `conversation/`).
      [shape, supports §1 above]

---

## 8. Deliberately *not* on this list

Carried forward from `v2_1.md` §16 — kept here so we don't add them by
accident:

- Time-travel UI (history exists; browsing it is an app).
- Server-side optimistic updates (Datastar does them client-side).
- First-class streaming flows (`:io` + `:patch` already covers it).
- Per-component CSS scoping (Hiccup is global; Tailwind/CSS modules
  belong at the build layer).
- WebSocket transport (SSE is the right primitive for our shape).
- Framework-owned durable chat / shared DB (Tier-3 pub/sub is
  live-only by design).
- Framework-owned application auth model (cid owner cookie is the
  only thing the framework promises).

---

*End — todo.md.*

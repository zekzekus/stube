# todo.md — stube, post-0.1.1

Tracking what's left. The road-to-1.0 sweep that landed in 0.1.1 cleared
most of the leverage items; what's here is the small set of things that
genuinely remain. Older history — the full 1.0 punch list, the tiered
sweeps, the resolved items — lives at `docs/archived_todo.md`.

Three tiers:

- **Correctness** — actual bugs the framework has today. These should
  be fixed before tagging 1.0.
- **Deferred spikes** — design seams we deliberately punted on. No
  concrete use case yet; build the smallest example that *needs* one
  before adding the primitive.
- **Won't do** — open shapes someone could ask for, but the framework
  already has a documented way to solve the same problem. Listed so we
  don't get talked into adding them by accident.

---

## 1 · Correctness

- [ ] **Fix the `:call-in-slot` previous-chain leak.**
      `:call-in-slot` parks the previous slot occupant on
      `:instance/previous` so the answer path can restore it. When the
      parent frame is replaced or ended *before* the slot child has
      answered, that previous-pointer chain becomes garbage:
      `:conv/instances` keeps every leaf in the chain, each with
      `:instance/parent` pointing at a removed iid. Surfaced by
      `kernel_property_test`; named and bounded by a comment in that
      file so the property test still catches everything else.
      The fix: walk previous-chains in `conv/pop-top` and
      `conv/remove-subtree`, sweep their iids out of `:conv/instances`,
      and run their `:stop` hooks during the sweep (they were never
      stopped — call-in-slot does not stop the displaced child, it
      preserves it). Once landed, relax the property test back to
      asserting that *every* `:instance/parent` points at a live iid.
      [surfaced 0.1.1, blocks 1.0]

---

## 2 · Deferred design spikes — wait for a real use case

Each of these is a known shape we've thought about and chosen not to
build. Don't build any of them without an example that demonstrably
needs the primitive — speculative API is the largest source of
framework cruft.

- [ ] **`[:notify-parent k value]`.** A child pushing data to its
      parent without unmounting. Today the only child→parent channel
      is `:answer`, which pops. Three example workloads have come and
      gone (chat, shared-counter, paginated-list) without anyone
      reaching for it; pub/sub or `render-slot` always covered the
      need. If a real demo wants it, the kernel addition is small —
      one new effect that looks up the parent through `:instance/parent`
      and routes the value through a named resume key without
      touching the call stack.
      [carried §2]

- [ ] **`:rebuild-children` effect for lazy / conditional slots.**
      `:children` materialises eagerly at instantiation. A slot whose
      embed-spec needs to change in response to later state currently
      forces `:call-in-slot`, which is the right primitive for
      "swap one child" but not for "structurally rebuild this whole
      sub-tree." `keyed-children` covers ordered collections; this is
      the gap for "the shape of the tree depends on conversation
      state at runtime."
      [carried §2]

- [ ] **`[:answer-error e]` + `:on-error` resume.** The error-frame
      system from S-5 catches `:render` / `:handle` throws and surfaces
      a banner in place of the failing instance — that handles
      *intra-component* failure. What's missing is the symmetric
      child→parent failure path: a way for a child to terminate
      abnormally and route the cause through the parent's component
      map under an `:on-error` resume key, mirroring how `:answer`
      routes a successful value through the named resume key. Useful
      for cancellation and for "the user closed the modal" — both
      currently force a sentinel-value convention on `:answer`.
      [carried §13 slice 1]

---

## 3 · Won't do (we have a documented alternative)

These come up periodically. Each has a working path today; don't add
the framework feature unless the documented alternative proves
insufficient under real load.

- **`try` / `catch` across `s/await` in `defflow`.** Cloroutine
  restricts forms across yield points, and we never spiked the exact
  limits. The documented alternative is to write the same shape as a
  hand-rolled task component (`:start` + named resume keys) and use
  ordinary `try`/`catch` between effect emissions; the tutorial shows
  the side-by-side. Since 0.1.1, `defflow` is explicitly the
  transient-flow ergonomic; if you need error recovery, you're already
  on the task-component path. Revisit only if cloroutine itself gains
  cross-yield exception support.

- **Browser back-button glue (`popstate`).** The supported in-page back
  primitive is `(s/back-button label)` plus the `[:back]` effect, which
  walks `:conv/history`. URL-bar back is a host concern: hosts that
  want it can emit `pushState` from their own shell and intercept
  `popstate` to POST `/conv/:cid/back`. Documented in the wizard
  example. Skip until a real deployment proves the host-side approach
  is too noisy.

- **Non-shell HTTP routes for the same conversation.** The
  seaside_todo port called out the Atom feed (`/atomTasks`) chapter:
  `start!` only mounts component shells and the conversation
  endpoints. Hosts can already declare their own Ring routes alongside
  the kernel's via `stube-ring/ring-routes` and read live conversation
  state through `(embed/conversation k cid)` — the embedded-Ring
  example proves this works end-to-end. No framework addition needed;
  the missing piece is just a small recipe in the docs. If a real port
  hits this, write the recipe.

---

## 4 · Deliberately not on this list

Carried forward from `v2_1.md` §16 — kept here so we don't add them by
accident:

- **Time-travel UI.** History exists on every conversation; browsing
  it is an app.
- **Server-side optimistic updates.** Datastar does them client-side;
  duplicating the work on the server fights the wire.
- **First-class streaming flows.** Runtime `:io` plus events and
  publishes can cover any streaming workload until a real one
  demands a primitive.
- **Per-component CSS scoping.** Hiccup is global by design;
  Tailwind, CSS modules, and the rest live at the build layer.
- **WebSocket transport.** SSE is the right primitive for our shape
  (server-driven, one-way patches, transparent HTTP semantics for
  proxies and auth).
- **Framework-owned durable chat / shared DB.** Pub/sub is in-process,
  single-JVM, live-only by design. Bring your own bus in `:app` if
  you need cross-process.
- **Framework-owned application auth model.** The framework owns
  the cid owner cookie; the host owns the principal via
  `:principal-fn`. See `docs/decisions/0004-app-store-and-principal.md`.

---

*End — todo.md.*

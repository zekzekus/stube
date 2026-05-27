# todo.md — stube, post-0.1.1

Tracking what's left. The road-to-1.0 sweep that landed in 0.1.1 cleared
most of the leverage items; what's here is the small set of things that
genuinely remain. Older history — the full 1.0 punch list, the tiered
sweeps, the resolved items — lives at `docs/archive/archived_todo.md`.

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

(No open items.  The `:call-in-slot` previous-chain leak surfaced
during the 0.1.1 sweep landed shortly after: `conv/subtree-ids`
walks `:instance/previous` chains alongside `:instance/children`
and `:instance/keyed-slots`, and the destruction paths
(`pop-top`, `:replace`, `:end`, `answer-from-stack`, keyed-child
removal, halt) use it so previous-chain instances get their
`:stop` hooks and are swept from `:conv/instances`.  The narrow
`descendant-ids` survives for paths where the previous gets
restored — `answer-from-slot`, `mark-rendered`, and wakeup —
because restoring an instance is not the same as destroying its
ancestors.  Pinned by `embed-test/replacing-parent-sweeps-call-in-slot-previous-chain`
and by the strengthened structural assertions in
`kernel-property-test`.)

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

- [ ] **Signal naming / binding / lookup toolkit.** First flagged in
      the kasten post-migration notes (`kasten/stube_notes.md`).
      Datastar's `data-bind:<key>` camel-cases the wire key, which
      collides with kebab-case Clojure conventions: emitting
      `data-bind:editMarkdown` reaches the browser as
      `data-bind:editmarkdown`, so updates land on the wrong signal.
      Today `s/bind` works around it with `__case.kebab`, but apps
      that *also* need JS-identifier refs in inline expressions and a
      matching server-side lookup convention end up re-inventing a
      small registry (kasten kept one). The sketch is roughly:

      ```clojure
      (s/data-signals {:edit-title "Title" :edit-markdown "Body"})
      (s/bind :edit-markdown {:wire-case :camel}) ; data-bind:edit-markdown
      (s/$ :edit-markdown)                        ; "$editMarkdown"
      (s/signal event :edit-markdown)             ; read from event signals
      (s/scoped-signal :save-submitting note-id)  ; safe indicator name+ref
      ```

      Don't build until a second host hits the same shape — the
      design space (wire-case as a global vs per-binding, how
      scoping interacts with `local-signal`, whether `$` should be a
      function or a tagged literal) is wide enough that one
      datapoint is not enough. When a second example needs it, the
      kasten signals namespace is a faithful sketch to adapt.
      [from kasten post-migration notes, post-0.1.7]

- [x] **`[:answer-error e]` + `:on-error` resume.** Shipped under S-14
      (issue #25) for the 0.1.3 / round-2 kasten-migration sweep.
      `(s/answer-error ex)` pops the child frame and routes the
      exception through the parent's `:on-error-<key>` resume; the
      kernel falls back to `:on-<key>` with `[:error ex]` (+ one
      deprecation log line) and, failing that, to the default error
      banner. See `docs/decisions/0005-answer-error-and-resume.md`.

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

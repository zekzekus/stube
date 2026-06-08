# todo.md — stube, post-0.1.1

Tracking what's left. The road-to-1.0 sweep that landed in 0.1.1 cleared
most of the leverage items; what's here is the small set of things that
genuinely remain. Older history — the full 1.0 punch list, the tiered
sweeps, the resolved items — lives at `docs/archive/archived_todo.md`.

Tiers:

- **Correctness** — actual bugs the framework has today. These should
  be fixed before tagging 1.0.
- **Security hardening** — active work to move from "personal research
  project" to "credibly secure for third-party adoption." Assessment
  and the three-release route live in `docs/security_draft.md`; the
  shipped contract is `docs/security.md`.
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

## 2 · Security hardening (road to credibly-secure)

Active work. Full assessment in `docs/security_draft.md`; the
current contract (what's enforced vs what's still a gap) is
`docs/security.md`. As each item ships, move its row from the
"current gaps" table in `security.md` into "what the framework
enforces today," and tick it here.

Sequencing: **Phase 0** (the doc) is done. **Phase 1** is all
framework-internal, no client-contract change, each item shippable on
its own — target a 0.9.0. **Phase 2/3** change the client contract or
add operator seams — target a 0.10.0. **Phase 4** is parked until a
multi-tenant host exists (see §5, "deliberately not on this list").

- [x] **Phase 0 — `docs/security.md`.** The shared-responsibility
      contract, threat model, host-config checklist, and author rules.
      Written before the code so each Phase 1 fix is "the thing the doc
      would otherwise apologise for."

### Phase 1 — cheap, high-severity, no client cooperation

- [x] **Cid entropy.** `conversation.clj/new-cid` now mints `cv-` +
      128 bits of `SecureRandom`, hex-encoded (32 hex chars). Chose hex
      over base32 so the `file-store` "cids are `[0-9a-f]` + `cv-`"
      invariant stays literally true with no new alphabet or
      dependency. Instance ids stay counter-based — only reachable
      under an already-authorized cid, so not an enumeration target.
      Pinned by `conversation-test/new-cid-is-unguessable-and-well-formed`.
- [x] **Bounded parsing — size.** `:max-signals-bytes` (64 KiB) and
      `:max-payload-bytes` (4 KiB) on `make-kernel`. `http.clj` reads
      the signals stream through a one-past-the-cap `slurp-capped`
      (never buffers the whole body) → `413`; the EDN payload param is
      size-bounded (which also bounds nesting depth) → `413` oversize /
      `400` unparseable. Pinned by `http-test/event-bounds-request-parsing`.
- [x] **Bounded parsing — keyword interning.** `parse-json` and
      `event-handler` now resolve untrusted keys with `find-keyword`
      (returns the keyword only if already interned, else a string /
      no-op) instead of `keyword`. Chose `find-keyword` over an
      LRU-capped intern because every key a component actually uses is
      a keyword literal — already interned — so legitimate traffic is
      unaffected and the keyword table can't grow from forged input.
      `merge-kept-signals` and `s/signal` now probe both the keyword
      and the string wire form, which is what makes the camel
      first-dispatch case keep working. Pinned by
      `conversation-test/merge-kept-signals-accepts-string-keys` and
      `http-test/event-handler-bounds-keyword-interning`.
- [x] **Secure cookie + knob.** `stube_sid` is `Secure` by default
      (plus the existing `HttpOnly` + `SameSite=Lax`). New `make-kernel`
      opts `:dev-cookie?` (opt out of `Secure` for plain-HTTP localhost)
      and `:cookie-domain` / `:cookie-path`. The default
      `:ensure-session-fn` is a closure over the resolved cookie
      attributes; host-managed sessions (`:session-id-fn`) keep control.
      Standalone `s/start!` defaults `:dev-cookie? true` (binds plain
      HTTP), keeping the e2e harness and localhost dev working. Pinned
      by `session-test`.
- [x] **Multipart caps + tempfile cleanup.** Ring's multipart
      middleware doesn't expose a byte cap, so `upload-handler` gates on
      `Content-Length` against `:max-upload-bytes` (default 10 MiB) →
      `413` before parsing, and a `try`/`finally` deletes ring's
      tempfiles after the synchronous dispatch consumes them.
      `:keep-upload? true` opts out for async file handoff. Pinned by
      `http-test/upload-handler-reclaims-tempfiles-by-default`,
      `-keeps-tempfiles-when-opted-in`, and `-rejects-oversize-body`.

### Phase 2 — CSRF token (changes the client contract)

- [x] **Per-conversation CSRF nonce.** `mint-conversation!` mints
      `:conv/csrf-token`; the shell stamps it on its root as
      `data-stube-csrf` (not a `<meta>` — that way it rides both the
      standalone `shell/html` and the embedded `shell-for` fragment).
      The behaviors bridge echoes it back via two transports, because
      not every request is a fetch: the `X-Stube-Csrf` header on
      Datastar `@post` (`event`/`back`, via a `fetch` wrapper) and a
      hidden `_stube_csrf` field on the zero-JS multipart upload form
      (which can't set a header). Handlers `403` a mismatch; the upload
      payload strips the field so it never reaches component state.
      `create-conversation!` (compat) mints no token. Validated by unit
      tests *and* the browser e2e harness (multicounter, guess,
      protected-counter, dialogs all green). The bridge stays on the
      web-platform `fetch`/`submit` seams — no Datastar internals.

### Phase 3 — operator seams and headers (hooks + docs)

- [ ] **Security event hooks** on the kernel — `:on-auth-fail`,
      `:on-stale`, `:on-shell-mint` — beside the existing
      `:on-conv-mint` / `:on-error`. Replace the `println` paths in
      `runtime.clj` / `store.clj` with a configurable logger fn.
- [ ] **`:before-dispatch` seam** —
      `(fn [conv event request] -> :continue | [:reject status body])`
      in `event-handler` / `back-handler` for host authz / rate-limit /
      audit.
- [ ] **`embed/rotate-session!`** — mint a new `stube_sid`, update
      `:conv/owner-token`, return a `Set-Cookie` the host attaches.
      Documented as "call on login/logout"; replaces the current
      end-and-remint workaround.
- [ ] **`stube.security/wrap-defaults`** — Ring middleware adding
      `X-Content-Type-Options`, `Referrer-Policy`, COOP,
      `X-Frame-Options` / `frame-ancestors`, `Permissions-Policy`.
- [ ] **CSP recipe** — a documented, Datastar-compatible baseline using
      a nonce for the shell's inline `data-init` and Datastar's inline
      `data-on:*` attributes, so the page can drop `unsafe-inline`.
      Verify against the upstream Datastar version pinned in nixpkgs.

### Phase 4 — multi-tenant / untrusted-component (parked)

Do **not** build without a concrete second host that needs it; consistent
with the speculative-API discipline in §3. Sketched in
`security_draft.md` "Stretch": per-kernel registry (drop the global atom
in `registry.clj`), topic ACLs in pub/sub, injectable `Executor` caps
for `:io` / `:after`, signed (HMAC) conversation cookies.

---

## 3 · Deferred design spikes — wait for a real use case

Each of these is a known shape we've thought about and chosen not to
build. Don't build any of them without an example that demonstrably
needs the primitive — speculative API is the largest source of
framework cruft.

- [ ] **`:rebuild-children` effect for lazy / conditional slots.**
      `:children` materialises eagerly at instantiation. A slot whose
      embed-spec needs to change in response to later state currently
      forces `:call-in-slot`, which is the right primitive for
      "swap one child" but not for "structurally rebuild this whole
      sub-tree." `keyed-children` covers ordered collections; this is
      the gap for "the shape of the tree depends on conversation
      state at runtime."
      [carried §2]

      Kasten evaluated (2026-06) and does **not** demonstrate this gap:
      its reading stack is homogeneous (`keyed-children`), its overlays
      are transient call/answer (`call-in-slot`), the topbar "swap" is
      separate siblings toggled by flags, and the about page is a
      distinct component id. The missing shape — a long-lived named slot
      whose component *type* changes in place while siblings keep local
      state — never arises (kasten has one note kind). Stay parked until
      a host shows a heterogeneous, state-bearing detail slot that is
      neither a keyed collection nor a call-in-slot overlay.

- [x] **`[:answer-error e]` + `:on-error` resume.** Shipped under S-14
      (issue #25) for the 0.1.3 / round-2 kasten-migration sweep.
      `(s/answer-error ex)` pops the child frame and routes the
      exception through the parent's `:on-error-<key>` resume; the
      kernel falls back to `:on-<key>` with `[:error ex]` (+ one
      deprecation log line) and, failing that, to the default error
      banner. See `docs/decisions/0005-answer-error-and-resume.md`.

---

## 4 · Won't do (we have a documented alternative)

These come up periodically. Each has a working path today; don't add
the framework feature unless the documented alternative proves
insufficient under real load.

- **Signal-name registry (component-level `:signals` declaration).**
  The casing problem itself landed earlier (kernel-level `:signal-case`
  plus `s/$` / `s/signal` / `s/signal-wire-name` helpers — see
  `kasten/stube_notes.md §3`), and the per-element seed/bind/indicator
  helpers are all shipped: `s/signals` / `s/local-signals` (seed),
  `s/bind` / `s/local-bind`, and the indicator twins `s/indicator`
  (page-global) + `s/local-indicator` (per-instance). What stays parked
  is only the broader *registry*: a single component-level declaration
  of every signal it owns, with init defaults and auto-`:keep`. That's
  an app-architecture pattern, not a framework one — apps that want it
  can keep their own map. Kasten — the only host that built such a
  registry — is now shrinking it, so the second datapoint is moving
  away, not closer. Revisit only if a second host independently
  re-invents the registry shape under real load.

- **`[:notify-parent k value]` — child→parent push without unmounting.**
  Resolved: the framework already covers it. `s/dispatch-to (:instance/parent
  self) [k value]` delivers a payload to the parent's `:handle` without
  popping the child, and kasten uses exactly this at 6+ sites
  (`search`→`:open-from-search`, `note-column`/`ledger`→`:delete` /
  `:open-create` / refresh). For one→many there's `s/publish-local!`; for
  click-driven controls there's `s/on-parent`. The only thing a dedicated
  effect would add is routing to a *resume key* rather than `:handle`, and
  no host has wanted that. The lone residual friction — threading
  `(:instance/parent self)` by hand through those `dispatch-to` calls —
  was closed by the `s/dispatch-to-parent` sugar (mirrors `s/on-parent`).
  Don't add `notify-parent`.

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

## 5 · Deliberately not on this list

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

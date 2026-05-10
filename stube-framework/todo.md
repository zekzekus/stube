# todo.md — stube framework, work outstanding

A grouped task list for everything we want true at 1.0. Items are
labelled by where they came from:

- **[design]** — open question or roadmap entry from `v2.md` / `v2_1.md`.
- **[ex:<file>]** — pain-point or workaround flagged in an example
  under `examples/stube/examples/`, or in `seaside-examples.md`.
- **[bar]** — non-functional aesthetic / DX target from §15 of v2.1.

Within each group items are ordered roughly by leverage: the higher up,
the more downstream code gets simpler the moment it lands.

---

## 1. Event & routing ergonomics

The single biggest source of repeated workaround code in the examples.
Every `:click :as` site that needs to carry "which thing was clicked"
currently hand-encodes the id into a keyword, then parses it back.

- [x] **Structured event payloads.**
  Allow `(s/on self :click :as [:pick-day day])` and have the kernel
  route `{:event :pick-day :payload day}` (or a multi-arity handler
  arm) into `:handle`.
  Eliminates: `(evt :toggle id)` + `parse-evt` in `todo.clj`,
  `:pick-<i>` in `dialogs.clj` `:ui/choose`, all the per-cell route
  names in `calendar.clj`, and ten `case` arms in `calc.clj` (one per
  digit).
  [ex:calc, ex:calendar, ex:dialogs, ex:todo] [design v2.1 §14]

- [x] **Per-instance signal scoping.**
  Two embedded `:ui/prompt`s on the same page both want `$answer`; today
  `dialogs.clj`, `todo.clj` and `wizard.clj` each compute
  `(keyword (str "prompt-" (:instance/id self)))` by hand. Pick one of:
  - (a) auto-namespace into `$cmp.<iid>.<name>` at render time;
  - (b) ship `(s/local-bind self :answer)` that does the same thing
        explicitly at the call site.
  Chose the explicit `(s/local-bind self :answer)` / `(s/local-signal
  self :answer)` path so scoping is visible at the call site while
  `:keep #{:answer}` still reads cleanly in handlers.
  [ex:dialogs, ex:todo, ex:wizard] [design v2.1 §14 q v2.3]

- [x] **`(s/on self :submit)` defaulting.**
  Today every form passes `:as :submit` explicitly. When `:as` is
  omitted, default to the DOM event name (`:submit`, `:click`, …).
  Trivial; collapses two-thirds of the `(s/on …)` calls in the
  examples to a single argument.
  [ex:dialogs, ex:todo]

---

## 2. Composition primitives

These move us from "everything pushes the whole stack" to faithful
Seaside-style local composition.

- [x] **`[:call-in-slot slot embed-spec :resume k]`.**
  Embedded call/answer that swaps a single child slot rather than the
  whole top frame. The single highest-leverage missing primitive this
  catalogue surfaced — without it `todo.clj` cannot do real in-place
  editing (it inlines the editor as parent state instead).
  Touches: kernel `step`, `instantiate`, `render-frame`, and the
  `:rendered?` bookkeeping that decides between morph-by-id and
  `#root inner`.
  Implemented as a slot-local overlay: the temporary child remembers
  the previous occupant and restores it when it answers, while the
  parent receives the answer under the named resume key.
  The todo example now uses this for its row-local editor.
  [ex:todo (former workaround documented in file)] [design v2.1 §11
  carried-forward]

- [ ] **`[:notify-parent k value]`.**
  Children that want to push something at their parent without
  unmounting (today `:answer` is the only mechanism, and it pops the
  child). Strong candidate the moment a real use case arrives — flag
  but don't build until then.
  Deferred deliberately; no current example needs non-unmounting child
  notification, and building it now would add a second return channel.
  [design v2.1 §13 slice 2 carried-forward]

- [ ] **`:rebuild-children` effect for lazy / conditional slots.**
  Today `:children` is materialised eagerly at instantiation. Slots
  whose embed-spec depends on later state need a kernel-level rebuild.
  Deferred until a dynamic-slot example drives the exact semantics;
  `:call-in-slot` covers the current local composition gap without
  rematerialising child state.
  [design v2.1 §13 slice 2 carried-forward]

- [x] **`s/decorate` demo.**
  No example currently exercises decorations end-to-end; the unit
  tests do. Add a tiny `with-banner` wrap in `wizard.clj` (or a new
  `breadcrumb.clj`) so the README can point at a real call site.
  [design v2.1 §13 slice 2] [ex:seaside-examples Tier 2 `WAPath`]

---

## 3. Convenience helpers (graduate from examples → `stube.core`)

If a helper appears in two examples, it belongs in core. These all
already exist in user code and are mechanically trivial.

- [x] **`(s/confirm question)`, `(s/prompt label default)`,
      `(s/choose options caption)`, `(s/info text)`.**
  Wrappers over `s/embed` plus the canonical `:ui/confirm` /
  `:ui/prompt` / `:ui/choose` / `:ui/info` components. Ship the
  components in a `stube.ui` namespace and the verbs in `stube.core`.
  Lets a `defflow` body read like Seaside's
  `self confirm: 'Ready?'`.
  Stock components live in `stube.ui`; `dialogs.clj` now uses only the
  verbs from `stube.core`.
  [ex:dialogs (DX note), ex:wizard]

- [x] **`(s/back-button label)` helper.**
  Renders the canonical `<button>` wired to `s/back`. Every example
  with a wizard re-rolls this.
  Implemented as a conversation-level helper posting to `/conv/:cid/back`;
  wizard-local Back buttons still use component events when they need to
  preserve in-flow state.
  [ex:wizard] [design v2.1 §13 slice 3]

- [x] **Default styles / `stube.ui` mini stylesheet.**
  Pick *one* opinionated CSS file (literally a `<link>` in the shell
  the user can opt out of) so demo code stops carrying inline
  `:style "padding:0.4rem 1rem; …"` strings on every button. Matches
  `[bar]` §15.4 — "the whole library fits in your head" — and §15.5 —
  "zero client code".
  The shell links `/stube/ui.css` by default; pass `:ui-css? false` to
  `s/start!` to opt out.
  [ex:calc, ex:dialogs, ex:todo (every example)]

---

## 4. Lifecycle, registry & component metadata

Small but visible holes in the data model.

- [x] **`:stop` lifecycle hook.**
  Mirror of `:start`; runs once when the frame is popped. Needed for
  cleaning up resources held by `:io` callbacks (subscriptions,
  timers, file handles).
  Runs for stack frames and slot-local children before removal; hook
  effects are folded before the removal's visible fragments.
  [design v2.1 §14 q v2.5]

- [x] **`:wakeup` lifecycle hook.**
  Runs when a frame is restored from history (via `:back` or
  crash-resume). Lets a component re-acquire transient resources that
  weren't (and shouldn't be) persisted.
  Runs for the restored top frame before render on `:back` and SSE
  reattach.
  [design v2.1 §14 q v2.5, §13 slice 3]

- [x] **Docstring slot + `(s/help :auth/login)`.**
  Add `:doc` to `defcomponent`; surface it via `(s/help id)` so
  component libraries are self-documenting. Trivial; matches §15.1.
  [design v2.1 §13 slice 5]

- [x] **Hot-reload safety.**
  Re-evaluating a `defcomponent` should not crash live conversations
  whose instances are of that type. Decide and document: do existing
  live frames pick up the new `:render` next time their handler fires,
  or only new frames?
  Existing live frames pick up the latest registered definition on the
  next render/dispatch; this is now pinned by regression coverage.
  [design v2.1 §13 slice 5]

- [x] **Stale-instance soft 410.**
  Today a POST whose iid is no longer on the stack throws. Patch a
  "page is stale, please reload" banner into `#root` and end the conv
  gracefully.
  The HTTP layer now returns 410, patches the stale banner when an SSE
  stream is present, closes it, and forgets the conversation.
  [design v2.1 §14 q v2.6]

---

## 5. Operations (slice 4 proper)

Already scoped in v2.1 §13 slice 4; listed here so nothing is lost.

- [x] **Reaper.** Background loop that ends conversations whose
      `:conv/touched` is older than a configurable TTL. Closes the
      "persisted convs live forever" gap from slice 3.
- [x] **`(s/active-conversations)`** and **`(s/end! cid)`** admin ops.
- [x] **Anti-forgery / session ownership.** A cid is a routing handle,
      not a capability. Session cookie + assertion on every POST.
- [x] **`slf4j` MDC by `cid` + `iid`.** A single `grep` should
      reconstruct the timeline of any one conversation.

---

## 6. History & navigation polish

`s/back` works; the loose ends are small.

- [ ] **Browser back-button glue.** One-liner in the shell HTML
      (`data-on-popstate__window` POSTing to `/conv/:cid/back`).
      Deferred until we have a clean cross-example pattern.
      Still deferred after the slice-4 sweep: without a matching
      `pushState` policy, a `popstate` handler alone is surprising and
      browser-global. In-page `(s/back-button ...)` remains the clean
      zero-JS path.
      [design v2.1 §13 slice 3 carried-forward]

- [ ] **Cloroutine continuation persistence.**
  `defflow` conversations are in-memory-only today; `file-store` skips
  them with a warning. Two paths worth costing: (a) a custom
  `print-method` that serialises the cloroutine state, (b) replay from
  recorded events on resume.
  Still deferred after the slice-4 sweep: hand-rolled task components
  remain EDN-clean and persistable, while opaque cloroutine state stays
  explicitly in-memory until a replay/checkpoint design preserves the
  "data is the program" invariant.
  [design v2.1 §13 slice 3 carried-forward]

---

## 7. Multi-user & async (drives a future slice)

These are the items that *should* drive whichever slice we pick after
slice 4. Listed in roughly increasing order of how much they push the
framework.

- [ ] **`[:after ms event]` timer effect.** Live clocks, debounced
      submits, polling fallbacks. (`WAClock` / `WATurboCounter`.)
      Deferred until the first async example chooses cancellation and
      ownership semantics; a timer must not outlive its conversation or
      surprise another tab for the same user.
      [ex:seaside-examples Tier 3]
- [ ] **`(s/publish! topic msg)` + per-conv subscription.** Server-push
      for "patch this conv from outside its handler". Required for
      `CTCounter`, `CTReport`, `CTChat` ports.
      Deferred until a multi-user example drives topic lifetime,
      backpressure, and authorization. The current SSE path stays
      single-conversation and handler-originated.
      [ex:seaside-examples Tier 3]
- [ ] **File upload.** Non-SSE multipart route plus an
      `[:upload-received]` hook routed to the active instance.
      Deferred until an upload example fixes the public API; adding a
      multipart side route now would widen the transport before the
      userspace shape is clear.
      [ex:seaside-examples Tier 3 `WAFileUploadExample`]
- [ ] **Session auth binding.** `WASessionProtectedCounter`-style;
      bind a conversation to an authenticated session.
      Deferred separately from slice-4 cookie ownership. The framework
      now prevents cross-session cid use; application-auth binding
      should compose with the host app's auth model, not invent one.
      [ex:seaside-examples Tier 3]
- [x] **Registry introspection.** `(s/mounts)` listing root mounts,
      so a `WANavigationBar`-style index page is two lines. Landed as
      part of the slice-4 admin surface.
      [ex:seaside-examples Tier 3]

---

## 8. Tier-2 demo backlog (no new primitives needed)

Adding any of these *first* would surface DX issues for the helpers
above before they harden.
After the sweep, the framework helpers they depend on are in place; keep
these as showcase/demo tasks and add them one at a time when we want
example coverage, not as justification for more runtime machinery.

- [ ] `paginated-list.clj` (`WABatchedList`) — render-callback init
      arg, pagination state.
- [ ] `table-report.clj` (`WATableReport`) — column config maps,
      click-to-sort.
- [ ] `tree.clj` (`WATree`) — per-node expansion set, recursive
      render.
- [ ] `breadcrumb.clj` (`WAPath` / `WATrail`) — first real
      `s/decorate` end-to-end demo.
- [ ] `example-browser.clj` (`WAExampleBrowser`) — dynamic component
      lookup + child swap; doubles as the demo landing page.

---

## 9. REPL & inspection (DX polish, slice 5)

- [x] **`(s/inspect cid)`** — pretty-print the live conversation
      (stack, instances, last event). Drops straight into the workflow
      we already use ad-hoc in tests.
      Prints and returns a compact live summary; dispatch records a
      sanitized last-event summary with signal keys, not signal values.
      [design v2.1 §13 slice 5]
- [x] **`(s/replay events)`** — reduce a conv from a baseline through
      a sequence of events. Useful both for tests and for "what if I
      had answered differently".
      Supports `(s/replay conv events)` and `(s/replay flow-id events)`;
      event maps default to the current top frame with empty signals.
      [design v2.1 §13 slice 5, bar §15.6]
- [x] **README: Datastar Inspector tip.** Single most useful debug
      tool; one paragraph in the running/debugging notes.
      [design v2.1 §13 slice 5]

---

## 10. Aesthetic & invariant guards (the §15 bar)

Not features — *checks* that the codebase still meets the bar at every
slice boundary. Worth a CI script before 1.0.

- [ ] **Kernel size invariant.** `stube/kernel.clj` ≤ 350 lines, one
      multimethod. Fail CI if it grows past the limit without an
      `:rationale` opt-out.
      [bar §15.4]
- [ ] **Zero-JS invariant.** Grep examples for `<script>` other than
      the Datastar bundle; fail CI if any example sneaks in custom JS.
      [bar §15.5]
- [ ] **EDN-clean conversations invariant.** A test that round-trips
      every example's mid-flow conversation through `pr-str` /
      `read-string` and asserts equality. (`defflow` continuations
      currently fail; tracked under §6.)
      [bar §15.6]
- [ ] **`defcomponent` reads like a record def.** Lint rule (or
      doc-test) that flags components doing anything but `def`-time
      registration at top level.
      [bar §15.1]

---

*End — todo.md.*

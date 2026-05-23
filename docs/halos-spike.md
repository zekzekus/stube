# halos-spike.md — Seaside halos as a stube dev tool

Spike: what Seaside halos are, how they map onto stube, the invasive
points, and an incremental build-up sequence with cost estimates.

---

## What Seaside halos actually are

In Pharo Seaside, a halo is a small overlay drawn around every rendered
`WAComponent` when "halos" is toggled on the dev toolbar. Each halo
carries a fixed set of icon buttons plus a class-name label. The
canonical icon set:

| icon | action | maps to stube? |
|---|---|---|
| Inspect | open Smalltalk inspector on the component instance | **yes** — show the instance map |
| Browse | open class browser on the component's class | **partial** — open the `defcomponent` form via file/line metadata |
| Halos | toggle halos on nested children (per-frame, not just global) | **yes** — UI toggle on the overlay |
| Configure | open the component's `#configuration` (Seaside configuration objects) | **no** — no analog; stube has no config object |
| Render source (XHTML) | show the literal rendered HTML for that component | **yes** — and trivial |
| Add/remove decoration | wrap/unwrap a Seaside decoration | **no** — stube composes via embed/call, not decorations |
| Class name strip | shows class, doubles as drag handle | **yes** — show `:instance/type` + iid |

Then there's the **dev toolbar** at the page bottom, which is separate
from per-component halos but ships together. It carries:

- New session
- Configure (session-level)
- Toggle halos
- **Memory** (browse the session's state + history — the time-travel UI)
- Profile (server-side profile of the last request)
- XHTML (full page render dump)

The Memory tool is the big one — it lets you click a point in session
history and the page snaps back. Stube already has `:conv/history`; this
is essentially exposing what's already in the kernel.

---

## What in stube makes this easier than Seaside-on-Smalltalk

You're well set up:

- Every instance has a stable `:instance/id`, and renders already use it
  as the outer DOM id (convention). That's the anchor the overlay needs.
- `:conv/instances` + `:conv/stack` + `:instance/children` already model
  the full tree.
- `:conv/history` already records snapshots — Memory tool is one
  endpoint away.
- The kernel is pure and a conversation is EDN-clean (well, except
  cloroutine flows — same blocker `todo.md §3` calls out).
- `render-frame` is the natural choke point to decorate output with halo
  metadata.
- `(s/inspect cid)` REPL helper already exists per README.

---

## What doesn't translate

- **Configure** — drop it. Stube components are `:init`/`:render`/`:handle`
  maps; there's no parallel object to mutate.
- **Add/remove decoration** — drop. Composition is
  `embed`/`call`/`call-in-slot`; "wrap with X" isn't a thing.
- **Profile button** — orthogonal to halos; could be added separately,
  but skip for v1.
- **In-browser inspector that edits state** — Smalltalk lets you mutate
  live; in Clojure that's an anti-feature. Inspect is read-only.

---

## Suggested incremental plan

Each tier ships something usable on its own. Gate the whole thing on
`(s/start! {:halos? true})` — never on in prod, never on by default.

### Tier 0 — REPL ergonomics (no browser surface) — ~½ day

What already exists (`s/inspect`) plus:

- `(s/tree cid)` — pretty tree of stack + slots, with `:instance/type`
  and iid.
- `(s/instance cid iid)` — returns the instance map (EDN-clean check
  baked in).
- `(s/conv-history cid)` — list snapshots with timestamps.
- `(s/where type-kw)` — source `:file`/`:line` captured by
  `defcomponent`.

Cheap, useful regardless of whether halos ship.

### Tier 1 — Passive overlay + labels — ~1 day

- `?halos=1` on the shell URL flips `:conv/halos? true`. Also: keyboard
  chord `?` once halos JS is loaded, to toggle.
- Add `data-stube-iid` and `data-stube-type` to every instance's root
  element during render. Cleanest spot: a tiny helper in `render-frame`
  that walks the top-level hiccup of an instance and merges the attrs.
  **Risk:** today there's no kernel-side guarantee the user's hiccup has
  its outer attr map at index 1 — examples follow the convention but
  it's not enforced. Worth tightening anyway.
- Ship a static `/stube/halos.js` (one file, no build step). On load:
  query `[data-stube-iid]`, draw a 1px dashed outline + a small tag in
  the corner showing type + iid. That's it for T1.

You stop here if you want — the label + outline alone is already a real
debug improvement.

### Tier 2 — Inspect panel + tree — ~2 days

- A side panel served by `/stube/halos/<cid>/panel` returning hiccup;
  rendered as an iframe or a fixed-position div in the shell.
- **Implement the panel itself as a stube component** in a sibling
  "control" conv (separate cid) so halo events don't pollute the app
  conv. Eats own dog food, costs ~50 LOC.
- Tabs: Tree / Instance / HTML / History.
  - **Tree** — hierarchical render of `:conv/instances` + stack. Click
    an iid → fills Instance + HTML tabs.
  - **Instance** — pretty-printed instance map. Use `clojure.pprint` or
    `puget`.
  - **HTML** — last rendered fragment for that iid (cache last emitted
    `:elements` per iid in the conv — ~30 LOC in `run-effects`).
  - **History** — list of snapshots, length, timestamp. No actions yet.
- Click a halo label in the page → focuses the side panel on that iid.

### Tier 3 — Memory / time travel — ~1 day, but touches kernel

- "Restore snapshot N" button in the History tab →
  `(server/swap-conv! cid (fn [_] (nth (:conv/history c) n)))` then
  re-emit a frame.
- Caveat: any conv carrying a cloroutine flow can't round-trip — same
  gap `todo.md §3` describes. Either disable Memory for those convs or
  label "in-memory only" in the panel.

### Tier 4 — Event log + replay — ~1 day

- Ring buffer (~256 events) in the conv:
  `{:t ts :iid … :event … :payload …}` appended in `dispatch!`.
- New "Events" tab. "Replay" button on an entry → re-fire the same
  event into the current state.
- Possibly: a Pause toggle that holds dispatch in a queue until you
  Step. Real value, but it interleaves with the SSE loop and async
  effects — defer until T1–T3 are used and the need is clear.

### Tier 5 — Editor jump — ~½ day

- "Open" button on each halo → an `editor://` link built from
  `:file`/`:line` (Emacsclient, `vscode://`, `idea://`). User configures
  their preferred handler. Trivial once T0's `where` exists.

---

## Where it gets invasive

Three pinch points worth flagging now:

1. **Instance-root attribute injection.** Adding `data-stube-*` to every
   instance's outer element is the foundation for everything else.
   Today render functions return arbitrary hiccup; the kernel doesn't
   enforce that index-1 is an attr map. T1 needs either a normaliser in
   `render-frame` (small but the kernel is already over budget per
   `todo.md §1`) or an explicit `(s/root-attrs self)` helper users must
   include. Prefer the normaliser, **and** extract it to a
   `dev.zeko.stube.halos` namespace so the kernel-LOC budget doesn't
   regress further.

2. **A second conversation for the panel.** Trying to mount halo UI
   inside the app conv creates ordering problems (halo dispatches
   snapshotting into the app's `:conv/history`, halo instances mixing
   into `:conv/stack`). A sibling conv keyed by the app cid is cleaner;
   costs an extra `register-control-conversation!` on the server side.

3. **Production gate.** `:halos?` must be opt-in at `start!` time, the
   static `halos.js` must 404 when off, and the server option should be
   checked at every endpoint, not just the shell. Belt and suspenders,
   because halos expose full session state.

---

## Overall verdict

Not hard. T0 + T1 is ~1.5 days and gets you 70% of the practical value
(labels, tree, REPL inspect). T2 gets you the inspector pane — which is
the thing you'll actually click. T3 (Memory) is the most Seaside-feeling
piece and the one cloroutine persistence (`todo.md §3`) gates. T4 – T5
are niceties.

If sequencing this on top of `todo.md`, slot T0 – T2 as a new
`§9 Developer tooling` section, done **before** the kernel-shrink work
in `§1`, because T1's normaliser is going to be one of the things
extracted from the kernel anyway, and it's cleaner to extract it once
with a known consumer than twice.

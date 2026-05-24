# stube documentation

stube is a personal research project that combines a Seaside-style
call/answer component model with Datastar over the wire. These guides
cover what it is, why it exists, and how to use it.

User-facing guides:

- **[Rationale](rationale.md)** — the personal story: UCW, Seaside,
  core-server, React's rehabilitation of host-language HTML, Datastar
  as the missing wire, and how this framework finally came together.
- **[Tutorial](tutorial.md)** — build a live collaborative todo list
  step by step. Start here if you've never used stube before.
- **[API reference](api.md)** — every public function in
  `dev.zeko.stube.core`, organised by what you're trying to do.
- **[Internals](internals.md)** — how the kernel, the conversation
  and the effect language fit together.
- **[Decisions](decisions/)** — short ADRs for the design calls that
  shaped the framework (resume-key naming, EDN-clean state,
  embed-vs-call, app-store and principal).
- **[Changelog](../CHANGELOG.md)** — big-rock framework changes by
  release/development pass.

Design notes (historical; useful context, not normative API docs):

- **[v2.md](v2.md)** — the original design document.
- **[v2_1.md](v2_1.md)** — the revised plan that drove the slices,
  with §0 covering the five Datastar facts the implementation had
  to discover the hard way.
- **[seaside-examples.md](seaside-examples.md)** — Seaside's
  canonical examples translated into stube.
- **[halos-spike.md](halos-spike.md)** — the dev overlay design.
- **[todo-tiers.md](todo-tiers.md)** — slice planning notes.

# 0003 — `:children` materialises eagerly; `:call` pushes a stack frame

## Context

A component needs two distinct ways to compose with other components:

1. **Structural composition.** "This component contains a left
   sidebar and a main panel, both of which are independent components
   with their own state." These children co-exist for as long as the
   parent does; they don't return values.
2. **Sequential composition.** "Ask the user a question via a child
   component; resume the parent with the answer." The child is
   temporary; it pops when it answers.

Conflating the two would force callers to express "always-on
sub-component" as some weird "calls itself forever" pattern, or
to wedge return values into structural children.

## Decision

Two distinct primitives:

- **`:children`** on a component definition declares the structural
  set. `:children {:slot/left (s/embed :sidebar) :slot/main
  (s/embed :panel)}` instantiates both children eagerly at the
  parent's own instantiation time. They live in `:conv/instances`
  alongside the parent, addressed through `:instance/children`. They
  do not appear on `:conv/stack`.
- **`:call`** is an effect: `(s/call :child/picker {} :on-picked)`.
  It pushes a new frame onto `:conv/stack`. The child's HTML
  replaces the parent's; on `:answer`, the frame pops and the parent
  resumes via the named key.

## Consequences

- **Two mental models, clearly separated.** Authors don't have to
  decide whether a child is "structural or sequential" through some
  awkward shared API — the primitives are different shapes from the
  start.
- **`:children` can't take return values.** That's the point. If you
  need an answer, that's a `:call` site, not a child slot.
- **`:call-in-slot` covers the hybrid case.** Sometimes you want a
  sequential exchange that doesn't take over the page — a confirm
  modal inside a panel, an inline editor on one cell. `:call-in-slot
  <slot> <embed> :on-answer` temporarily swaps the child in a known
  slot; the answer returns to the parent without popping the page.
- **Keyed children get a third primitive.** Ordered, dynamic
  collections (a sortable list of cards) use `:set-keyed-children`;
  see `keyed.clj`. Same composition model — children alongside the
  parent, addressed by slot + key — but with append/remove/outer
  patch reconciliation instead of full re-render.

## See also

- [0001](0001-resume-key-naming.md) — how the answer routes back to
  the parent
- [0002](0002-edn-clean-conversation-state.md) — why the whole
  child set is persistable

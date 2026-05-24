# 0001 — Resume keys live on the parent under explicit names

## Context

When a parent component calls a child, the child eventually answers
back with a value. The framework needs to know which function on the
parent to invoke with that answer. Two plausible shapes:

1. **Anonymous continuation.** The parent passes a literal function as
   the resume callback: `(s/call :child/picker {} (fn [self v] …))`.
   The kernel stores the closure on the call frame and invokes it on
   `:answer`.

2. **Named key.** The parent declares ordinary functions on its
   component map (`:on-picked`, `:on-confirmed`, …) and the call
   names which one to fire: `(s/call :child/picker {} :on-picked)`.

## Decision

Named keys on the parent. The kernel stores `:instance/resume <key>`
on the child frame, looks `<key>` up on the parent's component
definition when the child answers, and invokes that function.

## Consequences

- **Conversations are EDN-clean.** A resume key is a plain keyword;
  closures captured at call-time are not. See
  [0002](0002-edn-clean-conversation-state.md) — this decision is
  upstream of that invariant.
- **REPL inspection reads naturally.** `(s/inspect cid)` shows
  `:on-picked` instead of `#object[…]`. The same is true of the
  on-disk EDN file.
- **`defflow` needs one fixed resume key for every await.** The
  cloroutine bridge can't name a different key per await without
  generating component map entries at macro-expansion time. We use
  one well-known `:on-flow-resume` instead. This is a small price; if
  you ever need named keys, `defflow` isn't the right shape.
- **Refactoring is slightly noisier.** Renaming a resume key means
  touching both the call site and the parent map. Anonymous
  continuations would have been one-site, but you'd have lost the EDN
  property in exchange.

## See also

- [0002](0002-edn-clean-conversation-state.md) — the property this
  enables
- [0003](0003-embed-vs-call.md) — the other side of the call/answer
  protocol

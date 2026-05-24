# 0004 — `:app` is a per-kernel value; `:principal` is fixed at mint time

## Context

The kernel needs two things from the host that it deliberately
doesn't manage itself:

1. **Live resources.** Database pools, mailers, an injectable clock,
   any function the host would otherwise stash in a top-level var.
   These can't live on the conversation — they aren't EDN
   ([0002](0002-edn-clean-conversation-state.md)) and a deploy that
   recreates a pool would break every persisted conversation.
2. **An authenticated identity.** Some pages should be visible only
   to a signed-in user. The framework already binds the conversation
   to a `stube_sid` cookie (so URL theft is harmless), but that's
   *ownership* of the conversation, not *authorisation* of a person.

Both could be implemented in user space — apps could stuff a
`:db-pool` reference on each conversation, or a `:current-user` map.
We've seen that go badly: the pool becomes stale across a deploy,
and the cached user keeps the page open after they've logged out
elsewhere.

## Decision

Two embedder options on `make-kernel`:

- **`:app`** — an opaque host value (typically a small map). Stored
  on the kernel, **not the conversation**. Read from component code
  via `(s/app)`. Build it from JVM state at `make-kernel` time;
  rebuild on every fresh kernel.
- **`:principal-fn`** — `(fn [request] principal-or-nil)`. Called
  exactly once when a conversation is minted. Result is stamped on
  `:conv/principal` and surfaced via `(s/principal)`. **Fixed for
  the life of the conversation.** No `set-principal!` operation
  exists; on login or logout, the host ends the conversation and
  re-mints.

## Consequences

- **Live resources rebuild on each `make-kernel`.** A deploy or a
  REPL reload picks them up fresh; persisted conversations
  re-attach to whatever the new kernel has.
- **`(s/app)` and `(s/principal)` are zero-arity.** They read
  dynamic vars the runtime binds during dispatch and render. Tests
  that need a stand-in can rebind: `(binding
  [dev.zeko.stube.kernel/*current-app* {:db stub}] …)`.
- **Identity changes require ending the conversation.** Reusing one
  conversation across two identities (a session swap mid-flow) is
  the exact failure mode mint-time principals prevent. The framework
  treats this as a feature, not a missing API; if you find yourself
  wanting `set-principal!`, the right move is `(s/end nil)` and a
  redirect to the host's login route.
- **`:principal` lands in EDN.** The principal IS persisted with the
  conversation, unlike `:app`. That's intentional — the principal
  is a value (a user id, a role tuple), not a resource. If the
  principal needs to be invalidated for security reasons (an account
  was banned), the host either ends affected conversations or
  filters them in its request pipeline.

## See also

- [0002](0002-edn-clean-conversation-state.md) — why `:app` had to
  be per-kernel, not per-conversation
- `docs/api.md` — *Application boundaries* section under *Embedding
  in a host Ring app* for the user-facing description
- `examples/dev/zeko/stube/examples/protected_counter.clj` — worked
  example

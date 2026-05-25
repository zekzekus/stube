# 0005 — `s/answer-error` as a separate effect; `:on-error-<key>` mirrors `:on-<key>`

## Context

A child component has historically had one channel for talking to its
parent: `(s/answer value)`. Cancellation and "the user said no" fit
that channel cleanly — `s/cancel` and `false` are legitimate values,
the parent destructures, life goes on.

Real failures don't fit. Three concrete shapes that surfaced while
porting kasten (see [`#21`](https://github.com/zekzekus/stube/issues/21)
and `:kasten/edit-form`'s save flow):

- A database write fails with a constraint violation. The child
  caught the exception (it has to — `s/await` can't sit inside a
  cloroutine `try`) and now needs to inform the parent without
  losing the exception's information.
- A network call times out and the child wants the parent to retry,
  not unwind the whole stack.
- A 409 conflict means "the user's draft is fine, but the slug
  clashed". The form's draft state must survive; only the parent's
  banner area should change.

The pre-existing workarounds were all in user-space:

```clojure
;; Option A: sentinel value
(catch Exception _ [(s/answer ::error)])

;; Option B: tagged tuple
(catch Exception ex [(s/answer [:error ex])])
```

Both leak the discrimination logic into every parent. With B the
parent's `:on-saved` becomes:

```clojure
:on-saved (fn [self v]
            (if (and (vector? v) (= :error (first v)))
              (assoc self :banner (ex-message (second v)))
              (assoc self :edit-open? false)))
```

That's the kind of branching the `:on-<key>` lookup was designed to
*avoid* — and it grows unbounded as the parent learns more failure
shapes.

S-5 / `errors/build-fragment` already handles **intra**-component
throws (a `:render` or `:handle` that throws turns into an in-place
banner). What's missing is the **inter**-component (child→parent)
symmetric path.

## Decision

A new effect `(s/answer-error ex)` (wire form `[:answer-error ex]`)
and a mirror resume convention `:on-error-<key>` on the parent.

Lookup is three-tier, in order:

1. Parent declares `:on-error-<key>` (where `<key>` is the success
   resume key). Receives the raw exception. `:on-<key>` does not
   fire.
2. Parent declares only `:on-<key>`. Falls back to it with the
   wrapped value `[:error ex]`. Logs a one-time deprecation warning
   per (parent-cdef, resume-key) pair so existing apps can adopt
   incrementally without flooding stderr.
3. Parent declares neither. Default error banner on the parent
   (same `errors/build-fragment` path as a render/handle throw),
   SSE stays open.

The `<key>` is *derived* at fail time, not stored separately. A
child whose `:instance/resume` is `:on-saved` causes the kernel to
look for `:on-error-saved` on the parent's cdef. No new
`:instance/resume-error` field is needed for the common case; if
explicit independence is wanted later, it can be added without a
wire change (extra kwargs on `[:call …]`).

## Alternatives considered

- **Stay with the wrapped-value convention.** Lowest disruption,
  but it doesn't scale. Every parent with N error shapes pays N
  branches in one resume handler. Worse, the parent has to know the
  full vocabulary at `defcomponent` time — adding a new error
  source means editing every parent that listens for that resume.
- **`try`/`catch` around `s/await`** so the parent's "handler" can
  itself catch the child's throw. Blocked by cloroutine's
  inability to traverse `try` boundaries — see `todo.md §3`. Even
  if that were lifted, it would couple the parent to the child's
  control flow, the opposite of what `:answer` aims for.
- **Typed error categories on the wire**
  (`[:answer-error {:tag :validation :fields …}]`). Rejected: the
  framework stays untyped. `ex-info` already gives you a value
  carrier; the taxonomy belongs in the app.
- **Auto-rollback** when an `answer-error` arrives after a partial
  commit. Out of scope — recovery is the app's responsibility.

## Consequences

- **One new effect, one new naming convention.** Both opt-in.
  Existing apps continue to work; the wrapped-value fallback gives
  them a clear migration path with a single deprecation line in
  the logs.
- **Resume-key naming gains a hyphenated prefix.** A parent's
  failure handler is now `:on-error-foo` mirroring `:on-foo`. This
  generalises cleanly to nested namespaces (`:on/foo` →
  `:on-error/foo`) and to non-`:on-` prefixed keys
  (`:done` → `:on-error-done` — the prefix is unconditional so the
  derivation is predictable).
- **Replay determinism is preserved.** `(s/answer-error
  (ex-info …))` is just another effect; the conversation's
  observable state changes are deterministic, even though the
  exception object isn't EDN-round-trippable. Apps that need their
  errors to be EDN-clean can `(ex-info msg data)` with serialisable
  data.
- **Cloroutine flows.** `(s/answer-error …)` inside a `defflow`
  body is supported the same way `(s/answer …)` is — it pops the
  flow frame and routes to the parent. Catching it back in the
  flow body still hits the cloroutine `try` limitation; that's a
  pre-existing constraint, not new debt.

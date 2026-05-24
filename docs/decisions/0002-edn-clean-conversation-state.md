# 0002 — Conversation state must round-trip through EDN

## Context

A conversation is a value the framework manipulates on every event.
That value has to be persisted (for crash-resume), inspected
(for halos and `s/inspect`), and tested (replays from a vector of
events should produce the exact same value as the live path).

We could store live JVM objects on the conversation — open SSE
generators, future references, raw closures, JDBC connections,
cloroutine continuations. It would be ergonomic; a handler could
stash whatever it needed on `self` and read it back later.

## Decision

Conversation values are EDN. `(pr-str conv)` round-trips through
`(clojure.edn/read-string …)` to an equal value, with one deliberate
exception (see Consequences).

## Consequences

- **Crash-resume works.** The default `file-store` writes one EDN
  file per conversation; on restart, `load-all` reads them back and
  the kernel resumes mid-flight.
- **Tests don't need a kernel.** `core/replay` walks a vector of
  events against a fresh conversation; assertions are plain `=`
  against expected values. No server, no SSE, no threading.
- **Halos shows real data.** The dev overlay's "Instance" tab is a
  pretty-printed `pr-str` of the live instance map. EDN-clean means
  every field is readable; nothing renders as `#object[…]`.
- **`defflow` is the exception.** A cloroutine continuation cannot
  round-trip through EDN — the underlying class is gensym'd and
  varies across REPL reloads. The framework allows it (the
  ergonomics earn their keep for transient flows) but the file store
  refuses to persist a conversation that contains one, with a clear
  warning. Apps that need durable flows write hand-rolled task
  components instead. See the tutorial section *Durable flows:
  defflow vs. task components*.
- **Live resources go elsewhere.** Database connections, mailers,
  the wall clock — anything that can't be EDN — live on the
  embedder's `:app` value, which is *not* persisted with the
  conversation. See [0004](0004-app-store-and-principal.md).

## See also

- [0001](0001-resume-key-naming.md) — keys-not-closures is upstream
  of this property
- [0004](0004-app-store-and-principal.md) — where live resources
  actually live

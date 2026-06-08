# Scaling a stube app

> This page is about **operating** a stube deployment at load: how many
> users one process holds, when to add memory vs. add nodes, and what
> has to change to run more than one node. It assumes you already know
> how to *deploy* — embed the routes in a Ring app and ship the
> jar/container like any other Clojure service. If that part isn't
> obvious yet, see [`examples/secure_ring.clj`](../examples/secure_ring.clj)
> and the [Kit module](kit-module.md) doc first.

The short version: **stube is a stateful, single-JVM framework. Scale
it vertically first — one well-resourced process goes a long way — and
only reach for multiple nodes when you have to, at which point the rule
is conversation affinity (sticky by `cid`).** The rest of this page is
why, and how.

---

## 1 · The shape stube is built for

stube targets the same shape its [threat model](security.md#1-threat-model)
does: a **multi-user web application running in a single JVM**, behind a
reverse proxy the host controls. Everything below follows from that one
fact, so it's worth saying plainly up front:

- A conversation (one browser tab) lives **entirely in the memory of
  one process**. Its component instances, signals-of-record, timers,
  and open SSE stream are all entries in atoms on one kernel.
- There is **no shared state between processes**. Two `make-kernel`
  calls — whether in two containers or two `make-kernel` calls in the
  same JVM — have completely independent conversation maps,
  subscription registries, and timer pools. They cannot see each
  other's conversations.
- The wire protocol is **server-authoritative**: the server holds the
  component tree and pushes DOM fragments; the browser holds only
  Datastar signals and round-trips them back on every event.

This is a deliberate design, not a gap. It's what makes the
linear-code flow ergonomics (`defflow`), the pure-kernel replayability,
and the small surface possible. The cost is that "scaling" means
something specific here, covered below.

---

## 2 · Where the state actually lives

You can't reason about scaling without knowing what's in memory. Every
piece of runtime state is an atom on the **kernel** (`runtime/make-kernel`,
`src/dev/zeko/stube/runtime.clj`), created **once per process**:

| Kernel atom | Holds | Grows with |
|---|---|---|
| `:!conversations` | `{cid → conversation}` — the component tree, instance state, signals-of-record | live + idle (un-reaped) conversations |
| `:!sse-sessions` | `{cid → SSE generator}` | currently *connected* tabs |
| `:!sse-keepalive` | `{cid → heartbeat Thread}` | one daemon thread per connected tab |
| `:!timers` | `{cid → #{futures}}` | outstanding `[:after ms …]` effects |
| `:!subscriptions` | `{topic → {[cid iid] → event}}` | `s/subscribe`d instances |
| `:!cid-locks` | `{cid → monitor Object}` | distinct cids seen |
| `:!pending-roots` | `{cid → embed-spec}` | conversations minted but not yet SSE-connected |

Two things to internalize from this table:

1. **Memory is dominated by conversations**, and a conversation stays
   in `:!conversations` until it ends or is reaped — *not* when the tab
   disconnects. A user who closes the tab without an explicit `:end`
   leaves their conversation resident until the TTL reaper sweeps it
   (see §3).
2. **Each *connected* tab costs one daemon thread** (the SSE keepalive,
   default 15s heartbeat — `:sse-keepalive-ms`). That's the main reason
   raw connection count, not just user count, matters.

There is no per-connection state *separate* from the per-cid structures
above. The `cid` is the unit of everything. The client is identified by
the `stube_sid` cookie, which is recorded as the conversation's
`:conv/owner-token` at mint and re-checked on every event POST
(`runtime/authorized?`).

---

## 3 · Scale up first (vertical)

For the large majority of stube apps this is the *entire* scaling
story, and you should exhaust it before adding nodes. One JVM holding
tens of thousands of conversations is routine.

**The three resources that bind, in order:**

**a) Heap — bounded by live conversation count × tree size.**
A conversation is plain Clojure data; a typical one is kilobytes. The
real risk isn't size, it's *count* — abandoned tabs accumulating. The
fix is the reaper:

```clojure
;; End conversations idle longer than the TTL. Embedded hosts must
;; schedule this themselves — only the standalone server runs it for you.
(embed/reap! k (java.time.Duration/ofMinutes 30))
```

Wire that onto a fixed-rate executor (every minute or two). The
standalone `server/start!` runs an internal reaper automatically; an
**embedded Ring host does not** — this is the single most common
production omission. `reap!` ends any conversation whose
`:conv/touched` is older than the TTL, which runs `:stop` hooks,
cancels timers, closes the SSE stream, and deletes from the store.
Pick a TTL longer than a plausible idle-and-return gap but short enough
to bound the heap; 30–60 minutes is a sensible default.

**b) Threads — one keepalive daemon per connected tab.**
At very high concurrent-connection counts the keepalive threads, not
heap, become the ceiling. Options, in order of preference: raise the
JVM thread limits and give the process more cores; lengthen
`:sse-keepalive-ms` (or set it to `nil` if your proxy has no idle
timeout — the heartbeat exists only to keep reverse-proxy idle timers
happy); or, if you're genuinely at the tens-of-thousands-of-
simultaneous-streams scale, that's your signal to go horizontal (§4).

**c) Dispatch CPU — serialized *per cid*, parallel *across cids*.**
Every event for a given `cid` is serialized under a per-cid monitor
(`:!cid-locks`), so a single conversation can't race itself. Different
conversations dispatch fully in parallel. This means CPU scales with
*aggregate* event rate across all users, and a single hot conversation
can't be parallelized — which is correct (it's one user's UI) but worth
knowing if a component does heavy work in `:handle`. Push slow work
into `:io` thunks / futures rather than blocking the dispatch lock.

**JVM/proxy checklist for one big node:**

- Give the heap headroom for peak *live + un-reaped* conversations, and
  actually run the reaper.
- Set the reverse-proxy read/idle timeout **above** `:sse-keepalive-ms`
  so heartbeats keep the SSE stream open (and confirm the proxy does
  not buffer the SSE response).
- On deploy, call `embed/halt!` for a graceful drain: it refuses new
  mints, cancels timers, runs `:stop` hooks, sends a final `:close`
  fragment on every open stream, and flushes the store. Browsers
  reconnect cleanly against the new process.

---

## 4 · Scaling out (horizontal)

When one node isn't enough — connection ceiling, CPU, or you want
redundancy — you can run multiple stube processes, but you must respect
**one hard rule** and decide on **three externalizations**.

### The hard rule: conversation affinity (sticky by `cid`)

A conversation lives in exactly one process's memory, and its per-cid
lock is a plain in-process `Object`. **Every request for a given `cid`
must reach the node that owns it.** A load balancer that round-robins
requests for the same conversation across nodes will split-brain its
state — different nodes will hold divergent copies and neither will
have the open SSE stream.

The good news: every stube route carries the `cid` in the **path**, so
affinity is trivial to configure without cookies or app-layer
coordination. The routes are:

```
GET  <base>/sse/:cid
POST <base>/event/:cid/:iid/:event
POST <base>/upload/:cid/:iid
POST <base>/back/:cid
```

An nginx `upstream` hashing on the cid path segment pins each
conversation to one backend:

```nginx
upstream stube {
    hash $stube_cid consistent;   # consistent hashing → minimal
    server app1:8080;             # remapping when a node joins/leaves
    server app2:8080;
}

# extract :cid from /widget/{sse,event,upload,back}/<cid>/...
map $uri $stube_cid {
    ~^/widget/(?:sse|event|upload|back)/(?<cid>[^/]+) $cid;
    default                                            $uri;
}
```

Cookie-based stickiness (`ip_hash`, a balancer affinity cookie) also
works and is simpler if your proxy offers it, but cid-hashing is the
most precise — it keys on the actual unit of state. Either way:
**sticky sessions are mandatory, not optional, for multi-node stube.**

### Externalization 1 — the conversation store (for failover & redeploy)

Affinity pins a conversation to a node, but nodes die and redeploy. By
default the kernel's atom *is* the only copy of the truth
(`store/in-memory-store`), so a process restart loses every live
conversation. To survive that, hand `make-kernel` a persistent store:

```clojure
(s/make-kernel {:store (store/file-store "/var/lib/stube/convs")})
```

`store/save!` runs after every successful dispatch (atomic temp-file +
rename); `load-all` repopulates memory at startup. With a **shared**
store backend (a networked filesystem, or a custom `ConversationStore`
over Postgres/Redis), a conversation whose owning node died can be
re-hydrated when the balancer re-pins its `cid` to a surviving node —
the user's tab reconnects its SSE stream and continues. The protocol is
three methods; writing a DB-backed one is small:

```clojure
(defprotocol ConversationStore
  (load-all [this])      ; {cid → conv}, once at startup
  (save!    [this conv]) ; after every swap-conv!
  (delete!  [this cid])) ; on :end / reap
```

**One caveat that bites:** `defflow` continuations are live cloroutine
objects, not EDN — a conversation containing one is **skipped** by
`file-store` (it logs a warning and stays live in memory only). If
durability across restarts matters for a given flow, write it as a
hand-rolled task component (`:start` + named resume keys) so its state
is an EDN-clean map. See the store namespace docstring and the
*Durable flows* section of the [tutorial](tutorial.md).

### Externalization 2 — cross-node pub/sub

`s/publish!` / `s/subscribe` walk the kernel's `:!subscriptions` atom —
**in-process only by design.** A publish on node A is invisible to
subscribers on node B. If your app uses pub/sub purely for *intra*-
conversation fan-out (a component talking to its own children), this
doesn't matter; affinity keeps both ends on the same node. It only
matters when two *different users' conversations* live on different
nodes and need to see each other's events (chat, presence, live
dashboards).

There is no built-in distributed bus, and that's intentional — every
real choice (Redis pub/sub, Postgres `LISTEN/NOTIFY`, NATS, Kafka,
Cloud Pub/Sub) carries its own durability/ordering/back-pressure model
the framework shouldn't pick for you. Two paths:

- **Today, no framework change:** stand up your bus in `:app`, and have
  components publish/subscribe through it directly —
  `(my-bus-publish (:bus (s/app)) topic msg)` — instead of
  `s/publish!`. The subscribe side dispatches incoming messages back
  into the right local conversation via `embed/dispatch! k cid event`.
- **The named seam:** [`internals.md`](internals.md#single-jvm-scope)
  sketches a `Publisher` protocol (`publish-out!` / `subscribe-in!`)
  that would hook `make-kernel` so the local walk stays the fast path
  and an external bus fans out the rest. It's deliberately unbuilt
  until a concrete app needs it — the seam is named so the change is
  additive.

### Externalization 3 — durable timers

`[:after ms event]` schedules a Java future on the owning process
(`:!timers`). If that process restarts before the timer fires, **the
timer is lost** — it does not resurrect from the store. For most UI
timing (debounce, transient "saved!" toasts) that's fine. For anything
where a missed fire is a correctness bug (a deadline, a scheduled
state transition), don't model it as `[:after …]`: persist the
intent and reconstruct it on startup, or push it to an external
scheduler/job queue and have the worker call `embed/dispatch!` back
into the conversation when it fires.

---

## 5 · Decision guide

| Situation | Do this |
|---|---|
| Normal multi-user app, moderate load | **One node.** Run the reaper. Tune heap + proxy idle timeout. Done. |
| Heap pressure from abandoned tabs | Shorten the reaper TTL before anything else. |
| Need redundancy / zero-downtime deploy, single logical node is enough | One active node + a **shared persistent store**; `halt!` drains on deploy; standby re-hydrates from the store. |
| Connection or CPU ceiling on one node | **Multiple nodes + sticky-by-cid.** Shared store for failover. |
| Users on different nodes must see each other's events | All of the above **+ a cross-node bus** (Redis/NATS/…) wired through `:app`. |
| Truly massive concurrent-stream count | Reconsider the SSE-per-tab model for the hot path; this is the edge of what stube's design targets. |

A useful mental model: **vertical scaling is free (it's just config);
horizontal scaling costs you one externalization per shared concern.**
Add nodes only when a single node genuinely can't keep up, and add only
the externalizations your app's communication pattern actually
requires — many apps need affinity + a shared store and nothing else.

---

## 6 · Known limits

These are structural — they follow from the single-JVM design and won't
change without a shift in it:

- **No automatic conversation migration.** A conversation does not move
  between nodes on its own; affinity keeps it put, the store moves it
  on restart/failover. There is no live hand-off.
- **No built-in distributed bus, scheduler, or shared store.** Each is
  a clean seam (`:app`, the `Publisher` sketch, the `ConversationStore`
  protocol) you fill with infrastructure you already run — not
  something the framework owns. See
  [security.md §8](security.md#8-known-limits) for the matching
  threat-model statement.
- **`defflow` is in-memory only.** Durable-across-restart flows must be
  hand-rolled task components. By design.

---

*See also:* [Internals → Single-JVM scope](internals.md#single-jvm-scope)
for the runtime-atom and pub/sub detail · [Security](security.md) for
the deployment posture and host-config checklist ·
[`examples/secure_ring.clj`](../examples/secure_ring.clj) for a worked
embedding.

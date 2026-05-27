# Security report for stube — current state and a route forward

> **Status:** draft.  This is an internal assessment of stube's current
> security posture and a proposed route to move from "personal research
> project" to "credibly secure for third‑party adoption."  Nothing here
> is shipped yet; the document exists to be argued with and turned into
> issues.

This document is split into:

1. What the framework looks like through a security lens today
2. Threats and current posture
3. Severity summary
4. The framework / host / author split
5. Proposed route forward (three releases)
6. What the framework should deliberately *not* own
7. Concrete next step recommendation

---

## 1. What the framework looks like through a security lens today

stube's request boundary is small and easy to enumerate.  Five real
routes (`src/dev/zeko/stube/http.clj`):

```
+------------------------+       +------------------------------+
| GET  /<mount>          | --->  | shell-handler: mint conv     |
| GET  /sse/:cid         | --->  | sse-handler:    long-lived   |
| POST /event/:cid/:iid/:e| --->  | event-handler: dispatch event|
| POST /back/:cid        | --->  | back-handler: pop history    |
| POST /upload/:cid/:iid | --->  | upload-handler: multipart    |
+------------------------+       +------------------------------+
```

Plus a few asset routes (`<base>/ui.css`, `<base>/preserve.js`) and
the dev‑only halos endpoints which are 404 unless `:halos? true`
(`src/dev/zeko/stube/halos/http.clj`).

The auth primitive is **cookie‑bound conversation ownership**:
`stube_sid` is minted on first GET; the conversation records
`:conv/owner-token`; every subsequent route on that cid checks
`authorized?` against the request cookie.  See
`src/dev/zeko/stube/session.clj` and the four `authorized?` call‑sites
in `src/dev/zeko/stube/http.clj`.

That is a sound *foundation*.  The gaps are below.

---

## 2. Threats and current posture

### 2.1 XSS — partially safe by construction, but with a real escape hatch

**What works.** Hiccup → HTML goes through Chassis
(`src/dev/zeko/stube/render.clj`).  Chassis escapes attribute and text
content by default.  Component authors writing `[:div user-name]` are
safe.  Datastar SSE morphs by id, no `innerHTML` in user space.

**What's risky.**

1. **`s/execute-script`** (`src/dev/zeko/stube/effects.clj`) emits
   literal JS.  The docstring says "last‑resort escape hatch," but
   there is no guardrail: a component that does
   `(s/execute-script (str "alert('" user-input "')"))` ships a stored
   XSS.  This is the equivalent of `dangerouslySetInnerHTML`.
2. **`on-mount` / `on-unmount`** (`src/dev/zeko/stube/render.clj`)
   accept arbitrary JS expression strings attached to elements,
   evaluated client‑side.  Same hazard if interpolated with untrusted
   data.
3. **`chassis/raw`** is used inside the framework only in error
   comments (`src/dev/zeko/stube/errors.clj`), but it is available to
   component authors via Chassis — and there is no documented policy
   about it.
4. **No CSP**.  The shell (`src/dev/zeko/stube/shell.clj`) loads
   Datastar from `d*/CDN-url` and uses inline `data-init` attributes
   which require either `unsafe-inline` or hash/nonce CSP.  There is
   no `Content-Security-Policy` header set anywhere, and no
   documentation telling the host operator what a workable CSP looks
   like.

### 2.2 CSRF — uncomfortably exposed

The defence model relies on **cookie ownership of cid**.  That is not
CSRF defence by itself:

- The cookie is `SameSite=Lax`
  (`src/dev/zeko/stube/session.clj`).  `Lax` blocks cross‑site POSTs
  from forms but **does not block top‑level GET navigations**.  The
  shell GET is destructive: it mints a conversation, sets state, and
  binds it to whoever's `stube_sid` arrived.  Cross‑site link →
  CSRF of state allocation.
- The cookie is **not `Secure`** — it will happily ride HTTP requests
  if the host ever serves over plain HTTP.
- There is **no CSRF token**.  POSTs to `/event/...` and `/back/...`
  rely entirely on the cookie.  If `SameSite=Lax` is ever weakened,
  downgraded by a browser bug, or the cookie is missing the attribute
  due to a proxy stripping/rewriting it, every state‑changing endpoint
  is open.
- The cid is in the URL path.  A cid that leaks (referrer header,
  server log, screenshot, shared URL bar) is enough to interact with
  the conversation as long as the attacker also has the matching
  cookie — but the cookie is *minted on first GET*, which means
  **anyone navigating to the mount path obtains a valid cookie for a
  fresh conversation**.  The model only protects you against an
  attacker driving *someone else's* conversation, not against an
  attacker driving their own.

### 2.3 Session/cookie hygiene

- `stube_sid` is a `UUID/randomUUID` — that is a v4 UUID with 122 bits
  of entropy, fine.
- Set as `HttpOnly; SameSite=Lax; Path=/` — missing `Secure` and
  missing a configurable `Domain`/`Path` for embedded mounts.
- Token is **never rotated** (no equivalent of session fixation
  prevention on privilege change).  With `:principal-fn`, the
  principal is fixed for the life of the conv, but the underlying
  `stube_sid` is not rotated when a user logs in/out — the host needs
  to know to do this.
- Cookie parsing is a hand‑rolled split on `;` and `=` in
  `src/dev/zeko/stube/session.clj`.  It does not normalise quoted
  values and does not honour the most‑specific cookie rule when
  duplicates exist; minor but worth knowing.

### 2.4 Conversation ID guessing

Cid generation is in `src/dev/zeko/stube/conversation.clj` — `cv-`
plus a 6‑hex counter (per the docstring in
`src/dev/zeko/stube/store.clj`).  **That is sequentially
predictable.**  It is only kept safe by the cookie check, so the
predictability is not directly an auth bypass — but it does enable
**enumeration attacks**: an attacker who *also* has a cookie (any
random visitor) can fish for active cids in error responses (the
`410` "stale" message distinguishes "ended" from "not yours").  Cids
should be unguessable random bytes.

### 2.5 Event input handling

The event handler (`src/dev/zeko/stube/http.clj`) does three
potentially dangerous reads:

1. `signals` — JSON parsed with Charred, `:key-fn keyword`.
   **`keyword` on untrusted JSON is unbounded keyword interning**,
   which is a slow JVM‑level memory leak under attack: each unique
   key permanently enters the keyword table.  For a long‑lived process
   accepting arbitrary signals JSON, this is a denial‑of‑service
   vector.  (`charred`'s 8 KiB bufsize does not help.)
2. `payload` query param read with **`edn/read-string`**
   (`src/dev/zeko/stube/http.clj`).  The bare `edn/read-string` is
   **safe against eval** (it is not `read-string` from `clojure.core`
   and it has no `:default tagged-literal`), so unknown tags will
   throw — that is actually correct.  But it has no size limit and
   will happily construct deeply nested values that can OOM the
   parser, and any registered data reader from another library on the
   classpath becomes reachable.
3. `event` path segment → `(keyword event)`.  Same keyword‑intern leak
   as (1) — attackers control the keyword namespace by hammering
   `/event/<cid>/<iid>/<arbitrary>`.

There is **no request size cap** anywhere in the framework; http‑kit
defaults stand.  Multipart uploads (`src/dev/zeko/stube/http.clj`) use
ring's `multipart-params` middleware with no configured
`:max-file-size` / `:max-files-size`, and **tempfiles are not deleted
by stube** (the upload handler puts the absolute path into the event
payload and walks away).  A misbehaving or malicious user can fill
`/tmp`.

### 2.6 Replay / storage

The file store (`src/dev/zeko/stube/store.clj`) reads conversations
with `edn/read-string` plus `:default tagged-literal` and a custom
`#inst` reader.  `tagged-literal` is the safe choice (unknown tags
become inert data, not constructor calls).  **But** anyone who can
write a file into the conversation directory can trigger reads — so
this is a privilege‑escalation surface if the store dir's filesystem
permissions are wrong.  There is no documentation guidance on what
`chmod` to apply.

### 2.7 Pub/sub, timers, IO

- `publish!` is in‑process and **unscoped** by anything but topic.
  Any component can publish to any topic, including topics another
  tenant subscribes to.  No ACL.
- `:after` schedules `future`s on the agent thread pool with no rate
  limit; a handler can `(s/after 0 :tick)` from `:on-tick` and spawn
  an infinite loop of futures.
- `:io` runs arbitrary thunks on `future`
  (`src/dev/zeko/stube/runtime.clj`) with no executor cap.

These are all "component authors are trusted" assumptions.  That is
fine in a single‑author personal app; it is not in a multi‑tenant or
third‑party‑component context.

### 2.8 Response/transport hygiene

- **No security headers** anywhere: no CSP, no
  `X-Content-Type-Options: nosniff`, no `Referrer-Policy`, no
  `X-Frame-Options`/`frame-ancestors`.  The shell could be framed
  cross‑origin, and there is no recommendation against it.
  (Especially relevant given clickjacking can drive a state‑changing
  GET that mints a conversation.)
- `Cache-Control` is `no-store` on dynamic responses (good) but the
  shell page itself is `no-store` only on the standalone handler —
  embedded hosts must add their own.
- The SSE stream has no `X-Accel-Buffering: no` hint, which nginx
  needs to stop buffering.  (Operational, not security per se, but
  listed because docs already discuss proxies.)

### 2.9 Authn vs authz

The framework draws a clean line: cid owner cookie = "this browser
owns this conv"; `:principal-fn` = "this is the authenticated user."
That split is documented in ADR 0004 and is exactly right.

**What is missing:**

- No documented "what to do when the principal changes" recipe beyond
  "end the conv and re-mint."  There is no `rotate-session!` helper,
  and re‑minting requires the host to explicitly drop the `stube_sid`
  (which the framework does not expose a way to do).
- No documented authorization seam *inside* a dispatch.  If a host
  wants "a handler may only run if the principal has role X," they
  have to write it in each `:handle`.  A `:before-dispatch` hook
  would be the natural place for this.

### 2.10 Dev tooling (halos)

Correctly gated on `:halos? true`
(`src/dev/zeko/stube/halos/http.clj`).  The panel HTML embeds
component state which could include secrets — fine in dev, would be
a leak in prod.  The docs should say "never enable halos in
production" with a one‑line ops note.

---

## 3. Severity summary

| # | Issue | Severity | Owner |
|---|---|---|---|
| 1 | `s/execute-script` + `on-mount`/`on-unmount` have no string‑escape guidance | High | **Framework** + author docs |
| 2 | No CSRF token; cookie‑only protection with `SameSite=Lax` | High | **Framework** |
| 3 | Cid is sequentially predictable (enumeration aid) | Medium‑High | **Framework** |
| 4 | Unbounded keyword interning on signals JSON, event path, signal keys | High (slow DoS) | **Framework** |
| 5 | `edn/read-string` on payload param has no size/depth bound | Medium | **Framework** |
| 6 | No request size caps; multipart tempfiles never deleted | Medium‑High | **Framework** |
| 7 | Cookie missing `Secure`; no domain control; no rotation API | Medium | **Framework** (defaults) + Host (TLS) |
| 8 | No CSP / security headers; no documented baseline | Medium | **Framework** (recipe) + Host (apply) |
| 9 | Pub/sub topics are unscoped; no ACL | Medium | **Framework** + per‑app model |
| 10 | `:io` and `:after` have no executor / rate limit | Medium | **Framework** |
| 11 | File store dir permissions undocumented | Low | Docs + Host |
| 12 | Halos in prod = state disclosure | Low | Docs + Host |
| 13 | No observability for security‑relevant events (auth fail, stale, halt) | Low‑Medium | **Framework** seam, Host wires it |

---

## 4. The framework / host / author split

A clean way to draw the line:

**Framework owns** (because the kernel emits the responses and parses
the requests):

- Cid generation (entropy)
- CSRF token primitive + middleware adapter
- Cookie default attributes + a knob for `Secure` and `Domain`
- Bounded parsers: `:key-fn` policy for signals, size and depth caps
  for JSON and EDN
- Multipart cap configuration + tempfile lifecycle (clean up after
  the dispatch, or hand off to a `:cleanup` effect)
- A `:before-dispatch` hook so hosts can attach authz / rate limit /
  observability
- A documented security‑header recipe and helpers to emit them
- Halos hard‑off in prod (a build flag, not a runtime option, would
  be a stronger guarantee)
- Auditable hooks: `:on-auth-fail`, `:on-stale`, `:on-rate-limited`,
  `:on-conv-mint`, `:on-conv-end`

**Host owns** (because only the host knows the deployment):

- TLS termination, proxy headers, idle timeouts
  (`:sse-keepalive-ms` documented)
- The principal model and login/logout flow → calls a framework
  `rotate-session!`
- The actual CSP value, including any extra origins the host needs
  (CDN, fonts, analytics)
- File store directory permissions, backup, encryption at rest
- Rate limiting at the edge (the framework provides an in‑process
  knob; cluster‑wide is host)
- WAF, IDS, secrets management — out of scope for stube

**Author owns** (component code):

- Never interpolating untrusted data into `execute-script` /
  `on-mount` / `on-unmount`
- Validating signal values before trusting them (signals are user
  input)
- Picking the right surface for dependencies (`:app` vs `:context`
  vs `:principal`)

---

## 5. Proposed route to "credibly secure"

I would sequence it as three small releases plus a published
document.  Each milestone is bounded and lands shippable.

### M1 — Close the high‑severity holes (small, mostly framework code)

1. **Cid entropy.** Replace the `cv-<hex counter>` scheme with `cv-`
   + base32 of 16 random bytes from `SecureRandom`.  Migrate
   `:conv/id` readers; everything else is opaque.  (~30 LoC in
   `conversation.clj`.)
2. **Bound parsing.**
   - Add `:max-signals-bytes` (default 64 KiB) and
     `:max-payload-bytes` (default 4 KiB) to `make-kernel`.  Reject
     oversize requests with `413`.
   - Switch signals `:key-fn` from `keyword` to a memoising fn over
     a bounded LRU (or just `identity` + an explicit `keywordize-keys`
     for an enumerated allowlist).  Document the change.
   - Same for the path `event` segment: instead of `(keyword event)`,
     look the event name up in a *registered* set per component (we
     already know the closed set of resume keys per cdef).  Unknown
     events → `400`.
3. **CSRF token.**
   - Mint a per‑conversation CSRF nonce at conversation creation;
     embed it once in the shell as `<meta name="stube-csrf">`.
   - Adapter‑side helper that adds it as a custom header to every
     Datastar POST (Datastar supports custom request headers).
   - `event-handler`/`back-handler`/`upload-handler` check the header
     against `:conv/csrf-token`.  Mismatch → `403`.
   - Keep `SameSite=Lax` as defence in depth.
4. **`Secure` cookie + a knob.** Default `Secure` on; add a
   `:dev-cookie?` option that the standalone server may flip for
   `localhost`.  Document.
5. **Multipart caps + tempfile cleanup.** Configure ring's multipart
   middleware with `:max-file-size` / `:max-file-count`; in
   `upload-handler`, wrap dispatch in a `try`/`finally` that deletes
   tempfiles after the handler has consumed them, OR document a
   `:keep-upload?` opt‑in.
6. **`execute-script` review.** Two paths:
   - Add `s/execute-script-safe` that takes a function name + args
     and JSON‑encodes args server‑side (so user data is never
     string‑concatenated into JS).
   - Mark `s/execute-script` with a `^:dangerous` meta and lint
     warning; update docs to make this loud.
   - Same review for `on-mount`/`on-unmount`.

### M2 — Observability and operator seams (framework hooks + ops doc)

7. **Security event hooks** on the kernel: `:on-auth-fail`,
   `:on-stale`, `:on-rate-limited`, `:on-shell-mint`.  These are
   functions of `(request, info)`; default is `nil` (no‑op).  Replace
   the in‑file `println` paths in `runtime.clj` / `store.clj` with a
   configurable logger fn.
8. **`:before-dispatch` middleware seam** that runs before any
   handler.  Hosts use it for rate limiting, authz, audit.  Signature:
   `(fn [conv event request] -> :continue | [:reject status body])`.
9. **Cluster IDs for logs** — a `:request-id` propagated to MDC;
   already partly there via `src/dev/zeko/stube/http.clj`.  Document
   the slf4j integration.
10. **Halos hard‑off in prod.** Move the halos asset/route
    registration behind a JVM property or env var checked at
    namespace‑load time, not just `make-kernel` options.
    Belt‑and‑braces.
11. **Session rotation API.** `(embed/rotate-session! k cid)` —
    generates new `stube_sid`, updates `:conv/owner-token`, returns
    a `Set-Cookie` the host attaches to its response.  Document it
    as "call this on login/logout."

### M3 — Defaults and headers (mostly docs + small helpers)

12. **Recommended response headers** ship as a Ring middleware
    factory: `stube.security/wrap-defaults` adds
    `X-Content-Type-Options`, `Referrer-Policy: same-origin`,
    `Cross-Origin-Opener-Policy: same-origin`, `Permissions-Policy`
    (sane minimum), `X-Frame-Options: SAMEORIGIN` (or
    `frame-ancestors` via CSP).  Hosts opt in by wrapping
    `ring-handler`.
13. **CSP recipe.** A documented baseline that works with Datastar +
    preserve.js + stock CSS, using nonces for `data-init` so the page
    can drop `unsafe-inline`.  This is the most fiddly piece because
    Datastar uses inline `data-on:*` attributes — confirm a working
    CSP with the upstream and ship it.
14. **`docs/security.md`.** New top‑level doc page with sections:
    - Threat model (multi‑user single‑JVM web app, cookies trusted)
    - The shared‑responsibility table (framework / host / author)
    - Required host configuration (TLS, proxy, headers, store dir
      permissions)
    - Component‑author do/don't (especially around `execute-script`)
    - How to do login/logout safely (session rotation, principal
      change)
    - How to enable audit logging and what events fire
    - Known limits (single‑JVM pub/sub, no built‑in WAF, etc.)
15. **Pen‑test / threat‑model review.** Even a one‑afternoon external
    review of the surface after M1+M2 ship would be high‑value and
    citable.

### Stretch — for "third‑party component" / multi‑tenant ambitions

- **Per‑kernel registry** (drop the global atom in
  `src/dev/zeko/stube/registry.clj`) so two tenants in one JVM cannot
  see each other's components.
- **Topic ACLs in pub/sub**: register topics with a "can publish from
  cid X?" predicate, or namespace them per principal.
- **Executor caps** for `:io` and `:after`: an injectable
  `java.util.concurrent.Executor` so ops can bound concurrency.
- **Signed conversation cookies** (HMAC the cid + owner‑token) so a
  leaked log line containing a cid alone is not enough.

---

## 6. What I would *not* try to make the framework own

These are real concerns, but the right answer is "the host does it":

- **WAF / IP block lists** — Cloudflare / nginx / ALB do this better.
- **Auth provider integration** — `:principal-fn` is the seam; the
  host picks OIDC / SAML / etc.
- **Encryption at rest of the conversation store** — disk encryption
  is an ops concern; the file store should just document "don't put
  plaintext secrets in component state."
- **Rate limiting beyond a per‑cid soft cap** — global / per‑IP
  belongs at the edge.
- **Audit log persistence** — the framework should *emit* audit
  events via the M2 hooks; storing them is the host's job.

---

## 7. Concrete next step recommendation

If you want a single deliverable to make adoption defensible, write
`docs/security.md` *before* shipping the code changes.  That document
forces you to enumerate the contract; the M1 fixes then become
obvious because they are the items the document would otherwise have
to apologise for.  Once the doc + M1 land, you can credibly say:

> stube ships with a documented threat model, signed CSRF tokens on
> every state‑changing request, unguessable conversation ids,
> bounded parsing, secure‑by‑default cookies, and operator‑facing
> audit hooks.  The host is responsible for TLS, CSP customization,
> and authentication; an example Ring stack demonstrating the full
> chain ships in `examples/secure_ring.clj`.

That sentence is the bar for "considered secure" for a small Clojure
web framework.  None of the work to get there is research‑grade; it
is small, well‑scoped engineering that fits the existing architecture
cleanly.

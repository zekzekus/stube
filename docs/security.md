# Security

This page is the security contract for stube: the threat model it is
designed against, the split of responsibility between the framework,
the host that embeds it, and the component author, and the concrete
configuration a host must apply to deploy it safely.

> **Honesty note.** stube grew up as a personal research project, and
> some of the hardening described here as the *target* contract is not
> yet enforced by the framework. Where that is true I say so inline and
> mark the item **(gap — tracked)** with a pointer to the security
> section of [`todo.md`](../todo.md). The route from here to "every
> item below is enforced by default" is sequenced in
> [`docs/security_draft.md`](security_draft.md). This page is updated
> as each fix lands, so it always describes the *current* state, not
> the aspiration.

The authentication/authorization split this page leans on is the
subject of [ADR 0004](decisions/0004-app-store-and-principal.md); read
that first if you only want the auth model.

---

## 1 · Threat model

stube targets one shape: a **multi-user web application running in a
single JVM**, served over HTTPS, behind a reverse proxy the host
controls. Within that shape:

- **Browsers are untrusted.** Every request body, query param, signal
  value, path segment, and uploaded file is attacker-controlled input.
- **The cookie is trusted.** The `stube_sid` cookie is the ownership
  primitive. We assume the transport protects it (HTTPS) and the
  browser enforces `HttpOnly`/`SameSite`. A host that serves over plain
  HTTP, or terminates TLS without setting the cookie's `Secure`
  attribute, has voided this assumption.
- **Component authors are trusted.** Component code runs server-side
  with full JVM authority. stube does **not** sandbox component code,
  pub/sub topics, `:io` thunks, or `s/execute-script` output. This is
  fine for a single-author or single-team app; it is *not* a
  third-party-plugin trust boundary. See [§7](#7-known-limits).
- **The conversation store is trusted.** Anything that can write a file
  into the store directory can influence what is read back at startup.
  The directory's filesystem permissions are part of the trust boundary
  (see [§5](#5-required-host-configuration)).

Out of scope by design: WAF / IP blocklists, auth-provider integration,
encryption at rest, cluster-wide rate limiting, audit-log persistence.
These are host concerns; the framework's job is to expose clean seams
for them, not to own them.

---

## 2 · Shared responsibility

| Layer | Owns |
|---|---|
| **Framework** | Conversation-id entropy; cookie default attributes; bounded request parsing; multipart caps + tempfile lifecycle; the cid-ownership check; auditable security hooks; a security-headers helper. |
| **Host** | TLS termination and proxy headers; the principal model and login/logout flow; the concrete CSP value; store-directory permissions; edge rate limiting; keeping halos off in production. |
| **Author** | Never interpolating untrusted data into `s/execute-script` / `s/on-mount` / `s/on-unmount`; validating signal values before trusting them; picking the right dependency surface (`s/app` vs `s/context` vs `s/principal`). |

The rest of this page expands each row.

---

## 3 · What the framework enforces today

These are the guarantees you can rely on right now, on the current
release.

- **XSS-safe rendering by construction.** Hiccup → HTML goes through
  Chassis (`render.clj`), which escapes attribute and text content by
  default. `[:div user-name]` is safe. UI updates morph by element id
  over SSE; there is no `innerHTML` seam in component space.
- **Cookie-bound conversation ownership.** `stube_sid` is a v4 UUID
  (122 bits of entropy) minted on first GET and recorded on the
  conversation as `:conv/owner-token`. Every subsequent route on that
  cid checks `authorized?` against the request cookie; a mismatch is a
  `403`. This is the single ownership primitive both the HTTP and halos
  handlers use (`session.clj`).
- **Unguessable conversation ids.** A cid is `cv-` + 128 bits of
  `SecureRandom`, hex-encoded (`conversation.clj/new-cid`). It is not a
  secret — the owner cookie gates access — but it is not enumerable
  either, so a visitor cannot fish for other live conversations via the
  `410`-vs-`403` response split.
- **EDN reads are eval-safe.** The file store reads conversations with
  `clojure.edn/read-string` and `:default tagged-literal` — unknown
  tags become inert data, never constructor calls (`store.clj`). The
  event-payload query param is read with `clojure.edn/read-string`
  (the namespaced reader, *not* `clojure.core/read-string`), so it
  cannot eval either (`http.clj`).
- **Asset paths cannot traverse.** Component CSS/JS/behavior routes
  reject anything outside `[A-Za-z0-9_-]` one directory deep — no `..`,
  no nested paths, no dots in segment names (`http.clj` `safe-asset?`).
- **Bounded request parsing.** An event POST caps its JSON signals body
  (`:max-signals-bytes`, default 64 KiB) and its EDN `payload` query
  param (`:max-payload-bytes`, default 4 KiB). Oversize → `413` (the
  signals stream is never fully buffered); unparseable payload → `400`.
  The payload bound also caps EDN nesting depth, so a deep value cannot
  exhaust the parser stack.
- **Bounded keyword interning.** Untrusted JSON signal keys and the
  event-name path segment are resolved with `find-keyword`, never
  `keyword`, so an attacker cannot grow the JVM keyword table by sending
  novel keys — closing a slow memory-leak DoS. Keys a component actually
  uses are keyword literals (already interned) and resolve normally.
- **Bounded uploads + tempfile cleanup.** A multipart body over
  `:max-upload-bytes` (default 10 MiB) is rejected (`413`) by
  `Content-Length` before parsing, and ring's tempfiles are deleted once
  the dispatch consumes them — so uploads can neither overflow the
  request nor accumulate on disk. `:keep-upload? true` opts a kernel out
  for handlers that process the file asynchronously.
- **Cookies are `HttpOnly`, `SameSite=Lax`, and `Secure` by default.**
  This blocks JS cookie theft, cross-site form POSTs, and any plain-HTTP
  leak of the cookie (`session.clj`). `Secure` is on unless the kernel
  is built with `:dev-cookie? true`; the standalone `s/start!` server
  sets that for localhost, so a standalone TLS deploy must pass
  `:dev-cookie? false`.
- **A reaper exists.** `(embed/reap! k ttl)` ends conversations whose
  `:conv/touched` is older than `ttl`, bounding unbounded growth from
  conversation minting — *once the host wires it onto a schedule*
  (`runtime.clj`).
- **Graceful shutdown.** `halt!` refuses new mints (503), runs `:stop`
  hooks, drains SSE, and flushes the store (`runtime.clj`).

---

## 4 · Current gaps being closed

These are known weaknesses. Each is tracked in the security section of
[`todo.md`](../todo.md); this list exists so a host operator can make an
informed risk decision today and apply the compensating control in
[§5](#5-required-host-configuration).

| Gap | Risk | Compensating control until fixed |
|---|---|---|
| **No CSRF token** (gap — tracked) | State-changing POSTs (`/event`, `/back`, `/upload`) rely entirely on the cookie + `SameSite=Lax`. | Keep `SameSite=Lax` intact end-to-end; ensure no proxy strips or rewrites the cookie attribute. |
| **No CSP or security headers** (gap — tracked) | The shell can be framed cross-origin; no `nosniff`, no `Referrer-Policy`. | Apply the headers in [§5](#5-required-host-configuration) at the proxy or via host middleware. |
| **Pub/sub topics are unscoped; `:io`/`:after` uncapped** (gap — tracked) | Any component can publish to any topic; async effects spawn unbounded futures. Only matters under an untrusted-component model. | Trust your component authors (see [§1](#1-threat-model)); use `publish-local!` for per-conversation channels. |

---

## 5 · Required host configuration

A safe deployment applies all of the following. Several of these are
the compensating controls for [§4](#4-current-gaps-being-closed) and
become belt-and-braces once the framework fix lands.

**Transport**
- Serve over HTTPS only. Terminate TLS at the proxy and redirect or
  refuse plain HTTP. The cookie is `Secure` by default, so it will not
  even be sent over plain HTTP — but the rest of the exchange still
  needs TLS.
- Send `Strict-Transport-Security` so a downgrade is not silently
  accepted.
- If you deploy the standalone `s/start!` server behind a TLS
  terminator, pass `:dev-cookie? false` so the cookie keeps its
  `Secure` attribute (it defaults to dev mode for localhost).

**Reverse proxy (SSE-aware)**
- Do **not** buffer the SSE stream. For nginx: `proxy_buffering off;`
  and `X-Accel-Buffering: no` on the `/sse/` location. stube already
  sends a keepalive comment every `:sse-keepalive-ms` (default 15s) to
  survive idle timeouts; set the proxy idle timeout above that.
- Do not strip or rewrite the `Cookie` / `Set-Cookie` headers — the
  `SameSite=Lax` attribute is load-bearing CSRF defence today.

**Response headers** (apply at the proxy, or wrap `ring-handler`):
```
X-Content-Type-Options: nosniff
Referrer-Policy: same-origin
X-Frame-Options: SAMEORIGIN          # or frame-ancestors via CSP
Cross-Origin-Opener-Policy: same-origin
Permissions-Policy: <your minimum>
Content-Security-Policy: <see note>
```
The CSP is the fiddly one: the stube shell uses an inline `data-init`
attribute and Datastar uses inline `data-on:*` attributes, so a strict
CSP needs a nonce or hash strategy rather than a blanket
`unsafe-inline`. The shell also loads Datastar from a CDN
(`d*/CDN-url`) — add that origin to `script-src`, or self-host the
asset. A worked, Datastar-compatible CSP baseline is on the roadmap;
until then, start from `default-src 'self'` plus the Datastar CDN
origin and add nonces for the inline attributes.

**Request limits**
- The framework caps the signals body (`:max-signals-bytes`), EDN
  payload (`:max-payload-bytes`), and multipart upload Content-Length
  (`:max-upload-bytes`). A belt-and-braces body-size limit at the proxy
  is still good practice, especially for non-stube routes the host
  serves.
- stube deletes multipart tempfiles after each upload dispatch. If you
  set `:keep-upload? true` for async processing, mount the tempfile
  directory on a bounded volume and reap it out of band yourself.

**Conversation store**
- `chmod` the file-store directory so only the stube process user can
  read or write it. Files in that directory are read with
  `edn/read-string` at startup — write access to the directory is part
  of the trust boundary.
- Do not put plaintext secrets in component state; it is persisted to
  the store as EDN. Encryption at rest is a disk/ops concern.

**Lifecycle**
- Wire `(embed/reap! k ttl)` onto a schedule so abandoned
  conversations are evicted; minting is a GET and is otherwise
  unbounded.
- Apply an edge rate limit (per IP / per session) on the mount path and
  the POST endpoints.

**Dev tooling**
- **Never enable halos (`:halos? true`) in production.** The halos
  panel embeds live component state — fine in dev, a state-disclosure
  leak in prod.

---

## 6 · Rules for component authors

Component code is trusted and runs with full server authority. Three
rules keep that trust from becoming a vulnerability:

1. **Never interpolate untrusted data into client-side script.**
   `s/execute-script`, `s/on-mount`, and `s/on-unmount` emit literal JS
   that the browser evaluates — they are stube's `dangerouslySetInnerHTML`
   equivalent. This is a stored XSS:
   ```clojure
   ;; NEVER
   (s/execute-script (str "showToast('" user-input "')"))
   ```
   If you must pass data to a script, JSON-encode it server-side first
   so the value can never break out of its literal, or set a signal and
   read it from a pre-written client function. Treat these three helpers
   as a last resort and review every call site.

2. **Validate signal values before trusting them.** Signals are user
   input. A component that `:keep`s a signal receives whatever the
   browser sent — coerce, bound, and validate it in the handler before
   acting on it (parse numbers, clamp ranges, reject unknown enum
   values). The same applies to event payloads and uploaded file
   metadata (filename, content-type, size are all attacker-supplied).

3. **Pick the right dependency surface.** Read shared services with
   `s/app`, request/connection context with `s/context`, and the
   authenticated user with `s/principal` — see
   [ADR 0004](decisions/0004-app-store-and-principal.md) and the
   "Reading dependencies" section of [`api.md`](api.md). Don't smuggle
   the principal through component state where a buggy handler could
   overwrite it; the kernel protects `:stube/context` and `:conv/principal`
   for exactly this reason.

---

## 7 · Authentication vs authorization

stube draws a deliberate line, documented in
[ADR 0004](decisions/0004-app-store-and-principal.md):

- **Cid-owner cookie** = "this browser owns this conversation." The
  framework owns this primitive (`stube_sid` → `:conv/owner-token`).
- **`:principal-fn`** = "this is the authenticated user." The host owns
  this. It runs once at mint time; the result is persisted as
  `:conv/principal` and read with `(s/principal)`.

**Login / logout.** The principal is fixed for the life of a
conversation. When the authenticated user changes (login, logout,
privilege change), the correct move today is to **end the conversation
and re-mint** so a fresh `:conv/principal` is captured. A dedicated
`rotate-session!` helper that rotates `stube_sid` and the owner-token in
place is on the roadmap (gap — tracked); until it lands, end-and-remint
is the supported path.

**Per-dispatch authorization** (e.g. "this handler runs only for role
X") is currently the component's own responsibility inside `:handle`. A
`:before-dispatch` kernel seam to centralise authz / rate-limit / audit
is on the roadmap (gap — tracked).

---

## 8 · Known limits

These are structural and will not change without a corresponding shift
in the threat model:

- **Single-JVM, in-process pub/sub.** Topics are process-local and
  live-only; there is no cross-process bus. Bring your own via `:app`
  if you need one.
- **No component sandbox.** Component code, `:io` thunks, and
  `s/execute-script` output are trusted. stube is not a safe host for
  third-party / untrusted plugins. A per-kernel registry, topic ACLs,
  and executor caps are sketched as stretch work in
  [`security_draft.md`](security_draft.md) but are not built — and
  should not be, until a concrete multi-tenant host needs them.
- **No built-in WAF, IDS, or secrets management.** By design — these
  live at the host/edge.

---

## 9 · Roadmap

The sequenced plan to close every **(gap — tracked)** item above lives
in [`docs/security_draft.md`](security_draft.md) (the assessment and the
three-release route) and as a checklist in the security section of
[`todo.md`](../todo.md). As each item ships, its row moves from
[§4](#4-current-gaps-being-closed) into [§3](#3-what-the-framework-enforces-today)
and this page stops apologising for it.

// stube behaviors bridge
//
// Client-side companion of `s/behavior`.  The render layer marks an
// element with
//
//   data-stube-behavior="ns/name"
//   data-stube-arg-<key>="..."
//
// and this script discovers those elements on `stube:patched`, lazy-
// imports the matching module under `<base-path>/behaviors/ns/name.js`,
// and drives its lifecycle:
//
//   - `mount(el, ctx)`    once, the first time `el` is seen
//   - `patched(el, ctx)`  on every subsequent patch that left `el` alive
//   - `unmount(el, ctx)`  once, when `el` detaches from the DOM
//
// A behavior module is the default export shape
//
//   export default {
//     mount(el, ctx) { ... },
//     patched(el, ctx) { ... },
//     unmount(el, ctx) { ... },
//   }
//
// Every callback is optional.  `ctx.args` is a camelCased object built
// from the `data-stube-arg-*` attributes; `ctx.signals` is a thin
// accessor for Datastar signals when available.  `ctx.basePath` is
// the kernel base-path so behaviors can address other stube URLs.

(() => {
  const installedKey = "__stubeBehaviorsInstalled";
  if (globalThis[installedKey]) return;
  globalThis[installedKey] = true;

  const attr = "data-stube-behavior";
  const argPrefix = "data-stube-arg-";
  const stateKey = "__stubeBehaviorState";

  // Resolving the kernel base-path is non-trivial because this file is
  // loaded as `<script type="module">`, and inside a module IIFE
  // `document.currentScript` is always null (MDN: "It returns null if
  // the script element is a module script").  We try every reasonable
  // source in order and cache the first hit:
  //
  //   1. `import.meta.url` — always available in module scripts.  The
  //      bridge is served from `<base>/behaviors.js`, so stripping the
  //      trailing `/behaviors.js` from its pathname recovers the base.
  //   2. `<html>` or `<body>` `data-stube-base-path` attribute, if the
  //      host stamped one on (legacy escape hatch).
  //   3. Any element in the document with `data-stube-base-path` (e.g.
  //      the stube shell `<div>` itself always carries the attribute).
  //   4. Empty string (standalone stube mount).
  // `import.meta.url` is evaluated at the module's top level (capturing
  // it here, not inside a function, makes it accessible even when the
  // resolver re-runs after later DOM updates).
  let importMetaUrl = null;
  try { importMetaUrl = import.meta.url; } catch (_e) {}

  let resolvedBasePath = null;
  const trimTrailingSlash = (s) =>
    s && s !== "/" && s.endsWith("/") ? s.slice(0, -1) : (s === "/" ? "" : s);
  const stripSelfFromUrl = (url) => {
    try {
      const u = new URL(url);
      // strip the final `/behaviors.js` segment if present
      return u.pathname.replace(/\/behaviors\.js$/, "");
    } catch (_e) { return null; }
  };
  const resolveBasePath = () => {
    if (resolvedBasePath !== null) return resolvedBasePath;
    // 1. import.meta.url — most reliable in modules.
    if (importMetaUrl) {
      const fromMeta = stripSelfFromUrl(importMetaUrl);
      if (typeof fromMeta === "string") {
        resolvedBasePath = trimTrailingSlash(fromMeta) || "";
        return resolvedBasePath;
      }
    }
    // 2. <html>/<body> data attribute.
    for (const el of [document.documentElement, document.body]) {
      if (el && el.hasAttribute && el.hasAttribute("data-stube-base-path")) {
        resolvedBasePath = el.getAttribute("data-stube-base-path") || "";
        return resolvedBasePath;
      }
    }
    // 3. Any element carrying the attribute (e.g. the shell <div>).
    try {
      const any = document.querySelector("[data-stube-base-path]");
      if (any) {
        resolvedBasePath = any.getAttribute("data-stube-base-path") || "";
        return resolvedBasePath;
      }
    } catch (_e) {}
    // 4. Standalone mount.
    resolvedBasePath = "";
    return resolvedBasePath;
  };

  const moduleCache = new Map();

  const loadBehavior = (slug) => {
    if (moduleCache.has(slug)) return moduleCache.get(slug);
    const base = resolveBasePath();
    const url = `${base}/behaviors/${slug}.js`;
    const p = import(/* @vite-ignore */ url)
      .then((mod) => mod.default || mod)
      .catch((err) => {
        try { console.error(`stube: failed to load behavior ${slug} from ${url}`, err); }
        catch (_e) {}
        moduleCache.delete(slug);
        return null;
      });
    moduleCache.set(slug, p);
    return p;
  };

  const camelKey = (s) =>
    s.replace(/-([a-z0-9])/g, (_m, c) => c.toUpperCase());

  const readArgs = (el) => {
    const out = {};
    for (const a of el.attributes) {
      if (!a.name.startsWith(argPrefix)) continue;
      out[camelKey(a.name.slice(argPrefix.length))] = a.value;
    }
    return out;
  };

  // Signal writes flow through Datastar's *public* attribute API rather
  // than any Datastar-internal handle — we depend on `data-bind:<key>`
  // (the headline two-way-binding feature) and a standard DOM `input`
  // event, nothing more.  The render layer ships `(s/signal-mirror :k)`
  // which produces a hidden `<input data-bind:<wire>
  // data-stube-signal-mirror="<wire>">`.  Writes locate that input by
  // its marker, set `.value`, and dispatch `input`; Datastar's
  // `data-bind` machinery propagates the value into the signal store.
  //
  // Why not call Datastar's internal setter directly?  The bundle's only
  // documented external-write surface is the ESM export `mergePatch`,
  // which couples the bridge to Datastar's module shape (any rename or
  // bundle split would silently break us).  `data-bind` is the
  // headline public API every Datastar app relies on; this seam keeps
  // working across Datastar versions.
  const mirrorAttr = "data-stube-signal-mirror";

  const findMirror = (rootEl, name) => {
    if (typeof name !== "string" || !name) return null;
    let selector;
    try {
      selector = `[${mirrorAttr}="${CSS.escape(name)}"]`;
    } catch (_e) {
      // CSS.escape isn't universal in very old browsers; fall back to a
      // best-effort match (signal wire names are kebab/camel, never
      // contain quotes or brackets, so an unescaped attribute selector
      // is fine in practice).
      selector = `[${mirrorAttr}="${name}"]`;
    }
    // Walk up ancestors querying down at each level — this narrows the
    // lookup to the nearest mirror when multiple components on the same
    // page bind the same logical name.  Falls back to a document-wide
    // search.
    let scope = rootEl;
    while (scope && scope.querySelector) {
      const hit = scope.querySelector(selector);
      if (hit) return hit;
      scope = scope.parentElement;
    }
    return document.querySelector(selector);
  };

  const writeMirror = (rootEl, name, value) => {
    const mirror = findMirror(rootEl, name);
    if (!mirror) {
      try {
        console.warn(
          `stube: ctx.setSignal(${JSON.stringify(name)}, …) found no ` +
          `[${mirrorAttr}="${name}"] element in scope. Render one with ` +
          `(s/signal-mirror :${name}) inside the same view.`
        );
      } catch (_e) {}
      return false;
    }
    mirror.value = value == null ? "" : String(value);
    try {
      mirror.dispatchEvent(new Event("input", { bubbles: true }));
    } catch (_e) {}
    return true;
  };

  // Reads also prefer the mirror's `.value` when present — same DOM
  // source of truth as writes, so a behavior can round-trip its own
  // signal through `set` then `get` without depending on any Datastar
  // runtime handle.  Falls back to `globalThis.ds` for hosts whose
  // Datastar build happens to expose it (best-effort, version-dependent).
  const resolveDsNode = (name) => {
    const ds = globalThis.ds;
    let node = ds?.signals?.value;
    if (!node || typeof name !== "string") return undefined;
    const parts = name.split(".");
    for (let i = 0; i < parts.length; i++) {
      if (node == null) return undefined;
      node = node[parts[i]];
      if (node && typeof node === "object" && "value" in node && i < parts.length - 1) {
        node = node.value;
      }
    }
    return node;
  };

  const buildSignals = (rootEl) => ({
    get(name) {
      const mirror = findMirror(rootEl, name);
      if (mirror && typeof mirror.value === "string") return mirror.value;
      try {
        const sig = resolveDsNode(name);
        return sig && typeof sig === "object" && "value" in sig ? sig.value : undefined;
      } catch (_e) { return undefined; }
    },
    set(name, value) { writeMirror(rootEl, name, value); },
    patch(values) {
      if (!values || typeof values !== "object") return;
      for (const [k, v] of Object.entries(values)) writeMirror(rootEl, k, v);
    },
  });

  // POST to a stube event URL the same way `s/on` does.  Shared by
  // `ctx.fetch` (caller supplies the URL) and `ctx.dispatch` (we build
  // the URL from the stamped event base).
  const postEvent = async (url, opts) => {
    const init = {
      method: "POST",
      headers: {"Accept": "text/event-stream"},
      ...(opts || {}),
    };
    if (init.body && typeof init.body !== "string" && !(init.body instanceof FormData)) {
      init.body = JSON.stringify(init.body);
      init.headers = {"Content-Type": "application/json", ...init.headers};
    }
    return fetch(url, init);
  };

  // Mirrors of server-side constants in `dev.zeko.stube.render`:
  // `s/behavior` stamps the dispatch target as `data-stube-event-base`
  // (`/event/<cid>/<iid>`), and `event-url` rides structured payloads in
  // the `_stube_payload` query param, read back with `edn/read-string`.
  const eventBaseAttr = "data-stube-event-base";
  const payloadParam = "_stube_payload";

  // Encode a JS value as EDN for the payload query param.  Covers the
  // JSON value subset the server's `edn/read-string` accepts: null→nil,
  // strings, finite numbers, booleans, arrays→vectors, and plain
  // objects→maps with keyword keys (`{a:1}` → `{:a 1}`).  Anything else
  // (functions, symbols, NaN/Infinity) encodes as `nil`.  Note the
  // object-key mapping: pass a plain object and the handler sees keyword
  // keys, matching the Clojure-side expectation.
  const toEdn = (v) => {
    if (v === null || v === undefined) return "nil";
    switch (typeof v) {
      case "string":  return JSON.stringify(v);
      case "number":  return Number.isFinite(v) ? String(v) : "nil";
      case "boolean": return v ? "true" : "false";
      case "object":
        if (Array.isArray(v)) return "[" + v.map(toEdn).join(" ") + "]";
        return "{" + Object.entries(v)
          .map(([k, val]) => `:${k} ${toEdn(val)}`).join(" ") + "}";
      default: return "nil";
    }
  };

  // Fire a component event on the owning component without the host
  // pre-building an `event-url`.  Resolves the dispatch target from the
  // nearest `data-stube-event-base` (stamped on the behavior element by
  // `s/behavior`).  Unlike Datastar's `@post`, this does not attach the
  // current signals — pass anything the handler needs as `payload`.
  const buildDispatch = (el) => async (event, payload, opts) => {
    const baseEl = typeof el.closest === "function"
      ? el.closest(`[${eventBaseAttr}]`)
      : null;
    const base = baseEl && baseEl.getAttribute(eventBaseAttr);
    if (!base) {
      try {
        console.warn(
          `stube: ctx.dispatch(${JSON.stringify(event)}) found no ` +
          `[${eventBaseAttr}] in scope. Attach the behavior with ` +
          `(s/behavior self …) inside a rendered component so the server ` +
          `can stamp the dispatch target.`
        );
      } catch (_e) {}
      return null;
    }
    const name = typeof event === "string" ? event
      : (event == null ? "" : String(event));
    let url = `${base}/${encodeURIComponent(name)}`;
    if (payload !== undefined) {
      url += `?${payloadParam}=${encodeURIComponent(toEdn(payload))}`;
    }
    return postEvent(url, opts);
  };

  const buildCtx = (el) => {
    const signals = buildSignals(el);
    return {
      el,
      args: readArgs(el),
      basePath: resolveBasePath(),
      signals,
      // Convenience aliases so behavior code doesn't have to reach
      // into `ctx.signals` for the common write paths.
      setSignal: signals.set,
      patchSignals: signals.patch,
      fetch: postEvent,
      dispatch: buildDispatch(el),
    };
  };

  const safeCall = (fn, el, ctx, phase, slug) => {
    if (typeof fn !== "function") return;
    try { fn(el, ctx); }
    catch (err) {
      try { console.error(`stube behavior ${slug} ${phase} threw:`, err); }
      catch (_e) {}
    }
  };

  const runMountOrPatched = async (el) => {
    if (!el.isConnected) return;
    const slug = el.getAttribute(attr);
    if (!slug) return;

    const mod = await loadBehavior(slug);
    if (!mod) return;
    if (!el.isConnected) return;

    const state = el[stateKey] || (el[stateKey] = {mounted: new Map()});
    const ctx = buildCtx(el);

    if (state.mounted.has(slug)) {
      safeCall(mod.patched, el, ctx, "patched", slug);
    } else {
      state.mounted.set(slug, mod);
      safeCall(mod.mount, el, ctx, "mount", slug);
    }
  };

  const runUnmount = (el) => {
    const state = el[stateKey];
    if (!state || !state.mounted.size) return;
    const ctx = buildCtx(el);
    for (const [slug, mod] of state.mounted) {
      safeCall(mod.unmount, el, ctx, "unmount", slug);
    }
    state.mounted.clear();
  };

  const walkAndMount = (root) => {
    if (!root) return;
    if (root.nodeType === Node.ELEMENT_NODE && root.hasAttribute(attr)) {
      runMountOrPatched(root);
    }
    if (typeof root.querySelectorAll === "function") {
      for (const el of root.querySelectorAll(`[${attr}]`)) {
        runMountOrPatched(el);
      }
    }
  };

  const walkAndUnmount = (root) => {
    if (!root) return;
    if (root.nodeType === Node.ELEMENT_NODE && root[stateKey]) {
      runUnmount(root);
    }
    if (typeof root.querySelectorAll === "function") {
      for (const el of root.querySelectorAll(`[${attr}]`)) {
        if (el[stateKey]) runUnmount(el);
      }
    }
  };

  // On every Datastar morph, sweep the document for behaviors.  We
  // intentionally do not gate on patch detail — a previous patch may
  // have inserted behavior elements outside its own subtree, and the
  // cost of `querySelectorAll([data-stube-behavior])` is tiny for
  // realistic component counts.
  document.addEventListener("stube:patched", () => {
    walkAndMount(document.body || document.documentElement);
  });

  // Detect removed elements so unmount lifecycle fires exactly once.
  // Pairs with `s/preserve.js`'s detach observer — different attribute,
  // different state, no overlap.
  const installRemovalObserver = () => {
    if (!document.body) {
      document.addEventListener("DOMContentLoaded", installRemovalObserver, {once: true});
      return;
    }
    const observer = new MutationObserver((records) => {
      for (const r of records) {
        if (r.type !== "childList") continue;
        for (const node of r.removedNodes) {
          queueMicrotask(() => {
            if (node && node.nodeType === Node.ELEMENT_NODE && !node.isConnected) {
              walkAndUnmount(node);
            }
          });
        }
      }
    });
    observer.observe(document.body, {childList: true, subtree: true});
  };

  installRemovalObserver();

  // Initial sweep — page load may already contain behavior markers
  // before any SSE patch has landed.
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => walkAndMount(document.body), {once: true});
  } else {
    walkAndMount(document.body || document.documentElement);
  }
})();

// stube CSRF bridge
//
// The shell stamps a per-conversation token on its root element as
// `data-stube-csrf`; the server requires it back on every state-changing
// request and rejects a mismatch with 403.  Two transports:
//
//   - event / back go out as Datastar `@post` → `window.fetch`, so we
//     wrap fetch and add the `X-Stube-Csrf` header.  A custom header
//     can't be set cross-site without a CORS preflight the attacker's
//     origin can't satisfy, which is what makes it a CSRF defence.
//   - uploads are a zero-JS `multipart/form-data` <form> targeting a
//     hidden iframe; a form can't set a header, so we inject a hidden
//     `_stube_csrf` field on submit instead.
//
// This runs as a module *before* Datastar in the head, so the fetch
// wrapper is in place before any user-triggered POST.  We touch only
// the web-platform fetch/submit seams — never a Datastar internal.
(() => {
  const flag = "__stubeCsrfInstalled";
  if (globalThis[flag]) return;
  globalThis[flag] = true;

  const tokenOf = () => {
    const el = document.querySelector("[data-stube-csrf]");
    return el && el.getAttribute("data-stube-csrf");
  };

  // event / back — fetch header
  if (typeof globalThis.fetch === "function") {
    const orig = globalThis.fetch;
    globalThis.fetch = function (input, init) {
      try {
        const token = tokenOf();
        if (token) {
          const raw    = typeof input === "string" ? input : (input && input.url) || "";
          const method = ((init && init.method) || (input && input.method) || "GET").toUpperCase();
          const u      = new URL(raw, document.baseURI);
          if (method === "POST" &&
              u.origin === location.origin &&
              /\/(event|back)\//.test(u.pathname)) {
            const headers = new Headers((init && init.headers) ||
                                        (input && input.headers) || undefined);
            headers.set("X-Stube-Csrf", token);
            init = Object.assign({}, init, {headers});
          }
        }
      } catch (_e) { /* never block a request on CSRF wiring */ }
      return orig.call(this, input, init);
    };
  }

  // upload — hidden form field, set just-in-time on submit
  document.addEventListener("submit", (e) => {
    try {
      const form = e.target;
      if (!form || !form.matches ||
          !form.matches('form[enctype="multipart/form-data"]')) return;
      if (!/\/upload\//.test(form.getAttribute("action") || "")) return;
      const token = tokenOf();
      if (!token) return;
      let input = form.querySelector('input[name="_stube_csrf"]');
      if (!input) {
        input = document.createElement("input");
        input.type = "hidden";
        input.name = "_stube_csrf";
        form.appendChild(input);
      }
      input.value = token;
    } catch (_e) { /* never block a submit on CSRF wiring */ }
  }, true);
})();

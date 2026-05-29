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

  // Writes go through Datastar's documented external-write event so we
  // don't depend on any internal runtime handle (Datastar v1 does not
  // expose `globalThis.ds`).  Dotted paths in the signal name are
  // expanded into a nested-shape detail object — `"edit.markdown"`
  // dispatches `{edit: {markdown: <value>}}`, matching Datastar's
  // signal-namespacing convention.
  const expandDottedDetail = (name, value) => {
    const parts = String(name).split(".");
    let detail = value;
    for (let i = parts.length - 1; i >= 0; i--) detail = { [parts[i]]: detail };
    return detail;
  };

  const dispatchSignalPatch = (detail) => {
    if (!detail || typeof detail !== "object") return;
    try {
      document.dispatchEvent(
        new CustomEvent("datastar-signal-patch", { detail })
      );
    } catch (_e) {}
  };

  // Reads remain best-effort against `globalThis.ds` — Datastar v1 does
  // not document a runtime read handle.  Behaviors that need the latest
  // value should read it off the DOM (bound input's `.value`) or POST
  // through `ctx.fetch` and let the server respond with the truth.
  const resolveSignalNode = (name) => {
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

  const buildSignals = () => ({
    get(name) {
      try {
        const sig = resolveSignalNode(name);
        return sig && typeof sig === "object" && "value" in sig ? sig.value : undefined;
      } catch (_e) { return undefined; }
    },
    set(name, value) {
      if (typeof name !== "string" || !name) return;
      dispatchSignalPatch(expandDottedDetail(name, value));
    },
    patch(values) {
      if (!values || typeof values !== "object") return;
      dispatchSignalPatch(values);
    },
  });

  // A small helper so behaviors can POST to a stube event URL the same
  // way `s/on` does, without rebuilding the URL by hand.  `eventUrl` is
  // expected to be the absolute path produced by `s/event-url` on the
  // server side and passed in via `data-stube-arg-*`.
  const buildFetch = () => async (eventUrl, opts) => {
    const init = {
      method: "POST",
      headers: {"Accept": "text/event-stream"},
      ...(opts || {}),
    };
    if (init.body && typeof init.body !== "string" && !(init.body instanceof FormData)) {
      init.body = JSON.stringify(init.body);
      init.headers = {"Content-Type": "application/json", ...init.headers};
    }
    return fetch(eventUrl, init);
  };

  const buildCtx = (el) => {
    const signals = buildSignals();
    return {
      el,
      args: readArgs(el),
      basePath: resolveBasePath(),
      signals,
      // Convenience aliases so behavior code doesn't have to reach
      // into `ctx.signals` for the common write paths.
      setSignal: signals.set,
      patchSignals: signals.patch,
      fetch: buildFetch(),
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

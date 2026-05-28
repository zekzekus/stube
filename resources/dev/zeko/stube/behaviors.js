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

  const currentScript = document.currentScript;
  const basePathFromScript = currentScript?.getAttribute("data-stube-base-path");
  const resolveBasePath = () => {
    if (typeof basePathFromScript === "string") return basePathFromScript;
    const body = document.body;
    if (body && body.hasAttribute("data-stube-base-path")) {
      return body.getAttribute("data-stube-base-path") || "";
    }
    return "";
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

  const buildSignals = () => ({
    get(name) {
      const ds = globalThis.ds;
      try { return ds?.signals?.value?.[name]?.value; }
      catch (_e) { return undefined; }
    },
    set(name, value) {
      const ds = globalThis.ds;
      const sig = ds?.signals?.value?.[name];
      if (sig) sig.value = value;
    },
  });

  const buildCtx = (el) => ({
    el,
    args: readArgs(el),
    basePath: resolveBasePath(),
    signals: buildSignals(),
  });

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

// stube preserve bridge
//
// `data-stube-preserve` means: Datastar may keep morphing the host
// element's attributes, but the host's child nodes are owned by some
// third-party widget and must stay opaque.
//
// Current Datastar exposes `data-ignore-morph`, which skips an entire
// element (attributes and children). This bridge applies that skip only
// for the duration of one morph, then performs the host attribute merge
// itself. The result is "merge my attributes, preserve my subtree".

(() => {
  const installedKey = "__stubePreserveInstalled";
  if (globalThis[installedKey]) return;
  globalThis[installedKey] = true;

  const preserveAttr = "data-stube-preserve";
  const ignoreMorphAttr = "data-ignore-morph";
  const fetchEvent = "datastar-fetch";
  const patchElements = "datastar-patch-elements";
  const originalDispatchEvent = document.dispatchEvent.bind(document);

  const escapeCss = (s) => {
    if (globalThis.CSS && typeof globalThis.CSS.escape === "function") {
      return globalThis.CSS.escape(s);
    }
    return String(s).replace(/[^a-zA-Z0-9_-]/g, "\\$&");
  };

  const preservedWithin = (scope) => {
    if (!scope) return [];
    const out = [];
    if (scope.nodeType === Node.ELEMENT_NODE && scope.hasAttribute(preserveAttr)) {
      out.push(scope);
    }
    if (typeof scope.querySelectorAll === "function") {
      out.push(...scope.querySelectorAll(`[${preserveAttr}]`));
    }
    return out;
  };

  const parseTemplate = (html) => {
    const template = document.createElement("template");
    template.innerHTML = html;
    return template;
  };

  const attrSnapshot = (el) =>
    Array.from(el.attributes).map((attr) => [attr.name, attr.value]);

  const applyAttributes = (oldEl, attrs) => {
    const names = new Set(attrs.map(([name]) => name));

    for (const attr of Array.from(oldEl.attributes)) {
      if (!names.has(attr.name)) oldEl.removeAttribute(attr.name);
    }

    for (const [name, value] of attrs) {
      if (oldEl.getAttribute(name) !== value) {
        oldEl.setAttribute(name, value);
      }
    }
  };

  const findByIdWithin = (scope, id) => {
    if (!scope || !id) return null;
    if (scope.nodeType === Node.ELEMENT_NODE && scope.id === id) return scope;
    return scope.querySelector?.(`#${escapeCss(id)}`) || null;
  };

  const matchOldElement = (oldScope, oldCandidates, used, newEl) => {
    const marker = newEl.getAttribute(preserveAttr);

    if (newEl.id) {
      const oldById = findByIdWithin(oldScope, newEl.id);
      if (oldById && !used.has(oldById) && oldById.getAttribute(preserveAttr) === marker) {
        return oldById;
      }
    }

    return oldCandidates.find((oldEl) =>
      !used.has(oldEl) && oldEl.getAttribute(preserveAttr) === marker
    ) || null;
  };

  const prepareInScopes = (oldScope, newScope) => {
    const incoming = preservedWithin(newScope);
    if (!incoming.length) return [];

    const oldCandidates = preservedWithin(oldScope);
    if (!oldCandidates.length) return [];

    const used = new Set();
    const prepared = [];

    for (const newEl of incoming) {
      const oldEl = matchOldElement(oldScope, oldCandidates, used, newEl);
      if (!oldEl) continue;

      used.add(oldEl);
      prepared.push({oldEl, attrs: attrSnapshot(newEl)});
      oldEl.setAttribute(ignoreMorphAttr, "");
      newEl.setAttribute(ignoreMorphAttr, "");
    }

    return prepared;
  };

  const preparePreservedElements = (argsRaw) => {
    const html = typeof argsRaw?.elements === "string" ? argsRaw.elements : "";
    if (!html.includes(preserveAttr)) return [];

    const mode = (typeof argsRaw.mode === "string" && argsRaw.mode.trim()) || "outer";
    if (mode !== "outer" && mode !== "inner") return [];

    const selector = typeof argsRaw.selector === "string" ? argsRaw.selector.trim() : "";
    const template = parseTemplate(html);
    const fragment = template.content;
    let prepared = [];

    if (mode === "inner") {
      const oldScope = selector ? document.querySelector(selector) : null;
      if (oldScope) prepared = prepareInScopes(oldScope, fragment);
    } else if (selector) {
      const oldScope = document.querySelector(selector);
      if (oldScope) prepared = prepareInScopes(oldScope, fragment);
    } else {
      prepared = Array.from(fragment.children).flatMap((newRoot) => {
        const oldRoot = newRoot.id ? document.getElementById(newRoot.id) : null;
        return oldRoot ? prepareInScopes(oldRoot, newRoot) : [];
      });
    }

    if (prepared.length) {
      argsRaw.elements = template.innerHTML;
    }

    return prepared;
  };

  // Public lifecycle event. Fired on `document` after every successful
  // Datastar `patch-elements` morph driven by an SSE patch. Apps that
  // need to run after a patch lands (scroll/focus restoration, title
  // measurement, third-party widget reflow, optimistic UI cleanup) can
  // hook this instead of inventing their own MutationObserver.
  //
  // `detail` carries:
  //   - selector: the CSS selector Datastar used to find targets
  //                (or null when the patch addressed elements by id)
  //   - mode:     "outer" | "inner" | "before" | "after" | "remove" | ...
  //                (whatever the SSE event carried; absent → null)
  //   - patched:  true (reserved for future flag bits)
  //
  // The event is best-effort: we fire it after the underlying dispatch
  // returns regardless of preserve handling, but never before Datastar
  // has actually applied the morph.
  const dispatchPatched = (argsRaw) => {
    try {
      const detail = {
        selector: typeof argsRaw?.selector === "string" ? argsRaw.selector : null,
        mode:     typeof argsRaw?.mode === "string"     ? argsRaw.mode     : null,
        patched:  true,
      };
      originalDispatchEvent(
        new CustomEvent("stube:patched", {detail, bubbles: false, cancelable: false}),
      );
    } catch (err) {
      try { console.error("stube:patched dispatch threw:", err); }
      catch (_e) { /* console may be missing in test envs */ }
    }
  };

  document.dispatchEvent = (evt) => {
    if (
      evt instanceof CustomEvent &&
      evt.type === fetchEvent &&
      evt.detail?.type === patchElements
    ) {
      const argsRaw = evt.detail.argsRaw;
      const prepared = preparePreservedElements(argsRaw);
      try {
        return originalDispatchEvent(evt);
      } finally {
        for (const {oldEl, attrs} of prepared) {
          if (oldEl.isConnected) applyAttributes(oldEl, attrs);
        }
        dispatchPatched(argsRaw);
      }
    }
    return originalDispatchEvent(evt);
  };

  // ---------------------------------------------------------------
  // s/on-unmount — fire `data-stube-on-unmount` exactly once when a
  // host element detaches from the DOM.
  //
  // We use one document-wide MutationObserver. On each removal it
  // walks the removed subtree, fires any pending unmount expressions,
  // and tags the element so a subsequent re-insert+remove can't
  // double-fire (the morph path can briefly detach + reattach a
  // preserved element during Idiomorph's swap dance; we want exactly
  // one teardown across the *real* removal).
  // ---------------------------------------------------------------

  const unmountAttr = "data-stube-on-unmount";
  const unmountFiredKey = "__stubeUnmountFired";

  const fireUnmountFor = (el) => {
    if (!el || el.nodeType !== Node.ELEMENT_NODE) return;
    if (!el.hasAttribute(unmountAttr)) return;
    if (el[unmountFiredKey]) return;
    el[unmountFiredKey] = true;
    const expr = el.getAttribute(unmountAttr);
    if (!expr) return;
    try {
      // Match on-mount's `el`-bound IIFE shape.
      new Function("el", expr)(el);
    } catch (err) {
      try { console.error("stube on-unmount expression threw:", err); }
      catch (_e) { /* console may be missing in test envs */ }
    }
  };

  const walkAndFire = (root) => {
    if (!root) return;
    fireUnmountFor(root);
    if (typeof root.querySelectorAll === "function") {
      for (const el of root.querySelectorAll(`[${unmountAttr}]`)) {
        fireUnmountFor(el);
      }
    }
  };

  const installUnmountObserver = () => {
    if (!document.body) {
      // Body not parsed yet — defer.
      document.addEventListener("DOMContentLoaded", installUnmountObserver, {once: true});
      return;
    }
    const observer = new MutationObserver((records) => {
      for (const r of records) {
        if (r.type !== "childList") continue;
        for (const node of r.removedNodes) {
          // Allow the morph dance to settle for a microtask before
          // declaring the node truly gone — Idiomorph may detach and
          // reattach the same element during a swap.  If the node is
          // still connected after the microtask, it was a transient
          // move, not a real removal.
          queueMicrotask(() => {
            if (node && node.nodeType === Node.ELEMENT_NODE && !node.isConnected) {
              walkAndFire(node);
            }
          });
        }
      }
    });
    observer.observe(document.body, {childList: true, subtree: true});
  };

  installUnmountObserver();
})();

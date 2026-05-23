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

  document.dispatchEvent = (evt) => {
    if (
      evt instanceof CustomEvent &&
      evt.type === fetchEvent &&
      evt.detail?.type === patchElements
    ) {
      const prepared = preparePreservedElements(evt.detail.argsRaw);
      try {
        return originalDispatchEvent(evt);
      } finally {
        for (const {oldEl, attrs} of prepared) {
          if (oldEl.isConnected) applyAttributes(oldEl, attrs);
        }
      }
    }
    return originalDispatchEvent(evt);
  };
})();

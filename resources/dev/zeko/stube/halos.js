// stube halos — dev-only overlay. Served as /stube/halos.js when the
// server was started with :halos? true.
//
// Lifecycle:
//   - The shell injects this script + data-stube-cid on body when the
//     server is in dev mode. The overlay starts in OFF state.
//   - A floating pill in the bottom-right shows the current state:
//       OFF    — server is in dev mode but this conv has halos? false.
//                Click to POST /stube/halos/<cid>/enable, then the
//                kernel re-emits the top frame decorated with halo
//                data-attrs and we flip to ON.
//       ON     — outlines + hover labels are visible, side panel open.
//                Click to HIDE.
//       HIDDEN — overlay temporarily hidden but still active. Click
//                pill (or hit `?`) to show again.
//
// Layout safety:
//   The overlay never mutates the inline styles of component elements
//   or appends children to them. Outlines come from CSS (`outline`
//   doesn't affect layout). Labels live in a sibling overlay layer
//   positioned via getBoundingClientRect, so they survive Datastar
//   morphs and can't perturb flex/grid layouts of the real UI.
(() => {
  const PANEL_ID   = "stube-halos-panel";
  const PILL_ID    = "stube-halos-pill";
  const OVERLAY_ID = "stube-halos-overlay";
  const CID  = (document.body && document.body.dataset.stubeCid) || null;
  const BASE = (document.body && document.body.dataset.stubeBasePath) || "";
  if (!CID) return;

  const haloPath = (suffix) => `${BASE}/stube/halos/${CID}${suffix}`;

  const STYLE = `
    /* ─── component outlines (CSS-only; no layout impact) ──────── */
    body.stube-halos-on [data-stube-iid] {
      outline: 1px solid hsl(var(--stube-hue, 280) 70% 55% / 0.55);
      outline-offset: -1px;
    }
    body.stube-halos-on [data-stube-iid].stube-halo-hovered {
      outline: 1px solid hsl(var(--stube-hue, 280) 80% 60% / 0.95);
    }
    body.stube-halos-on [data-stube-iid].stube-halo-selected {
      outline: 2px solid hsl(var(--stube-hue, 280) 90% 65%);
      outline-offset: -2px;
      box-shadow: 0 0 0 2px hsl(var(--stube-hue, 280) 90% 65% / 0.25);
    }

    /* ─── overlay layer for labels (own stacking context) ──────── */
    #${OVERLAY_ID} {
      position: fixed;
      top: 0; left: 0; right: 0; bottom: 0;
      pointer-events: none;
      z-index: 9997;
      overflow: hidden;
    }
    body:not(.stube-halos-on) #${OVERLAY_ID} { display: none; }

    .stube-halo-tag {
      position: absolute;
      background: hsl(var(--stube-hue, 280) 55% 30%);
      color: #fff;
      font: 10px/14px ui-monospace, SFMono-Regular, Menlo, monospace;
      padding: 0 5px;
      height: 14px;
      cursor: pointer;
      user-select: none;
      pointer-events: auto;
      border-radius: 2px 2px 0 0;
      white-space: nowrap;
      box-shadow: 0 1px 2px rgba(0,0,0,0.3);
    }
    .stube-halo-tag.selected {
      background: hsl(var(--stube-hue, 280) 55% 25%);
      font-weight: bold;
      box-shadow: 0 0 0 1px hsl(var(--stube-hue, 280) 90% 65%) inset,
                  0 1px 4px rgba(0,0,0,0.4);
    }
    .stube-halo-tag .iid {
      color: hsl(var(--stube-hue, 280) 60% 88%);
      margin-left: 4px;
    }

    /* ─── side panel ────────────────────────────────────────────── */
    #${PANEL_ID} {
      position: fixed;
      top: 0; right: 0; bottom: 0;
      width: 420px;
      background: #1e1e2e;
      color: #e0e0e0;
      font: 12px/1.45 ui-monospace, SFMono-Regular, Menlo, monospace;
      z-index: 9999;
      display: flex;
      flex-direction: column;
      box-shadow: -2px 0 10px rgba(0,0,0,0.35);
      transform: translateX(0);
      transition: transform 120ms ease-out;
    }
    body:not(.stube-halos-on) #${PANEL_ID},
    body.stube-halos-panel-hidden #${PANEL_ID} {
      transform: translateX(100%);
    }
    #${PANEL_ID} header {
      padding: 6px 8px;
      background: #2a2a3e;
      display: flex;
      gap: 4px;
      align-items: center;
      border-bottom: 1px solid #3a3a4e;
      flex: 0 0 auto;
    }
    #${PANEL_ID} header button {
      background: #3a3a4e;
      color: #eee;
      border: 0;
      padding: 3px 8px;
      cursor: pointer;
      font: inherit;
      border-radius: 3px;
    }
    #${PANEL_ID} header button:hover { background: #4a4a5e; }
    #${PANEL_ID} header button.active { background: #9333ea; }
    #${PANEL_ID} .stube-halo-body {
      padding: 8px;
      overflow: auto;
      flex: 1 1 auto;
    }
    #${PANEL_ID} pre {
      margin: 0;
      white-space: pre-wrap;
      word-break: break-word;
      color: #cde2ff;
    }
    #${PANEL_ID} table { border-collapse: collapse; }
    #${PANEL_ID} td { padding: 1px 0; vertical-align: top; }
    #${PANEL_ID} a { color: #93c5fd; cursor: pointer; text-decoration: none; }
    #${PANEL_ID} a:hover { text-decoration: underline; }
    #${PANEL_ID} a.stube-halo-selected { color: #ffd5fb; font-weight: bold; }

    /* ─── floating pill ─────────────────────────────────────────── */
    #${PILL_ID} {
      position: fixed;
      bottom: 12px;
      right: 12px;
      z-index: 10000;
      background: #1e1e2e;
      color: #e0e0e0;
      font: 12px/1 ui-monospace, SFMono-Regular, Menlo, monospace;
      padding: 6px 10px;
      border: 1px solid #3a3a4e;
      border-radius: 14px;
      cursor: pointer;
      user-select: none;
      box-shadow: 0 2px 8px rgba(0,0,0,0.4);
      display: flex;
      gap: 6px;
      align-items: center;
    }
    #${PILL_ID}:hover { border-color: #9333ea; }
    #${PILL_ID} .dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: #555;
    }
    #${PILL_ID}.on    .dot { background: #4ade80; box-shadow: 0 0 6px #4ade80; }
    #${PILL_ID}.hidden .dot { background: #fbbf24; }
    #${PILL_ID} .panel-btn {
      margin-left: 6px;
      padding: 2px 6px;
      background: #3a3a4e;
      border-radius: 8px;
      font-size: 10px;
    }
    #${PILL_ID} .panel-btn:hover { background: #4a4a5e; }
  `;
  const styleEl = document.createElement("style");
  styleEl.textContent = STYLE;
  document.head.appendChild(styleEl);

  // ─── DOM scaffolding ────────────────────────────────────────────
  const overlay = document.createElement("div");
  overlay.id = OVERLAY_ID;
  document.body.appendChild(overlay);

  const hoverTag = document.createElement("div");
  hoverTag.className = "stube-halo-tag";
  hoverTag.style.display = "none";
  overlay.appendChild(hoverTag);

  const selectedTag = document.createElement("div");
  selectedTag.className = "stube-halo-tag selected";
  selectedTag.style.display = "none";
  overlay.appendChild(selectedTag);

  const panel = document.createElement("aside");
  panel.id = PANEL_ID;
  panel.innerHTML = '<div class="stube-halo-body">…</div>';
  document.body.appendChild(panel);

  const pill = document.createElement("div");
  pill.id = PILL_ID;
  document.body.appendChild(pill);

  // ─── state ──────────────────────────────────────────────────────
  // state: "off" | "on" | "hidden"   (hidden = on but overlay collapsed)
  let state = "off";
  let currentIid = null;
  let currentTab = "tree";
  let hoveredIid = null;

  function renderPill() {
    pill.className = state === "on" ? "on" : state;
    const label =
      state === "off"    ? "halos: enable"
    : state === "on"     ? "halos: on"
    :                      "halos: hidden";
    pill.innerHTML = `<span class="dot"></span><span>${label}</span>` +
      (state !== "off"
        ? '<span class="panel-btn" data-halo-panel-toggle="1">panel</span>'
        : "");
  }

  // ─── hue per iid (consistent label colour across the session) ──
  function hueFor(iid) {
    let h = 0;
    for (let i = 0; i < iid.length; i++) h = (h * 31 + iid.charCodeAt(i)) | 0;
    return Math.abs(h) % 360;
  }

  function elFor(iid) {
    return iid ? document.querySelector(`[data-stube-iid="${CSS.escape(iid)}"]`) : null;
  }

  // ─── tag positioning ────────────────────────────────────────────
  function positionTag(tag, el) {
    if (!el || !el.isConnected) {
      tag.style.display = "none";
      return;
    }
    const rect = el.getBoundingClientRect();
    if (rect.width === 0 && rect.height === 0) {
      tag.style.display = "none";
      return;
    }
    const iid  = el.dataset.stubeIid;
    const type = el.dataset.stubeType;
    tag.innerHTML = `${type} <span class="iid">${iid}</span>`;
    tag.style.setProperty("--stube-hue", String(hueFor(iid)));
    // Sit just above the rect; clamp to viewport top.
    const top  = Math.max(0, rect.top - 14);
    const left = Math.max(0, rect.left);
    tag.style.top  = `${top}px`;
    tag.style.left = `${left}px`;
    tag.dataset.haloIid = iid;
    tag.style.display = "block";
  }

  function refreshTags() {
    if (state !== "on") return;
    positionTag(hoverTag, hoveredIid && hoveredIid !== currentIid ? elFor(hoveredIid) : null);
    positionTag(selectedTag, currentIid ? elFor(currentIid) : null);
  }

  // ─── set custom hue per element via CSS variable (no inline pos) ─
  function decorateHues() {
    document.querySelectorAll("[data-stube-iid]:not([data-stube-hued])").forEach(el => {
      el.dataset.stubeHued = "1";
      el.style.setProperty("--stube-hue", String(hueFor(el.dataset.stubeIid)));
    });
  }

  function applySelected() {
    document.querySelectorAll("[data-stube-iid].stube-halo-selected")
      .forEach(el => { if (el.dataset.stubeIid !== currentIid) el.classList.remove("stube-halo-selected"); });
    const sel = elFor(currentIid);
    if (sel) {
      sel.classList.add("stube-halo-selected");
      sel.scrollIntoView({ block: "nearest", behavior: "smooth" });
    }
    // mirror in panel
    panel.querySelectorAll("a.stube-halo-selected").forEach(a => a.classList.remove("stube-halo-selected"));
    if (currentIid) {
      const a = panel.querySelector(`a[data-halo-iid="${CSS.escape(currentIid)}"]`);
      if (a) a.classList.add("stube-halo-selected");
    }
    refreshTags();
  }

  function applyHovered(iid) {
    if (hoveredIid === iid) return;
    if (hoveredIid) {
      const prev = elFor(hoveredIid);
      if (prev) prev.classList.remove("stube-halo-hovered");
    }
    hoveredIid = iid;
    if (iid) {
      const el = elFor(iid);
      if (el) el.classList.add("stube-halo-hovered");
    }
    refreshTags();
  }

  // ─── panel fetch / state ────────────────────────────────────────
  async function loadPanel(iid, tab) {
    if (state === "off") return;
    if (iid !== null && iid !== undefined) currentIid = iid;
    if (tab !== null && tab !== undefined) currentTab = tab;
    const params = new URLSearchParams();
    if (currentIid) params.set("iid", currentIid);
    if (currentTab) params.set("tab", currentTab);
    try {
      const res = await fetch(haloPath(`/panel?${params.toString()}`),
                              { credentials: "same-origin" });
      if (res.status === 410) {
        setState("off");
        return;
      }
      panel.innerHTML = await res.text();
      applySelected();
    } catch (err) {
      panel.innerHTML = `<div class="stube-halo-body">panel fetch failed: ${err}</div>`;
    }
  }

  async function enableHalos() {
    try {
      const res = await fetch(haloPath("/enable"),
                              { method: "POST", credentials: "same-origin" });
      if (!res.ok) {
        console.warn("stube halos: enable failed", res.status);
        return;
      }
      setState("on");
      setTimeout(() => loadPanel(null, null), 100);
    } catch (err) {
      console.warn("stube halos: enable threw", err);
    }
  }

  function setState(s) {
    state = s;
    document.body.classList.toggle("stube-halos-on", s === "on");
    document.body.classList.toggle("stube-halos-panel-hidden", s === "hidden");
    renderPill();
    if (s === "on") {
      decorateHues();
      refreshTags();
      loadPanel(null, null);
    } else {
      hoverTag.style.display = "none";
      selectedTag.style.display = "none";
    }
  }

  // ─── event delegation ───────────────────────────────────────────
  document.body.addEventListener("click", e => {
    const t = e.target.closest("[data-halo-iid], [data-halo-tab], [data-halo-refresh], [data-halo-panel-toggle], #" + PILL_ID);
    if (!t) return;

    if (t.id === PILL_ID || t.parentElement?.id === PILL_ID) {
      if (e.target.closest("[data-halo-panel-toggle]")) {
        e.preventDefault();
        if (state === "on") setState("hidden");
        else if (state === "hidden") setState("on");
        return;
      }
      e.preventDefault();
      if (state === "off") { enableHalos(); return; }
      if (state === "on")  { setState("hidden"); return; }
      setState("on");
      return;
    }
    if (t.dataset.haloIid) {
      e.preventDefault();
      e.stopPropagation();
      loadPanel(t.dataset.haloIid, null);
    } else if (t.dataset.haloTab) {
      e.preventDefault();
      loadPanel(null, t.dataset.haloTab);
    } else if (t.dataset.haloRefresh) {
      e.preventDefault();
      loadPanel(null, null);
    }
  });

  // ─── hover tracking ─────────────────────────────────────────────
  // mouseover bubbles; closest() picks the innermost halo'd ancestor.
  document.body.addEventListener("mouseover", e => {
    if (state !== "on") return;
    if (e.target.closest("#" + PANEL_ID) ||
        e.target.closest("#" + PILL_ID)) {
      applyHovered(null);
      return;
    }
    const el = e.target.closest("[data-stube-iid]");
    applyHovered(el ? el.dataset.stubeIid : null);
  });

  // ─── DOM observation ────────────────────────────────────────────
  let refreshTimer = null;
  function scheduleRefresh() {
    if (refreshTimer) return;
    refreshTimer = setTimeout(() => {
      refreshTimer = null;
      decorateHues();
      refreshTags();
    }, 30);
  }
  const obs = new MutationObserver(scheduleRefresh);
  obs.observe(document.body, { childList: true, subtree: true, attributes: true,
                               attributeFilter: ["data-stube-iid", "data-stube-type"] });

  // Tags float over real elements via fixed coords, so they need to
  // follow scroll/resize. capture:true catches scrolls inside any
  // nested scrollable region too.
  window.addEventListener("scroll", refreshTags, { passive: true, capture: true });
  window.addEventListener("resize", refreshTags, { passive: true });

  // ─── keyboard toggle ────────────────────────────────────────────
  window.addEventListener("keydown", e => {
    if (e.key !== "?") return;
    const t = e.target;
    if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
    e.preventDefault();
    if (state === "off")    { enableHalos(); return; }
    if (state === "on")     setState("hidden");
    else                    setState("on");
  });

  // ─── boot ───────────────────────────────────────────────────────
  (async () => {
    const res = await fetch(haloPath("/panel"), { credentials: "same-origin" });
    if (res.ok) setState("on");
    else        { renderPill(); }
    decorateHues();
  })();
})();

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
(() => {
  const PANEL_ID = "stube-halos-panel";
  const PILL_ID  = "stube-halos-pill";
  const CID = (document.body && document.body.dataset.stubeCid) || null;
  if (!CID) return;

  const STYLE = `
    /* ─── component outlines ────────────────────────────────────── */
    body.stube-halos-on [data-stube-iid] {
      outline: 1px solid hsl(var(--stube-hue, 280) 70% 55% / 0.55);
      outline-offset: -1px;
    }
    body.stube-halos-on [data-stube-iid]:hover {
      outline: 1px solid hsl(var(--stube-hue, 280) 80% 60% / 0.95);
    }
    body.stube-halos-on [data-stube-iid].stube-halo-selected {
      outline: 2px solid hsl(var(--stube-hue, 280) 90% 65%);
      outline-offset: -2px;
      box-shadow: 0 0 0 2px hsl(var(--stube-hue, 280) 90% 65% / 0.25);
    }

    /* ─── hover label (sits above the element, never covers content) */
    .stube-halo-tag {
      position: absolute;
      top: -16px;
      left: -1px;
      background: hsl(var(--stube-hue, 280) 70% 45%);
      color: #fff;
      font: 10px/14px ui-monospace, SFMono-Regular, Menlo, monospace;
      padding: 0 5px;
      height: 14px;
      z-index: 9998;
      cursor: pointer;
      user-select: none;
      pointer-events: auto;
      border-radius: 2px 2px 0 0;
      white-space: nowrap;
      opacity: 0;
      transition: opacity 80ms ease-in;
    }
    body.stube-halos-on [data-stube-iid]:hover > .stube-halo-tag,
    body.stube-halos-on [data-stube-iid].stube-halo-selected > .stube-halo-tag {
      opacity: 1;
    }
    .stube-halo-tag .iid {
      color: hsl(var(--stube-hue, 280) 30% 90%);
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

  // ─── outline / label decoration ─────────────────────────────────
  function hueFor(iid) {
    // tiny string hash → 0..359
    let h = 0;
    for (let i = 0; i < iid.length; i++) h = (h * 31 + iid.charCodeAt(i)) | 0;
    return Math.abs(h) % 360;
  }

  function decorateLabels() {
    document.querySelectorAll("[data-stube-iid]:not([data-stube-haloed])").forEach(el => {
      el.dataset.stubeHaloed = "1";
      if (getComputedStyle(el).position === "static") el.style.position = "relative";
      el.style.setProperty("--stube-hue", String(hueFor(el.dataset.stubeIid)));
      const tag = document.createElement("span");
      tag.className = "stube-halo-tag";
      tag.innerHTML =
        el.dataset.stubeType +
        ` <span class="iid">${el.dataset.stubeIid}</span>`;
      tag.addEventListener("click", e => {
        e.stopPropagation();
        e.preventDefault();
        loadPanel(el.dataset.stubeIid, null);
      });
      el.appendChild(tag);
    });
    applySelected();
  }

  function applySelected() {
    document.querySelectorAll("[data-stube-iid].stube-halo-selected")
      .forEach(el => { if (el.dataset.stubeIid !== currentIid) el.classList.remove("stube-halo-selected"); });
    if (currentIid) {
      const el = document.querySelector(`[data-stube-iid="${CSS.escape(currentIid)}"]`);
      if (el) {
        el.classList.add("stube-halo-selected");
        el.scrollIntoView({ block: "nearest", behavior: "smooth" });
      }
    }
    // mirror in panel
    panel.querySelectorAll("a.stube-halo-selected").forEach(a => a.classList.remove("stube-halo-selected"));
    if (currentIid) {
      const a = panel.querySelector(`a[data-halo-iid="${CSS.escape(currentIid)}"]`);
      if (a) a.classList.add("stube-halo-selected");
    }
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
      const res = await fetch(`/stube/halos/${CID}/panel?` + params.toString(),
                              { credentials: "same-origin" });
      if (res.status === 410) {
        // Conv has halos? off — flip our local state.
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
      const res = await fetch(`/stube/halos/${CID}/enable`,
                              { method: "POST", credentials: "same-origin" });
      if (!res.ok) {
        console.warn("stube halos: enable failed", res.status);
        return;
      }
      // The server pushes the decorated frame over SSE.  Once Datastar
      // morphs it in, MutationObserver wakes and `decorateLabels` runs.
      setState("on");
      // Give SSE a moment to land the new frame before fetching panel.
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
    if (s === "on") loadPanel(null, null);
  }

  // ─── event delegation ───────────────────────────────────────────
  // One listener on body covers pill clicks, panel buttons, and any
  // halo links rendered into the panel.  Robust against innerHTML
  // replacement on every panel fetch.
  document.body.addEventListener("click", e => {
    const t = e.target.closest("[data-halo-iid], [data-halo-tab], [data-halo-refresh], [data-halo-panel-toggle], #" + PILL_ID);
    if (!t) return;

    if (t.id === PILL_ID || t.parentElement?.id === PILL_ID) {
      // Outer pill click — but ignore if the inner panel-btn handled it.
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
      loadPanel(t.dataset.haloIid, null);
    } else if (t.dataset.haloTab) {
      e.preventDefault();
      loadPanel(null, t.dataset.haloTab);
    } else if (t.dataset.haloRefresh) {
      e.preventDefault();
      loadPanel(null, null);
    }
  });

  // ─── DOM observation ────────────────────────────────────────────
  let labelTimer = null;
  const obs = new MutationObserver(() => {
    if (labelTimer) return;
    labelTimer = setTimeout(() => { labelTimer = null; decorateLabels(); }, 30);
  });
  obs.observe(document.body, { childList: true, subtree: true });

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
  // Probe the panel endpoint to discover the conv's initial state.
  (async () => {
    const res = await fetch(`/stube/halos/${CID}/panel`, { credentials: "same-origin" });
    if (res.ok) setState("on");
    else        { renderPill(); /* state stays "off" */ }
    decorateLabels();
  })();
})();

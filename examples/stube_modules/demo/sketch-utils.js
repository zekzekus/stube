// demo/sketch-utils — global helpers for the /sketch demo
//
// Declared by `:modules ["demo/sketch-utils"]` on the
// `:demo/sketch` component.  `head-tags` emits one
// <script type="module" src="/<base>/modules/demo/sketch-utils.js">
// per distinct entry across the whole registry, so even if many
// instances of the component appear, this script loads once.
//
// Anything a behavior needs to share across instances or share with
// the rest of the page goes here.  In this demo we:
//
//   * publish a tiny strokes log under globalThis.demoSketchUtils;
//   * register a `c` keyboard shortcut that finds the visible Clear
//     button and clicks it — the click is routed through stube's
//     normal `s/on` event channel, no special framework cooperation
//     required.

(() => {
  if (globalThis.demoSketchUtils) return;

  const history = [];

  globalThis.demoSketchUtils = {
    history,
    recordStroke(colour, stroke) {
      history.push({colour, stroke, at: Date.now()});
      if (history.length > 256) history.splice(0, history.length - 256);
    },
  };

  document.addEventListener("keydown", (evt) => {
    if (evt.key !== "c" && evt.key !== "C") return;
    const target = evt.target;
    if (target && (target.tagName === "INPUT" || target.isContentEditable)) return;
    const clear = document.querySelector('[data-sketch-clear="true"]');
    if (clear) {
      evt.preventDefault();
      clear.click();
    }
  });

  try { console.info("demo/sketch-utils module loaded"); }
  catch (_e) {}
})();

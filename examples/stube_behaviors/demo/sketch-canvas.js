// demo/sketch-canvas — behavior for /sketch
//
// Owns the live `<canvas>` element: pointer listeners, drawing state,
// clear-on-cue.  The server pushes the current palette / stroke /
// clear-seq down as `data-stube-arg-*` attributes; `ctx.args` is the
// decoded view of those.
//
// Loaded by `behaviors.js` on first sighting via
//   import("/<base-path>/behaviors/demo/sketch-canvas.js")
//
// Pairs with `s/preserve self :pad` on the host — Datastar may keep
// merging the host's attributes but leaves the canvas DOM node alone,
// so the user's drawing survives every server re-render.

const STATE = Symbol("demo/sketch state");

const colourOf = (ctx) => ctx.args.colour || "#0f172a";
const strokeOf = (ctx) => Number(ctx.args.stroke) || 4;

// Allow the behavior to reach into the global module's helper if the
// :modules entry has loaded.  Missing helper = no-op; the behavior
// still draws fine.
const utils = () => globalThis.demoSketchUtils;

function ensureCanvas(el) {
  return el.querySelector("canvas");
}

function readPointer(canvas, evt) {
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const scaleY = canvas.height / rect.height;
  return {
    x: (evt.clientX - rect.left) * scaleX,
    y: (evt.clientY - rect.top) * scaleY,
  };
}

function clearCanvas(canvas) {
  const c2d = canvas.getContext("2d");
  c2d.clearRect(0, 0, canvas.width, canvas.height);
}

// Behaviors talk back to the server with plain `fetch` against an
// event URL the component handed down in `ctx.args`.  Body is empty
// — `event-url` already encodes the iid + event name — and the
// response is a 204 with the SSE patches flowing in on the existing
// stream.  Errors are swallowed to keep one offline blip from killing
// the pen.
function postStrokeEnd(ctx) {
  const url = ctx.args.strokeUrl;
  if (!url) return;
  fetch(url, {method: "POST"}).catch(() => {});
}

export default {
  mount(el, ctx) {
    const canvas = ensureCanvas(el);
    if (!canvas) return;

    const c2d = canvas.getContext("2d");
    const state = {
      drawing: false,
      colour: colourOf(ctx),
      stroke: strokeOf(ctx),
      lastClearSeq: ctx.args.clearSeq || "0",
    };
    el[STATE] = state;

    const onDown = (evt) => {
      evt.preventDefault();
      state.drawing = true;
      const {x, y} = readPointer(canvas, evt);
      c2d.beginPath();
      c2d.moveTo(x, y);
      c2d.lineCap = "round";
      c2d.lineJoin = "round";
      c2d.strokeStyle = state.colour;
      c2d.lineWidth = state.stroke;
    };

    const onMove = (evt) => {
      if (!state.drawing) return;
      const {x, y} = readPointer(canvas, evt);
      c2d.lineTo(x, y);
      c2d.stroke();
    };

    const onUp = () => {
      if (!state.drawing) return;
      state.drawing = false;
      utils()?.recordStroke?.(state.colour, state.stroke);
      postStrokeEnd(ctx);
    };

    canvas.addEventListener("pointerdown", onDown);
    canvas.addEventListener("pointermove", onMove);
    canvas.addEventListener("pointerup", onUp);
    canvas.addEventListener("pointerleave", onUp);

    state.listeners = [
      ["pointerdown", onDown],
      ["pointermove", onMove],
      ["pointerup",   onUp],
      ["pointerleave", onUp],
    ];

    try { console.info("demo/sketch-canvas mounted", state); }
    catch (_e) {}
  },

  patched(el, ctx) {
    const state = el[STATE];
    if (!state) return;
    state.colour = colourOf(ctx);
    state.stroke = strokeOf(ctx);

    const seq = ctx.args.clearSeq || "0";
    if (seq !== state.lastClearSeq) {
      state.lastClearSeq = seq;
      const canvas = ensureCanvas(el);
      if (canvas) clearCanvas(canvas);
    }
  },

  unmount(el) {
    const state = el[STATE];
    if (!state) return;
    const canvas = ensureCanvas(el);
    if (canvas && state.listeners) {
      for (const [type, fn] of state.listeners) {
        canvas.removeEventListener(type, fn);
      }
    }
    delete el[STATE];
    try { console.info("demo/sketch-canvas unmounted"); }
    catch (_e) {}
  },
};

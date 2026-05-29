// demo/signal-mirror-write — behavior for /signal-mirror.
//
// Smallest possible exercise of `ctx.setSignal`: write a value through
// the bridge's data-bind seam at mount time and confirm the bound
// `<p data-text="$x">` reflects it on the next frame.
//
// Loaded by `behaviors.js` on first sighting via
//   import("/<base-path>/behaviors/demo/signal-mirror-write.js")

export default {
  mount(_el, ctx) {
    ctx.setSignal("x", "hello");
  },
};

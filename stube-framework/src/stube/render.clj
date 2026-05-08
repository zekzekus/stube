(ns stube.render
  "Hiccup → HTML rendering and the small DSL for Datastar attributes.

  Two responsibilities live here, deliberately kept apart from the kernel:

  1. **Serialise hiccup to HTML** with [Chassis](https://github.com/onionpancakes/chassis).
     The kernel works with hiccup data structures all the way through;
     they are only stringified at the very edge, just before
     `patch-elements!` writes to the wire.  This keeps everything before
     the wire pure, diff-able, and REPL-inspectable.

  2. **Generate Datastar attribute fragments** — `on`, `bind` — that
     tag a piece of UI with the wiring that lets the client post events
     back to the right conversation and instance.

  The cid only exists at request time, so the helpers consult a dynamic
  var bound by the http layer for the duration of a render."
  (:require [dev.onionpancakes.chassis.core :as chassis]))

;; ---------------------------------------------------------------------------
;; Render-time context
;; ---------------------------------------------------------------------------

(def ^:dynamic *cid*
  "The conversation id of the request currently being served.  Bound by
  the http layer around every render so attribute helpers can build URLs
  pointing at the right SSE endpoint."
  nil)

(defn ^String html
  "Render hiccup `tree` to an HTML string."
  [tree]
  (chassis/html tree))

;; ---------------------------------------------------------------------------
;; Datastar attribute helpers
;; ---------------------------------------------------------------------------
;;
;; These produce small attribute maps that callers `merge` into their
;; hiccup attribute map.  Returning a map (instead of mutating one) keeps
;; them composable with whatever else the user wants on the same element.

(defn- require-cid! []
  (or *cid*
      (throw (ex-info "stube.render/*cid* is unbound; cannot build event URL"
                      {}))))

(defn event-url
  "URL the browser POSTs to for an event.  Public so user code can build
  custom Datastar expressions that target the same endpoint."
  [iid event]
  (str "/conv/" (require-cid!) "/" iid "/" (name event)))

(defn on
  "Return an attribute map that wires `event` (e.g. `:submit`,
  `:click`) on the surrounding element to the stube event endpoint.

  The instance id and event name are encoded in the URL path itself —
  this avoids using a Datastar signal to carry routing metadata, since
  Datastar treats any signal whose name starts with `_` as
  client-local and never sends it to the server.  Other signals
  (`data-bind:foo`, etc.) still ship along automatically as the request
  body.

  Datastar's `data-on:submit` listener calls `preventDefault` on form
  submits automatically (see the [data-on
  reference](https://data-star.dev/reference/attributes#data-on)), so a
  `<form>` submit does not trigger a full-page reload.

  Usage:

      [:form   (s/on self :submit) …]
      [:button (s/on self :click)  …]"
  [self event]
  (let [iid    (or (:instance/id self)
                   (throw (ex-info "stube.render/on requires an instance map"
                                   {:got self})))
        ev     (name event)
        ;; Datastar registers DOM event listeners with the colon form
        ;; `data-on:<event>`.  The dash form `data-on-<event>` is
        ;; reserved for built-in pseudo-events like `data-on-intersect`
        ;; and would silently be ignored for an arbitrary DOM event.
        attr-k (keyword (str "data-on:" ev))
        expr   (str "@post('" (event-url iid ev) "')")]
    {attr-k expr}))

(defn bind
  "Return an attribute map that two-way binds the named signal to the
  current element.

      [:input (merge {:name \"answer\"} (s/bind :answer))]

  Datastar's signal-defining attributes use the colon form
  (`data-bind:foo`); the dash form would not be recognised."
  [signal]
  {(keyword (str "data-bind:" (name signal))) true})

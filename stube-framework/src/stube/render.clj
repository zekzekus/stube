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

(def ^:dynamic *conv*
  "The conversation being rendered, bound by the kernel for the duration
  of one `render-frame` call.  [[render-slot]] consults it to look up
  embedded children by id.

  Two-way bindings (`s/bind`) and event hooks (`s/on`) only need the
  cid; only slot rendering needs the conversation, hence the separate
  vars."
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
  "Return an attribute map that wires a real DOM event on the
  surrounding element to a server-side stube event.

  Two arities:

      (on self :submit)            ;; DOM `submit`  → POST .../submit
      (on self :click :as :inc)    ;; DOM `click`   → POST .../inc

  The first form is the common case where the DOM event name and the
  route name happen to be the same (most often `:submit` on a form,
  `:input` on a text field).  The second form is the right one for any
  click-triggered action: a button has no `inc` event of its own, only
  `click`, so we need to listen on `click` and route to `inc`
  separately.

  Datastar registers listeners under the colon form
  (`data-on:<event>`); the dash form `data-on-<event>` is reserved for
  built-in pseudo-events (`data-on-intersect`, …) and would be silently
  ignored.  `data-on:submit` automatically calls `preventDefault`, so
  forms never trigger a full-page reload.

  The instance id and route event live in the URL path itself —
  Datastar still ships every other signal as the request body, so
  two-way bindings (`s/bind`) keep working unchanged.

  Usage:

      [:form   (s/on self :submit) …]
      [:button (s/on self :click :as :inc) \"+\"]
      [:button (s/on self :click :as :dec) \"−\"]"
  ([self dom-event]
   (on self dom-event :as dom-event))
  ([self dom-event as-kw route-event]
   (when-not (= :as as-kw)
     (throw (ex-info "stube.render/on: 4-arity expects :as as the third argument"
                     {:got as-kw})))
   (let [iid (or (:instance/id self)
                 (throw (ex-info "stube.render/on requires an instance map"
                                 {:got self})))
         attr-k (keyword (str "data-on:" (name dom-event)))
         expr   (str "@post('" (event-url iid (name route-event)) "')")]
     {attr-k expr})))

(defn bind
  "Return an attribute map that two-way binds the named signal to the
  current element.

      [:input (merge {:name \"answer\"} (s/bind :answer))]

  Datastar's signal-defining attributes use the colon form
  (`data-bind:foo`); the dash form would not be recognised."
  [signal]
  {(keyword (str "data-bind:" (name signal))) true})

;; ---------------------------------------------------------------------------
;; Slots: rendering an embedded child inline
;; ---------------------------------------------------------------------------

(defn render-slot
  "Inline the hiccup of an embedded child.  Inside a parent's `:render`,

      [:section (s/render-slot self :slot/header)]

  expands to whatever the `:ui/site-header` instance currently renders,
  and Chassis serialises both layers in one pass.  No HTML escaping is
  needed because we hand back hiccup, not a pre-rendered string.

  The lookup arrow is:

      slot-key  →  (:instance/children self)
                →  child instance id
                →  (instance *conv* child-iid)
                →  child component definition's `:render`

  Throws if `*conv*` is unbound or the slot is unknown.  Returns the
  default hidden placeholder when the child component has no `:render`
  of its own."
  ([self slot-key]
   (render-slot self slot-key
                (or (resolve 'stube.registry/lookup!)
                    (throw (ex-info "stube.registry not loaded" {})))))
  ([self slot-key lookup!]
   (let [conv      (or *conv*
                       (throw (ex-info "stube.render/*conv* is unbound; render-slot cannot resolve children"
                                       {:slot slot-key
                                        :parent (:instance/id self)})))
         child-iid (or (get-in self [:instance/children slot-key])
                       (throw (ex-info "Unknown slot on parent"
                                       {:slot          slot-key
                                        :parent        (:instance/id self)
                                        :known-slots   (vec (keys (:instance/children self)))})))
         child     (or (get-in conv [:conv/instances child-iid])
                       (throw (ex-info "Slot child instance is missing from conv"
                                       {:slot      slot-key
                                        :child-iid child-iid})))
         lookup-fn (if (var? lookup!) @lookup! lookup!)
         cdef      (lookup-fn (:instance/type child))
         render-fn (or (:component/render cdef)
                       (fn default [s]
                         [:div {:id (:instance/id s) :hidden true}]))]
     (render-fn child))))

(ns dev.zeko.stube.render
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
  (:require [dev.onionpancakes.chassis.core :as chassis]
            [dev.zeko.stube.conversation :as conv])
  (:import (java.net URLEncoder)))

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

(defn html
  "Render hiccup `tree` to an HTML string."
  ^String [tree]
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
      (throw (ex-info "dev.zeko.stube.render/*cid* is unbound; cannot build event URL"
                      {}))))

(def ^:private no-payload ::no-payload)

(def payload-query-param
  "Query-string key used by [[event-url]] for structured event payloads."
  "_stube_payload")

(defn- parse-route-event [route-event]
  (if (vector? route-event)
    (let [[event & payloads] route-event]
      (when-not event
        (throw (ex-info "Structured stube events need a route keyword"
                        {:route-event route-event})))
      {:event event
       :payload (case (count payloads)
                  0 no-payload
                  1 (first payloads)
                  (vec payloads))})
    {:event route-event
     :payload no-payload}))

(defn- url-encode [s]
  (URLEncoder/encode (str s) "UTF-8"))

(defn event-url
  "URL the browser POSTs to for an event.  Public so user code can build
  custom Datastar expressions that target the same endpoint.

  `route-event` is either a keyword (`:save`) or a structured event
  vector (`[:pick-day day]`).  The path always contains the logical
  event name; structured payloads ride in a small EDN query parameter so
  the server can reconstruct `{:event :pick-day :payload day}` without
  teaching Datastar about stube metadata."
  [iid route-event]
  (let [{:keys [event payload]} (parse-route-event route-event)
        base (str "/conv/" (require-cid!) "/" iid "/" (name event))]
    (if (= no-payload payload)
      base
      (str base "?" payload-query-param "=" (url-encode (pr-str payload))))))

(defn back-url
  "URL the browser POSTs to for the conversation-level Back action."
  []
  (str "/conv/" (require-cid!) "/back"))

(defn upload-url
  "URL a multipart upload form POSTs to for `self`.

  Uploads intentionally do not use Datastar's signal POST body: browser
  file inputs need a normal `multipart/form-data` request.  The HTTP
  layer turns that request back into a regular `:upload-received` event
  for this instance and pushes any resulting fragments over the already
  open SSE stream."
  [self]
  (let [iid (or (:instance/id self)
                (throw (ex-info "dev.zeko.stube.render/upload-url requires an instance map"
                                {:got self})))]
    (str "/stube/upload/" (require-cid!) "/" iid)))

(defn upload-target
  "Stable hidden iframe target name for upload forms owned by `self`."
  [self]
  (let [iid (or (:instance/id self)
                (throw (ex-info "dev.zeko.stube.render/upload-target requires an instance map"
                                {:got self})))]
    (str "stube-upload-" iid)))

(defn upload-attrs
  "Return form attributes for a zero-JS multipart upload.

      [:form (s/upload-attrs self)
       [:input {:type \"file\" :name \"file\"}]
       [:button \"Upload\"]]
      (s/upload-frame self)

  The hidden iframe target prevents the browser from navigating away from
  the Datastar shell while the server handles the multipart POST."
  [self]
  {:method  "post"
   :action  (upload-url self)
   :enctype "multipart/form-data"
   :target  (upload-target self)})

(defn upload-frame
  "Hidden iframe target used by [[upload-attrs]]."
  [self]
  [:iframe {:name   (upload-target self)
            :title  "stube upload target"
            :hidden true
            :style  "display:none; width:0; height:0; border:0;"}])

(defn back-button
  "Return a small Hiccup button wired to the conversation-level `[:back]`
  effect.

      (s/back-button \"Back\")

  This intentionally does not take `self`: browser history rewind is a
  conversation operation, not a component-local event.  For wizard-style
  Back buttons that answer a parent with a sentinel, keep using
  `(s/on self :click :as ...)` from that component."
  ([label]
   (back-button label {}))
  ([label attrs]
   [:button (merge {:type "button"
                    :class "stube-button"
                    (keyword "data-on:click") (str "@post('" (back-url) "')")}
                   attrs)
    label]))

(defn on
  "Return an attribute map that wires a real DOM event on the
  surrounding element to a server-side stube event.

  Two arities:

      (on self :submit)            ;; DOM `submit`  → POST .../submit
      (on self :click :as :inc)    ;; DOM `click`   → POST .../inc
      (on self :click :as [:pick item-id])
                                    ;; handler sees :event :pick,
                                    ;; :payload item-id

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
     (throw (ex-info "dev.zeko.stube.render/on: 4-arity expects :as as the third argument"
                     {:got as-kw})))
   (let [iid (or (:instance/id self)
                 (throw (ex-info "dev.zeko.stube.render/on requires an instance map"
                                 {:got self})))
         attr-k (keyword (str "data-on:" (name dom-event)))
         expr   (str "@post('" (event-url iid route-event) "')")]
     {attr-k expr})))

(defn bind
  "Return an attribute map that two-way binds the named signal to the
  current element.

      [:input (merge {:name \"answer\"} (s/bind :answer))]

  Datastar's signal-defining attributes use the colon form
  (`data-bind:foo`); the dash form would not be recognised.

  Datastar 1.0 camel-cases `data-bind:<key>` by default.  Clojure code
  conventionally names signals with kebab-case keywords and reads the
  POSTed signals back by the same keyword, so force Datastar's no-op
  kebab case modifier to keep the wire key unchanged."
  [signal]
  {(keyword (str "data-bind:" (name signal) "__case.kebab")) true})

(def ^{:doc "See [[dev.zeko.stube.conversation/local-signal]]."}
  local-signal conv/local-signal)

(defn local-bind
  "Like [[bind]], but scopes logical `signal` to this component instance.

      :keep #{:answer}
      [:input (s/local-bind self :answer)]

  The browser sends `:answer-<iid>`; the conversation layer lifts that
  value back onto `:answer` before the handler runs."
  [self signal]
  (bind (local-signal self signal)))

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
                (or (resolve 'dev.zeko.stube.registry/lookup!)
                    (throw (ex-info "dev.zeko.stube.registry not loaded" {})))))
  ([self slot-key lookup!]
   (let [conv      (or *conv*
                       (throw (ex-info "dev.zeko.stube.render/*conv* is unbound; render-slot cannot resolve children"
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

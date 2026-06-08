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
  (:require [charred.api                    :as json]
            [clojure.string                 :as string]
            [dev.onionpancakes.chassis.core :as chassis]
            [dev.zeko.stube.conversation    :as conv]
            [dev.zeko.stube.halos           :as halos])
  (:import (java.net URLEncoder)))

;; ---------------------------------------------------------------------------
;; Render-time context
;; ---------------------------------------------------------------------------

(def ^:dynamic *cid*
  "The conversation id of the request currently being served.  Bound by
  the http layer around every render so attribute helpers can build URLs
  pointing at the right SSE endpoint."
  nil)

(def ^:dynamic *base-path*
  "URL prefix for the current adapter mount.  Standalone stube keeps this
  empty, while embedders can bind it to e.g. `/widget` so generated
  Datastar URLs stay inside the host route tree."
  "")

(def ^:dynamic *root-selector*
  "Selector targeted by the first frame render.  The shell and embedded
  fragment render a matching element."
  "#root")

(def ^:dynamic *conv*
  "The conversation being rendered, bound by `frame/render-frame` for
  the duration of one render call.  [[render-slot]] consults it to
  look up embedded children by id.

  Two-way bindings (`s/bind`) and event hooks (`s/on`) only need the
  cid; only slot rendering needs the conversation, hence the separate
  vars."
  nil)

(def ^:dynamic *signal-case*
  "Default casing for Datastar signal wire keys.  Bound by the kernel
  from `:signal-case` on [[dev.zeko.stube.runtime/make-kernel]].

  Two valid values:

  * `:kebab` (default) — `(s/bind :edit-markdown)` emits
    `data-bind:edit-markdown__case.kebab`, so Datastar keeps the signal
    keyed `edit-markdown` on the wire and handlers read it back as the
    same kebab keyword.  Pick this if all signal access is from Clojure.

  * `:camel` — `(s/bind :edit-markdown)` emits `data-bind:edit-markdown`
    (no modifier), so Datastar's default `__case.camel` makes the wire
    key `editMarkdown`.  Pick this if any inline Datastar expression
    references the signal (e.g. `data-on:input=\"$editMarkdown = ...\"`),
    since JS identifiers can't contain dashes.

  Per-call `{:case :camel}` / `{:case :kebab}` opts on [[bind]] /
  [[local-bind]] / [[$]] / [[signal]] override this default."
  :kebab)

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

(defn instance-id
  "Return the `:instance/id` carried on `self`, or throw with a clear
  message if the value is missing.

  Helpers passing instance ids out into pure rendering code should reach
  for this rather than destructuring `:instance/id` directly — the
  framework owns the wire shape, and the public name is the stable seam."
  [self]
  (or (:instance/id self)
      (throw (ex-info "dev.zeko.stube.render/instance-id requires an instance map"
                      {:got self}))))

(defn child-iid
  "Return the iid of the child instance mounted under `slot-key` on
  `self`, or nil when the slot is unknown.

  The 2-arg form looks in `:instance/children` — slots declared by
  `:children` on the component definition or filled by a
  `[:call-in-slot …]` effect.  The 3-arg form looks in
  `:instance/keyed-slots` for a specific child by application key:
  `(s/child-iid self :slot/items :note-42)` returns the iid of the
  keyed child under `:slot/items` with key `:note-42`, or nil if
  there is no such entry.

  Useful when a parent needs to address its embedded child by id, e.g.
  to target a `(s/dispatch-to)` effect or to build an event URL through
  `s/event-url` / `s/on-target`."
  ([self slot-key]
   (get-in self [:instance/children slot-key]))
  ([self slot key]
   (get-in self [:instance/keyed-slots slot :children key :iid])))

(defn- ->iid
  "Coerce `target` to an instance id.  Accepts either a bare iid string
  or an instance map (so `(s/on-target self :click :as :foo)` works as
  well as `(s/on-target iid :click :as :foo)`)."
  [target]
  (cond
    (string? target) target
    (map? target)    (instance-id target)
    :else
    (throw (ex-info "stube target must be an instance map or an instance-id string"
                    {:got target}))))

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

(defn- clean-base []
  (let [base (or *base-path* "")]
    (cond
      (= base "/") ""
      (.endsWith base "/") (subs base 0 (dec (count base)))
      :else base)))

(defn- path [& parts]
  (str (clean-base) (apply str parts)))

(defn sse-url
  "URL the shell uses to open the Datastar SSE stream for `cid`."
  [cid]
  (path "/sse/" cid))

(defn ui-css-url
  "URL for the stock stylesheet in the current mount."
  []
  (path "/ui.css"))

(defn halos-js-url
  "URL for the optional halos script in the current mount."
  []
  (path "/halos.js"))

(defn preserve-js-url
  "URL for stube's preserved-subtree bridge script in the current mount."
  []
  (path "/preserve.js"))

(defn behaviors-js-url
  "URL for stube's behaviors bridge script in the current mount."
  []
  (path "/behaviors.js"))

(defn component-style-url
  "URL of the stylesheet for component `type-kw` in the current mount."
  [type-kw]
  (path "/styles/" (namespace type-kw) "/" (name type-kw) ".css"))

(defn component-module-url
  "URL of a JS module by `module-id` (e.g. `\"notes/zoom\"`) in the
  current mount."
  [module-id]
  (path "/modules/" module-id ".js"))

(defn behavior-module-url
  "URL the behaviors bridge imports for behavior `behavior-id`
  (a qualified keyword) in the current mount."
  [behavior-id]
  (path "/behaviors/" (namespace behavior-id) "/" (name behavior-id) ".js"))

(defn event-url
  "URL the browser POSTs to for an event.  Public so user code can build
  custom Datastar expressions that target the same endpoint.

  `target` is either a bare instance-id string or an instance map.

  `route-event` is either a keyword (`:save`) or a structured event
  vector (`[:pick-day day]`).  The path always contains the logical
  event name; structured payloads ride in a small EDN query parameter so
  the server can reconstruct `{:event :pick-day :payload day}` without
  teaching Datastar about stube metadata."
  [target route-event]
  (when-not (some? target)
    (throw (ex-info "dev.zeko.stube.render/event-url requires a target instance id"
                    {:route-event route-event})))
  (let [iid (->iid target)
        {:keys [event payload]} (parse-route-event route-event)
        cid  (require-cid!)
        base (path "/event/" cid "/" iid "/" (name event))]
    (if (= no-payload payload)
      base
      (str base "?" payload-query-param "=" (url-encode (pr-str payload))))))

(defn- modifier-token [k v]
  (let [nm (name k)]
    (cond
      (true? v)     (str "__" nm)
      (false? v)    nil
      (nil? v)      nil
      (keyword? v)  (str "__" nm "." (name v))
      :else         (str "__" nm "." v))))

(defn- modifiers->suffix
  "Build the Datastar modifier suffix for `data-on:<event>...`.

  `modifiers` is either `nil`, a map, or a sequence of `[k v]` pairs.
  Maps are emitted in sorted key order for deterministic output;
  sequences preserve caller order (useful when a single Datastar
  modifier takes multiple positional parts, e.g. `__debounce.300ms.leading`
  which the caller can spell as `[[:debounce \"300ms.leading\"]]`)."
  [modifiers]
  (cond
    (nil? modifiers)
    ""

    (map? modifiers)
    (->> modifiers
         (sort-by (comp name key))
         (keep (fn [[k v]] (modifier-token k v)))
         (apply str))

    (sequential? modifiers)
    (->> modifiers
         (keep (fn [[k v]] (modifier-token k v)))
         (apply str))

    :else
    (throw (ex-info "stube event modifiers must be a map or seq of pairs"
                    {:got modifiers}))))

(defn on-target
  "Like [[on]], but route the event to an explicit target instance
  instead of the component whose hiccup is being rendered.

      [:button (s/on-target parent-iid :click :as [:open note-id]) \"Open\"]
      [:button (s/on-target parent-self :click :as :open) \"Open\"]
      [:input  (s/on-target target :input :as :search {:debounce \"300ms\"})]

  `target` may be either a bare instance-id string or an instance map;
  the helper coerces it through [[instance-id]].

  The optional 5-arity `modifiers` map produces Datastar event modifiers
  in the attribute name (`data-on:input__debounce.300ms`).  Values may be
  strings, numbers, keywords, or `true` for flag-only modifiers
  (`{:stop true :prevent true}` → `__prevent__stop`).  Map entries are
  sorted by key name for deterministic output; pass a vector of pairs to
  preserve caller order.

  This is intentionally a narrow escape hatch for cross-instance controls
  such as links rendered inside one child that should notify a stable
  parent without answering/removing the child."
  ([target dom-event]
   (on-target target dom-event :as dom-event nil))
  ([target dom-event as-kw route-event]
   (on-target target dom-event as-kw route-event nil))
  ([target dom-event as-kw route-event modifiers]
   (when-not (= :as as-kw)
     (throw (ex-info "dev.zeko.stube.render/on-target: expects :as as the third argument"
                     {:got as-kw})))
   {(keyword (str "data-on:" (name dom-event) (modifiers->suffix modifiers)))
    (str "@post('" (event-url target route-event) "')")}))

(defn on-parent
  "Like [[on-target]], but routes the event to `self`'s parent instance.

      [:button (s/on-parent self :click :as [:open note-id]) \"Open\"]

  Equivalent to `(on-target (:instance/parent self) …)`, but the public
  name lets pure render helpers ride one stable seam instead of
  reaching for the instance-map's keys.  Use this for the recurring
  pattern of a child rendering controls whose semantics belong to the
  parent (close button on a card, link inside a row that opens
  something in the owning desk, etc.)."
  ([self dom-event]
   (on-parent self dom-event :as dom-event nil))
  ([self dom-event as-kw route-event]
   (on-parent self dom-event as-kw route-event nil))
  ([self dom-event as-kw route-event modifiers]
   (let [parent (or (:instance/parent self)
                    (throw (ex-info "dev.zeko.stube.render/on-parent requires self to have :instance/parent"
                                    {:got self})))]
     (on-target parent dom-event as-kw route-event modifiers))))

(defn back-url
  "URL the browser POSTs to for the conversation-level Back action."
  []
  (path "/back/" (require-cid!)))

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
    (path "/upload/" (require-cid!) "/" iid)))

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

(defn- require-instance-id! [helper self]
  (or (:instance/id self)
      (throw (ex-info (str helper " requires an instance map")
                      {:got self}))))

(defn- preserve-label [label]
  (let [s (cond
            (keyword? label) (name label)
            (string? label)  label
            :else
            (throw (ex-info "stube preserve labels must be keywords or strings"
                            {:got label})))]
    (if (seq s)
      s
      (throw (ex-info "stube preserve labels must not be empty"
                      {:got label})))))

(defn preserve
  "Return attributes marking an element's children as externally owned.

      [:div (merge (s/preserve self :editor)
                   (s/on-mount self :editor \"...\"))]

  stube's shell loads a small bridge that lets Datastar merge the marked
  element's attributes on each morph while skipping its child subtree.
  The label only needs to be unique within the
  rendered patch; use a stable keyword such as `:editor` or `:chart`."
  [self label]
  (require-instance-id! "dev.zeko.stube.render/preserve" self)
  {:data-stube-preserve (preserve-label label)})

(defn on-mount
  "Return a Datastar `data-init` expression only before `self` is rendered.

  Use this with [[preserve]] to construct a third-party widget once, then
  let later stube renders update the host element's attributes without
  re-running the widget constructor."
  [self label expr]
  (require-instance-id! "dev.zeko.stube.render/on-mount" self)
  (preserve-label label)
  (if (:instance/rendered? self)
    {}
    {:data-init expr}))

(defn on-unmount
  "Attach a JS expression that runs once when the host element is
  detached from the DOM.

  Use this alongside [[preserve]] / [[on-mount]] to dispose third-party
  widgets cleanly:

      [:div (merge (s/preserve self :editor)
                   (s/on-mount   self :editor \"el.cmView = new EditorView({parent:el})\")
                   (s/on-unmount self :editor \"el.cmView?.destroy()\"))]

  The expression runs **once**, **just before** the host detaches,
  with `el` bound to the element (mirroring [[on-mount]]).  The
  expression must be synchronous and idempotent; it should not emit
  events back to the server.  Errors are logged to `console` and do
  not block the morph.

  Implemented via a single document-wide MutationObserver installed
  by `stube/preserve.js`."
  [self label expr]
  (require-instance-id! "dev.zeko.stube.render/on-unmount" self)
  (preserve-label label)
  {:data-stube-on-unmount expr})

(defn preserve-scroll
  "Return an attribute that keeps this element's scroll position stable
  across Datastar morphs.

      [:div.ledger-columns (merge {:id (s/child-iid self :slot/cols)}
                                  (s/preserve-scroll self :ledger))
       …]

  A morph that replaces or re-renders a scroll container resets its
  `scrollLeft` / `scrollTop` to 0.  The preserve bridge snapshots the
  scroll offsets of every `data-stube-preserve-scroll` element just
  before each morph and restores them by `label` immediately after, so
  horizontally-scrolled ledgers and long lists don't jump on reconcile.

  The label only needs to be unique among the scroll containers present
  in one rendered patch.  Unlike [[preserve]], this does *not* protect
  the subtree from morphing — it only restores the scroll offset of the
  surviving (or re-created) container, so the children stay
  server-owned and keyed diffs still apply."
  [self label]
  (require-instance-id! "dev.zeko.stube.render/preserve-scroll" self)
  {:data-stube-preserve-scroll (preserve-label label)})

(defn- kebab-case [s]
  (-> (str s)
      (string/replace #"_" "-")
      (string/lower-case)))

(defn- behavior-slug [behavior-id]
  (cond
    (qualified-keyword? behavior-id)
    (str (namespace behavior-id) "/" (name behavior-id))

    (string? behavior-id)
    behavior-id

    :else
    (throw (ex-info "stube behavior id must be a qualified keyword or string"
                    {:got behavior-id}))))

(defn- behavior-arg-attr [k]
  (keyword (str "data-stube-arg-" (kebab-case (name k)))))

(defn- behavior-arg-value [v]
  (cond
    (nil? v)         ""
    (string? v)      v
    (keyword? v)     (name v)
    (symbol? v)      (name v)
    (number? v)      (str v)
    (boolean? v)     (str v)
    :else            (pr-str v)))

;; `behavior` accepts an optional `{:signal …}` and wire-cases the
;; signal key; `signal-wire-name` is defined further down with the other
;; signal helpers, so forward-declare it.
(declare signal-wire-name)

(defn behavior
  "Attach a client-side behavior to this element.

      [:div (s/behavior self :notes/cm6-editor {:doc-id (:doc-id self)})]

  Renders as `data-stube-behavior=\"notes/cm6-editor\"` plus one
  `data-stube-arg-<key>` attribute per entry in `args`.

  The 4-arity takes an `opts` map.  `{:signal <signal-key>}` is sugar for
  the common \"behavior writes one signal\" case (CodeMirror, a slider, a
  rich-text widget): it stamps `data-stube-arg-signal=\"<wire-name>\"`
  with the key wire-cased exactly as [[bind]] would, so the behavior
  reads a stable `ctx.args.signal` and calls `ctx.setSignal(ctx.args.signal,
  value)` without the host computing the wire name or threading it through
  `args` by hand.  Pair it with a `(s/signal-mirror <signal-key>)` input
  in scope (that hidden input is the public `data-bind` write seam; see
  [[signal-mirror]]).  An `opts` `{:case …}` propagates to the wire-casing.

      [:input (s/signal-mirror sig)]
      [:div (s/behavior self :notes/cm6-editor {:content md} {:signal sig})]

  The behaviors bridge loaded with the shell discovers the attribute
  after each Datastar morph, lazy-imports the module at
  `/<base-path>/behaviors/<ns>/<name>.js`, and drives its
  `mount`/`patched`/`unmount` lifecycle.  Behaviors are the canonical
  seam for non-trivial client code: third-party widgets, autocompletes,
  drag-and-drop, anything that needs imperative JS keyed to a DOM
  element.

  `args` values are stringified (`name` for keywords, `str` for
  numbers/booleans, `pr-str` for everything else) and decoded on the
  JS side as `ctx.args.<camelKey>`.  Pass small, scalar values that
  belong to the server's view of the element — avoid round-tripping
  large blobs through DOM attributes.

  Behavior `ctx` shape:

  * `el` — the DOM element the behavior is attached to.
  * `args` — `data-stube-arg-*` attributes decoded into a camelCased
    object.
  * `basePath` — the kernel base-path so behaviors can build other
    stube URLs.
  * `signals.get(name)` / `signals.set(name, v)` / `signals.patch(map)`
    — read/write Datastar signals.  Aliases `ctx.setSignal(name, v)`
    and `ctx.patchSignals(map)` exist for the common write paths so
    behaviors can drive bound signals directly, retiring hidden-input
    shims.
  * `fetch(eventUrl, opts?)` — POST to a stube event URL the same
    shape `s/on` produces.  Pass the URL in via a `data-stube-arg-*`
    built with [[event-url]] on the server.
  * `dispatch(event, payload?, opts?)` — fire a component event on the
    owning component directly (`ctx.dispatch(\"save\")`,
    `ctx.dispatch(\"pick\", {dayId})`), resolving the dispatch target
    from the `data-stube-event-base` this helper stamps.  The handler
    sees `{:event :save}` / `{:event :pick :payload {:dayId …}}`.
    `payload` is encoded to EDN over the JSON value subset (strings,
    numbers, booleans, vectors, and maps with keyword keys); unlike
    Datastar's own `@post`, dispatch does *not* attach the current
    signals, so pass anything the handler needs as the payload.

  Use [[preserve]] when the behavior owns DOM children outside the
  server's render tree, and [[on-unmount]] for one-off teardown that
  doesn't need the full behavior contract."
  ([self behavior-id]
   (behavior self behavior-id {} {}))
  ([self behavior-id args]
   (behavior self behavior-id args {}))
  ([self behavior-id args opts]
   (let [iid  (require-instance-id! "dev.zeko.stube.render/behavior" self)
         slug (behavior-slug behavior-id)
         arg-attrs (into {}
                         (map (fn [[k v]]
                                [(behavior-arg-attr k) (behavior-arg-value v)]))
                         args)
         ;; Stamp the dispatch target so the behavior can fire component
         ;; events directly via `ctx.dispatch(event, payload?)` without
         ;; the host pre-building an `event-url`.  Only possible inside a
         ;; render (when *cid* is bound); harmless to omit otherwise — the
         ;; bridge warns if a behavior calls dispatch without it.
         base (when *cid* (path "/event/" *cid* "/" iid))
         sig  (:signal opts)]
     (cond-> (assoc arg-attrs :data-stube-behavior slug)
       base (assoc :data-stube-event-base base)
       sig  (assoc (behavior-arg-attr :signal)
                   (behavior-arg-value (signal-wire-name sig opts)))))))

(defn back-button
  "Return a small Hiccup button wired to the conversation-level `[:back]`
  effect.

      (s/back-button \"Back\")

  This intentionally does not take `self`: conversation-level history
  rewind is a conversation operation, not a component-local event.  It
  walks `:conv/history` (not `window.history`) — the browser's Back
  button still works independently.  For wizard-style Back buttons that
  answer a parent with a sentinel, keep using
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
      [:button (s/on self :click :as :dec) \"−\"]
      [:input  (s/on self :input :as :search {:debounce \"300ms\"})]

  The optional 5-arity `modifiers` map produces Datastar event
  modifiers in the attribute name (`data-on:input__debounce.300ms`).
  See [[on-target]] for the modifier rules."
  ([self dom-event]
   (on self dom-event :as dom-event nil))
  ([self dom-event as-kw route-event]
   (on self dom-event as-kw route-event nil))
  ([self dom-event as-kw route-event modifiers]
   (when-not (= :as as-kw)
     (throw (ex-info "dev.zeko.stube.render/on: expects :as as the third argument"
                     {:got as-kw})))
   (let [iid (or (:instance/id self)
                 (throw (ex-info "dev.zeko.stube.render/on requires an instance map"
                                 {:got self})))
         attr-k (keyword (str "data-on:" (name dom-event) (modifiers->suffix modifiers)))
         expr   (str "@post('" (event-url iid route-event) "')")]
     {attr-k expr})))

(defn component-slug
  "Return the wire form of component id `type-kw` — namespace + \"/\" +
  name (e.g. `:notes/shell` → `\"notes/shell\"`).  This is the value of
  `data-stube-component` on every component root."
  [type-kw]
  (when (qualified-keyword? type-kw)
    (str (namespace type-kw) "/" (name type-kw))))

(defn component-class
  "Return the auto-generated CSS class for component id `type-kw`
  (e.g. `:notes/shell` → `\"stube-c-notes-shell\"`).  Always present on
  every component root so host CSS can hang selectors off it without
  the host having to spell out every class manually."
  [type-kw]
  (when (qualified-keyword? type-kw)
    (str "stube-c-" (namespace type-kw) "-" (name type-kw))))

(defn root-attrs
  "Return an attribute map carrying the framework hooks every component
  root needs, merged with any other attribute maps the caller hands in.

      (s/root-attrs self (s/on self :submit) {:class \"x\"})

  Emitted attributes:

  * `:id` — `self`'s `:instance/id`, required by Datastar's morph-by-id.
  * `:data-stube-component` — the component id in `ns/name` form, so
    CSS selectors and the behaviors bridge can address every component
    by its registered keyword.
  * `:class` — the auto-generated `stube-c-<ns>-<name>` class
    (concatenated with any user-supplied `:class`).

  If the caller passes `:id`, the framework id wins.  If `:instance/type`
  is missing (component code exercised through tests with hand-rolled
  instance maps), the component-derived attributes are skipped silently
  and only `:id` is enforced."
  [self & attr-maps]
  (let [iid (or (:instance/id self)
                (throw (ex-info "dev.zeko.stube.render/root-attrs requires an instance map"
                                {:got self})))
        type-kw (:instance/type self)
        slug    (component-slug type-kw)
        cls     (component-class type-kw)
        user    (apply merge attr-maps)
        user-class (:class user)
        merged-class (cond
                       (and cls user-class) (str cls " " user-class)
                       :else                 (or user-class cls))]
    (cond-> (assoc user :id iid)
      slug         (assoc :data-stube-component slug)
      merged-class (assoc :class merged-class))))

(defn- kebab->camel
  "Translate a kebab-cased string to camelCase: `\"edit-markdown\"` →
  `\"editMarkdown\"`.  Leaves the leading segment intact and upper-cases
  the first character after each `-`."
  [^String s]
  ;; Mirror Datastar's `camel` transform exactly (`/-[a-z]/g`): only a
  ;; lowercase letter after a dash is folded; a dash before a digit is
  ;; left intact.  This matters for wire names ending in an instance id
  ;; (`-ix-1` → `Ix-1`, not `Ix1`), where a `[a-zA-Z0-9]` class would
  ;; disagree with what Datastar stores client-side.
  (string/replace s #"-([a-z])" (fn [[_ c]] (string/upper-case c))))

(defn- resolve-case
  "Resolve the effective signal casing: a per-call `{:case ...}` opt wins
  over the kernel-bound default in [[*signal-case*]]."
  [opts]
  (or (:case opts) *signal-case* :kebab))

(defn signal-wire-name
  "Translate logical signal keyword `k` to the string Datastar uses on
  the wire under `casing` (`:kebab` or `:camel`).  Useful when a host
  needs to build wire keys by hand, but most code should reach for
  [[bind]], [[$]], or [[signal]] instead."
  ([k] (signal-wire-name k nil))
  ([k opts]
   (let [s (name k)]
     (if (= (resolve-case opts) :camel)
       (kebab->camel s)
       s))))

(defn bind
  "Return an attribute map that two-way binds the named signal to the
  current element.

      [:input (merge {:name \"answer\"} (s/bind :answer))]

  Datastar's signal-defining attributes use the colon form
  (`data-bind:foo`); the dash form would not be recognised.

  Casing is resolved as: per-call `{:case ...}` opt → kernel-bound
  [[*signal-case*]] → `:kebab` default.  Under `:kebab`, the
  `__case.kebab` modifier keeps the wire key identical to the supplied
  keyword (so handlers read it back as the same kebab keyword).  Under
  `:camel`, no modifier is emitted so Datastar's default applies and
  `:edit-markdown` lands on the wire as `editMarkdown` (which inline
  expressions like `data-on:input=\"$editMarkdown = ...\"` can address)."
  ([signal] (bind signal nil))
  ([signal opts]
   (let [casing  (resolve-case opts)
         dbind   (str "data-bind:" (name signal))
         attr-kw (if (= casing :kebab)
                   (keyword (str dbind "__case.kebab"))
                   (keyword dbind))]
     {attr-kw true})))

(def ^{:doc "See [[dev.zeko.stube.conversation/local-signal]]."}
  local-signal conv/local-signal)

(defn local-bind
  "Like [[bind]], but scopes logical `signal` to this component instance.

      :keep #{:answer}
      [:input (s/local-bind self :answer)]

  The browser sends a per-instance wire key; the conversation layer lifts
  that value back onto `:answer` before the handler runs.  Casing follows
  the same resolution as [[bind]]."
  ([self signal] (local-bind self signal nil))
  ([self signal opts]
   (bind (local-signal self signal) opts)))

(defn $
  "Return the Datastar inline-expression reference string for logical
  signal `k`, e.g. `(s/$ :create-title)` → `\"$create-title\"` under
  `:kebab`, `\"$createTitle\"` under `:camel`.

  Use this when building inline JS expressions that read or write a
  signal:

      [:input {:data-on:input (str (s/$ :create-slug) \" = slugify(\"
                                   (s/$ :create-title) \".value)\")}]

  Casing follows the same resolution as [[bind]]."
  ([k] ($ k nil))
  ([k opts]
   (str "$" (signal-wire-name k opts))))

(defn signal
  "Look up logical signal `k` on a posted event in the active wire
  casing — `(s/signal event :edit-markdown)` reads `:edit-markdown` under
  `:kebab` or `:editMarkdown` under `:camel`.

  Use this in `:handle` callbacks so the read side mirrors whatever
  casing [[bind]] used to write it:

      (defn handle [self event]
        (case (:event/key event)
          :save (save! self (s/signal event :edit-markdown))))

  Casing follows the same resolution as [[bind]]."
  ([event k] (signal event k nil))
  ([event k opts]
   ;; Probe both the wire keyword and the wire string: the HTTP layer
   ;; only interns signal keys that were already interned, so a key can
   ;; arrive either way.  `contains?` rather than `or` so a falsey
   ;; signal value (an unchecked checkbox sends `false`) still reads.
   (let [signals (:signals event)
         wire    (signal-wire-name k opts)
         wire-kw (keyword wire)]
     (if (contains? signals wire-kw)
       (get signals wire-kw)
       (get signals wire)))))

(defn indicator
  "Return attrs that mount Datastar's `data-indicator` on this element
  bound to a page-global signal — typically used to flip a 'loading'
  boolean while an `@post(…)`/`@get(…)` round-trip is in flight:

      [:button (merge (s/on self :click :as :reload)
                      (s/indicator :reload-loading))
       \"Reload\"]
      ;; …and a spinner that reads the same signal:
      [:span {:data-show (s/$ :reload-loading)} \"⏳\"]

  Datastar writes `true` to the signal while the request is in flight
  and back to `false` when it completes.  This is the page-global
  counterpart to [[local-indicator]]: use it for top-bar/global action
  buttons where there is only one instance; reach for `local-indicator`
  when two embedded copies of a component must not share indicator
  state.  Pair with [[$]] for the matching `data-show` reference.
  Casing follows the same resolution as [[bind]]."
  ([signal] (indicator signal nil))
  ([signal opts]
   {(keyword (str "data-indicator:" (signal-wire-name signal opts)))
    true}))

(defn local-signal-ref
  "Return the Datastar inline-expression reference for a per-instance
  signal — `(s/local-signal-ref self :save-submitting)` →
  `\"$save-submitting-ix-1\"` under `:kebab`, `\"$saveSubmittingIx1\"`
  under `:camel`.

  Use this when an inline JS expression needs to read a signal that
  must be unique per component instance — most commonly the boolean
  signal Datastar writes from `data-indicator`:

      [:button (merge (s/local-indicator self :save-submitting)
                      {:data-show (str \"!\" (s/local-signal-ref
                                              self :save-submitting))})
       \"Save\"]

  Casing follows the same resolution as [[bind]]."
  ([self signal] (local-signal-ref self signal nil))
  ([self signal opts]
   ($ (local-signal self signal) opts)))

(defn local-indicator
  "Return attrs that mount Datastar's `data-indicator` on this element
  scoped to a per-instance signal — typically used to flip a 'submitting'
  boolean while an `@post(…)` round-trip is in flight:

      [:button (merge (s/on-target self :save)
                      (s/local-indicator self :save-submitting))
       \"Save\"]

  Datastar writes `true` to the signal while the request is in flight
  and back to `false` when it completes.  Because the signal name
  includes the instance id, two embedded copies of the component don't
  share the same indicator state.  Pair with [[local-signal-ref]] for
  the matching `data-show` reference.  Casing follows the same
  resolution as [[bind]]."
  ([self signal] (local-indicator self signal nil))
  ([self signal opts]
   {(keyword (str "data-indicator:"
                  (signal-wire-name (local-signal self signal) opts)))
    true}))

(defn signal-mirror
  "Return attrs for a hidden `<input>` that mirrors a Datastar signal,
  giving client-side behaviors a stable write seam.

      [:input (s/signal-mirror :edit-markdown)]

  The input is `type=\"hidden\"`, two-way-bound to the named signal with
  the same casing rules as [[bind]], and carries
  `data-stube-signal-mirror=\"<wire-name>\"` so the behaviors bridge can
  find it.  A behavior calling `ctx.setSignal(<wire-name>, value)` then
  writes the value through DOM (set `.value`, dispatch `input`) and
  Datastar's `data-bind` machinery propagates it to the signal store.

  This is the canonical signal-write path for behaviors that own a live
  widget (CodeMirror, Chart.js, drag-and-drop): no coupling to any
  Datastar-internal handle, just the public `data-bind:` attribute and a
  standard DOM event.  Casing follows the same resolution as [[bind]]."
  ([signal] (signal-mirror signal nil))
  ([signal opts]
   (let [wire (signal-wire-name signal opts)]
     (assoc (bind signal opts)
            :type "hidden"
            :data-stube-signal-mirror wire))))

(defn signals
  "Return an attribute map that seeds initial signal *values* on this
  element's subtree: `{:data-signals \"<json>\"}` whose keys are wire-cased
  via [[signal-wire-name]] so they match what [[bind]] / [[$]] / [[signal]]
  emit and read.

  [[bind]] wires a two-way binding but does not set the signal's starting
  value; pair it with [[signals]] to seed one without hand-rolling the
  JSON and the casing:

      [:form (merge (s/signals {:create-title \"\" :create-slug \"\"})
                    (s/on-target self :create))
       [:input (merge {:name \"title\"} (s/bind :create-title))]]

  Casing follows the same resolution as [[bind]] (per-call `{:case ...}`
  → kernel-bound [[*signal-case*]] → `:kebab`).  Values must be
  JSON-encodable."
  ([signal-map] (signals signal-map nil))
  ([signal-map opts]
   {:data-signals
    (json/write-json-str
      (reduce-kv (fn [m k v] (assoc m (signal-wire-name k opts) v))
                 {} signal-map))}))

(defn local-signals
  "Like [[signals]], but scopes each key to this component instance via
  [[local-signal]], so two embedded copies of a component don't seed the
  same page-global signal.  Pair with [[local-bind]] and a `:keep` of the
  same logical keys:

      :keep #{:edit-title :edit-markdown}
      [:form (s/local-signals self {:edit-title title :edit-markdown md})
       [:input (s/local-bind self :edit-title)]]

  Casing follows the same resolution as [[bind]]."
  ([self signal-map] (local-signals self signal-map nil))
  ([self signal-map opts]
   (signals (reduce-kv (fn [m k v] (assoc m (local-signal self k) v))
                       {} signal-map)
            opts)))

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

  Returns `nil` when no child is currently mounted under `slot-key` —
  typically a `[:call-in-slot …]` slot that hasn't been called into
  yet, or one whose occupant has answered and popped.  The natural
  template-style embed `(s/render-slot self :slot/foo)` is therefore a
  no-op when empty, so the host hiccup can include it unconditionally
  instead of guarding with `(when (s/child-iid self :slot/foo) …)`.

  Throws if `*conv*` is unbound (the renderer was called outside a
  dispatch / render cycle) or when the slot is filled but the
  referenced child instance is missing from `:conv/instances`
  (kernel-state corruption, not a user error).  Returns the default
  hidden placeholder when the child component has no `:render` of its
  own."
  ([self slot-key]
   (render-slot self slot-key
                (or (resolve 'dev.zeko.stube.registry/lookup!)
                    (throw (ex-info "dev.zeko.stube.registry not loaded" {})))))
  ([self slot-key lookup!]
   (let [conv      (or *conv*
                       (throw (ex-info "dev.zeko.stube.render/*conv* is unbound; render-slot cannot resolve children"
                                       {:slot slot-key
                                        :parent (:instance/id self)})))
         child-iid (get-in self [:instance/children slot-key])]
     (when child-iid
       (let [child     (or (get-in conv [:conv/instances child-iid])
                           (throw (ex-info "Slot child instance is missing from conv"
                                           {:slot      slot-key
                                            :child-iid child-iid})))
             lookup-fn (if (var? lookup!) @lookup! lookup!)
             cdef      (lookup-fn (:instance/type child))
             render-fn (or (:component/render cdef)
                           (fn default [s]
                             [:div {:id (:instance/id s) :hidden true}]))]
         (cond-> (render-fn child)
           (:conv/halos? conv) (halos/decorate-root child)))))))

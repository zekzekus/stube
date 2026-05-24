(ns dev.zeko.stube.fragments
  "Fragment data shapes and the one Datastar SSE translator.

  A *fragment* is the kernel's wire-format-neutral way of saying \"push
  this to the browser\":

      {:fragment/kind :elements
       :fragment/html \"<form id=ix-001>…</form>\"
       :fragment/opts {…patch-elements! options…}}

      {:fragment/kind :signals
       :fragment/data {:foo 1}
       :fragment/opts {}}

      {:fragment/kind :script
       :fragment/script \"alert('hi')\"
       :fragment/opts {}}

      {:fragment/kind :error
       :fragment/html \"<div id=ix-7e2 class=stube-error …>…</div>\"
       :fragment/opts {:selector \"#ix-7e2\" :patch-mode :outer}}

      {:fragment/kind :close}

  The kernel never touches Datastar; it just produces these maps.  This
  namespace is the **single Datastar SDK boundary** — it owns the patch-
  mode keyword translation and turns fragments into SSE events.  Before
  it existed, the translator was duplicated between [[dev.zeko.stube.http]] and
  [[dev.zeko.stube.server]]."
  (:require [charred.api                               :as json]
            [starfederation.datastar.clojure.api       :as d*]
            [starfederation.datastar.clojure.protocols :as sse-protocols]))

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(defn elements
  "Build an `:elements` fragment.  `opts` is a kernel-level options map
  (`{:selector ... :patch-mode ...}`); leave it empty to let Datastar
  morph by id."
  ([html]      (elements html {}))
  ([html opts] {:fragment/kind :elements
                :fragment/html html
                :fragment/opts opts}))

(defn signals
  "Build a `:signals` fragment from a Clojure map."
  [m]
  {:fragment/kind :signals
   :fragment/data m
   :fragment/opts {}})

(defn script
  "Build an `:execute-script` fragment."
  [js]
  {:fragment/kind :script
   :fragment/script js
   :fragment/opts {}})

(defn error
  "Build an `:error` fragment.  Wire-equivalent to `:elements`, but
  tagged so observability layers and tests can distinguish a component
  failure from a normal render."
  ([html]      (error html {}))
  ([html opts] {:fragment/kind :error
                :fragment/html html
                :fragment/opts opts}))

(defn history-script
  "Build a `:script` fragment that pushes or replaces the browser URL.

  `mode` is `:replace` or `:push`; `url` is the new URL string.
  Translates to a one-shot `history.replaceState` / `history.pushState`
  call so no new SSE event type is needed."
  [mode url]
  (let [method (case mode
                 :replace "replaceState"
                 :push    "pushState"
                 (throw (ex-info "history-script: unknown mode" {:mode mode})))]
    (script (str "history." method "(null,''," (pr-str (str url)) ")"))))

(def close
  "Tell the SSE channel to close.  Used after `:end`."
  {:fragment/kind :close})

;; ---------------------------------------------------------------------------
;; Datastar SSE translation
;; ---------------------------------------------------------------------------

(def ^:private write-json
  (json/write-json-fn {}))

(defn- json-str ^String [m]
  (let [w (java.io.StringWriter.)]
    (write-json w m)
    (str w)))

(def ^:private patch-modes
  "Translate kernel keyword patch modes to Datastar string constants."
  {:outer   d*/pm-outer
   :inner   d*/pm-inner
   :remove  d*/pm-remove
   :prepend d*/pm-prepend
   :append  d*/pm-append
   :before  d*/pm-before
   :after   d*/pm-after
   :replace d*/pm-replace})

(defn- elements-opts
  "Translate the kernel's wire-agnostic options map into the Datastar
  SDK's namespaced-keyword option keys."
  [{:keys [selector patch-mode]}]
  (cond-> {}
    selector   (assoc d*/selector   selector)
    patch-mode (assoc d*/patch-mode (or (patch-modes patch-mode)
                                        (throw (ex-info "Unknown patch-mode"
                                                        {:patch-mode patch-mode}))))))

(defn- push-fragment!
  "Translate one fragment into one Datastar SSE event."
  [sse-gen {:fragment/keys [kind html data script opts]}]
  (case kind
    :elements (d*/patch-elements! sse-gen html (elements-opts opts))
    :error    (d*/patch-elements! sse-gen html (elements-opts opts))
    :signals  (d*/patch-signals!  sse-gen (json-str data) (or opts {}))
    :script   (d*/execute-script! sse-gen script (or opts {}))
    :close    (d*/close-sse! sse-gen)))

(defn push!
  "Push a sequence of fragments to an open Datastar SSE generator,
  holding the SSE lock so concurrent pushes never interleave."
  [sse-gen fragments]
  (when (seq fragments)
    (d*/lock-sse! sse-gen
      (doseq [f fragments]
        (push-fragment! sse-gen f)))))

(defn push-keep-alive!
  "Write a no-op SSE event to keep the connection alive across
  reverse-proxy idle timeouts.  Datastar ignores any event type it
  does not know, so the client sees no DOM change; only the proxy
  sees activity on the channel.

  Returns true on a successful write, false when the underlying
  channel is already closed (which is the signal to stop scheduling
  further heartbeats)."
  [sse-gen]
  (try
    (d*/lock-sse! sse-gen
      (sse-protocols/send-event! sse-gen "stube-keepalive" [""] {}))
    true
    (catch Throwable _ false)))

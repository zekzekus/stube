(ns dev.zeko.stube.dev
  "Dev-time conveniences gated by the `stube.dev` system property.

  Currently exposes one thing: post-handler state-shape validation via
  optional Malli schemas declared on `:component/state` (see S-9).
  Production runs incur the cost of a single `(when @dev-mode? …)`
  check, so the validation pipeline can sit unconditionally inside
  `kernel/dispatch` without bloating the hot path.

  Enable validation by either

      -Dstube.dev=true     ; system property
      STUBE_DEV=true       ; environment variable

  or by binding [[*enabled?*]] true at the REPL.

  Malli stays an optional dependency: the namespace is loaded via
  `requiring-resolve` only when a schema is actually present *and*
  dev mode is on.  Apps that never declare a schema, or never enable
  dev mode, do not need Malli on the classpath."
  (:require [clojure.string :as str]))

(def ^:private property-flag?
  (delay
    (or (= "true" (str/lower-case (or (System/getProperty "stube.dev") "")))
        (= "true" (str/lower-case (or (System/getenv "STUBE_DEV") ""))))))

(def ^:dynamic *enabled?*
  "Override the system-property-driven default at the REPL or in tests."
  nil)

(defn enabled?
  "True when component-state schema validation should fire after every
  handler return."
  []
  (if (some? *enabled?*) *enabled?* @property-flag?))

(def ^:private instance-keys
  #{:instance/id
    :instance/type
    :instance/parent
    :instance/resume
    :instance/rendered?
    :instance/children
    :instance/keyed-slots
    :instance/slot
    :instance/previous
    :instance/last-html
    :stube/context})

(defn user-state
  "Slice `self` down to the user-defined keys — the portion a
  `:component/state` schema is meant to validate."
  [self]
  (reduce dissoc self instance-keys))

(defn- malli-validate-fn []
  (or (requiring-resolve 'malli.core/validate)
      (throw (ex-info "stube dev validation needs malli on the classpath"
                      {:dep 'metosin/malli}))))

(defn- malli-explain-fn []
  (or (requiring-resolve 'malli.core/explain)
      (constantly nil)))

(defn validate!
  "Validate `self'` against the component's `:component/state` schema if
  one is declared *and* dev mode is enabled.  Throws an `ex-info`
  carrying the Malli explainer output on failure.  Returns `self'`
  unchanged on success or when validation is skipped."
  [cdef self' phase]
  (when (and (enabled?) cdef (:component/state cdef))
    (let [schema (:component/state cdef)
          value  (user-state self')
          valid? (malli-validate-fn)]
      (when-not (valid? schema value)
        (let [explain (malli-explain-fn)
              report  (try (explain schema value) (catch Throwable _ nil))]
          (throw (ex-info (str "stube " (name phase) " return failed :component/state schema "
                               "for component " (or (:component/id cdef)
                                                    (:instance/type self')))
                          {:stube.dev/component (:component/id cdef)
                           :stube.dev/iid       (:instance/id self')
                           :stube.dev/phase     phase
                           :stube.dev/value     value
                           :stube.dev/explain   report}))))))
  self')

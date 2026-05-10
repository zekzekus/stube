(ns stube.registry
  "The component registry.

  In stube, a *component* is a plain map of the form

      {:component/id      :auth/login
       :component/doc     \"Prompt for credentials.\"
       :component/init    (fn [args] state-map)
       :component/render  (fn [self]  hiccup)
       :component/handle  (fn [self event] [self' effects])
       :component/keep    #{:signal-keys}
       :on-foo            (fn [self answer-value] [self' effects])
       …}

  Behaviour lives in the values; the key under which the kernel finds them
  is by namespaced convention.  Resume keys (`:on-foo`, `:on-step-3`, …)
  are looked up dynamically when an `[:answer …]` effect pops a child
  frame: the parent's `:instance/resume` value names the function to call.

  The registry maps `:component/id` to the component map.  It is held in a
  single atom so component definitions can be evaluated at namespace load
  time the same way `defmulti` defmethods are."
  (:refer-clojure :exclude [register]))

;; A single global registry.  Re-defining a component (e.g. during REPL
;; iteration) replaces the previous entry by id.
(defonce ^:private !components (atom {}))

;; Only `:component/id` is strictly required.  All lifecycle keys
;; (`:component/init`, `:component/render`, `:component/handle`,
;; `:component/keep`) are optional so that "task" components that exist
;; only to sequence other components stay terse.  The kernel falls back
;; to sensible no-op defaults at use sites.

(defn- validate!
  "Throw an `ex-info` if `cdef` is malformed."
  [cdef]
  (when-not (map? cdef)
    (throw (ex-info "Component definition must be a map."
                    {:got cdef})))
  (when-not (contains? cdef :component/id)
    (throw (ex-info "Component definition is missing :component/id."
                    {:got cdef})))
  (when-not (qualified-keyword? (:component/id cdef))
    (throw (ex-info "Component :component/id must be a namespaced keyword."
                    {:component/id (:component/id cdef)})))
  cdef)

(defn register!
  "Add or replace a component definition.  Returns the registered map."
  [cdef]
  (let [cdef (validate! cdef)]
    (swap! !components assoc (:component/id cdef) cdef)
    cdef))

(defn lookup
  "Return the component map for `id`, or nil if none is registered."
  [id]
  (get @!components id))

(defn lookup!
  "Like [[lookup]] but throws if the component is unknown.  Used by the
  kernel where a missing component is an unrecoverable bug."
  [id]
  (or (lookup id)
      (throw (ex-info (str "No component registered under " id)
                      {:component/id id
                       :known        (vec (sort (keys @!components)))}))))

(defn all
  "Snapshot of every registered component, keyed by id.  Handy from the
  REPL; not used internally."
  []
  @!components)

(defn help
  "Return the docstring registered for component `id`, or nil."
  [id]
  (:component/doc (lookup id)))

(defn clear!
  "Drop every component from the registry.  Intended for tests."
  []
  (reset! !components {})
  nil)

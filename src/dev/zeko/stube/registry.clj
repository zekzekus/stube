(ns dev.zeko.stube.registry
  "The component registry.

  In stube, a *component* is a plain map of the form

      {:component/id       :auth/login
       :component/doc      \"Prompt for credentials.\"
       :component/init     (fn [args] state-map)
       :component/render   (fn [self]  hiccup)
       :component/handle   (fn [self event] [self' effects])
       :component/keep     #{:signal-keys}
       :component/start    (fn [self] [self' effects])
       :component/stop     (fn [self] [self' effects])
       :component/wakeup   (fn [self] [self' effects])
       :component/children {:slot/x embed-spec}
       :on-foo             (fn [self answer-value] [self' effects])
       …}

  Behaviour lives in the values; the key under which the kernel finds them
  is by namespaced convention.  Resume keys (`:on-foo`, `:on-step-3`, …)
  are looked up dynamically when an `[:answer …]` effect pops a child
  frame: the parent's `:instance/resume` value names the function to call.

  Author keys (`:init`, `:render`, `:handle`, `:keep`, `:doc`, `:state`,
  `:start`, `:stop`, `:wakeup`, `:children`) are lifted to their
  `:component/<name>` homes by [[register!]] so every cdef the kernel
  reads has a single uniform namespace.

  The registry maps `:component/id` to the component map.  It is held in a
  single atom so component definitions can be evaluated at namespace load
  time the same way `defmulti` defmethods are.")

;; A single global registry.  Re-defining a component (e.g. during REPL
;; iteration) replaces the previous entry by id.
(defonce ^:private !components (atom {}))

(def ^:private colocated-keys
  "Component-author-facing keys lifted to `:component/<name>` at
  registration time so the kernel finds them under a single namespace.
  Resume keys (`:on-foo`, etc.) are not on this list — they pass through
  verbatim because the kernel looks them up by exact name."
  [:init :render :handle :keep :doc :state
   :start :stop :wakeup :children :url])

(defn- lift-colocated [cdef]
  (reduce (fn [m k]
            (if (contains? m k)
              (-> m
                  (assoc (keyword "component" (name k)) (get m k))
                  (dissoc k))
              m))
          cdef
          colocated-keys))

(defn- lift-emit-on-mount
  "Lift `:emit-on-mount` to `:component/start` (sugar for the
  effect-only `:start` case).  Declaring both is a registration-time
  error so the collision can't go unnoticed."
  [cdef]
  (if-not (contains? cdef :emit-on-mount)
    cdef
    (do
      (when (contains? cdef :component/start)
        (throw (ex-info ":emit-on-mount and :start may not be declared on the same component"
                        {:component/id (:component/id cdef)})))
      (-> cdef
          (assoc :component/start (:emit-on-mount cdef))
          (dissoc :emit-on-mount)))))

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
  "Add or replace a component definition.  Returns the registered map.

  Colocated author keys (`:init`, `:render`, `:handle`, `:keep`, `:doc`,
  `:state`, `:start`, `:stop`, `:wakeup`, `:children`) are lifted to
  `:component/<name>` here, so the kernel can read every cdef under a
  single namespace regardless of which entry point produced it
  (`defcomponent` macro, `register-component!` function, or
  `decorate!`)."
  [cdef]
  (let [cdef (-> cdef validate! lift-colocated lift-emit-on-mount)]
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

(ns dev.zeko.stube.errors
  "In-page error reporting for component throws.

  When a `:render` or `:handle` fn throws, the kernel and frame layers
  catch here instead of letting the exception bubble into the SSE
  handler.  The conversation is left untouched; an `:error` fragment
  patches a banner in place of the failing instance's DOM node so the
  user sees what went wrong and can keep interacting with the page.

  The optional `:on-error` hook configured on
  [[dev.zeko.stube.kernel/make-kernel]] is invoked with `(conv
  throwable)` and may return its own fragment to display instead of
  the default banner.  The throwable handed to the hook is wrapped in
  an `ex-info` carrying `:stube.error/iid` and `:stube.error/phase`
  (`:render` or `:handle`)."
  (:require [dev.onionpancakes.chassis.core :as chassis]
            [dev.zeko.stube.fragments        :as f]
            [dev.zeko.stube.render           :as render]))

(def ^:dynamic *on-error*
  "Optional `(fn [conv throwable])` returning a fragment to patch in
  place of the failing instance.  Bound by the runtime layer from
  `make-kernel`'s `:on-error` option."
  nil)

(defn- wrap [cid iid throwable phase]
  (ex-info (or (ex-message throwable)
               (.getName ^Class (class throwable)))
           {:stube.error/cid   cid
            :stube.error/iid   iid
            :stube.error/phase phase}
           throwable))

(defn default-fragment
  "Build the stock error banner targeting `iid`.  An HTML comment in
  the patched markup carries the cid/iid/phase so the surface stays
  identifiable in browser devtools."
  ([cid iid throwable phase]
   (default-fragment cid iid throwable phase
                     {:selector (str "#" iid) :patch-mode :outer}))
  ([cid iid throwable phase opts]
  (f/error
    (render/html
      [:div {:id              iid
             :class           "stube-error"
             :role            "alert"
             :data-stube-error "true"}
       (chassis/raw (str "<!-- stube error: cid=" cid
                         " iid=" iid
                         " phase=" (name phase) " -->"))
       [:strong {:class "stube-error-title"} "Component error"]
       " "
       [:span {:class "stube-error-message"}
        (or (ex-message throwable)
            (.getName ^Class (class throwable)))]])
    opts)))

(defn- fragment-opts
  "Target the existing instance root for post-render errors; target the
  shell root for first-render failures where `#iid` does not exist yet."
  [conv iid]
  (if (get-in conv [:conv/instances iid :instance/rendered?])
    {:selector (str "#" iid) :patch-mode :outer}
    {:selector render/*root-selector* :patch-mode :inner}))

(defn- log-component-error! [cid iid phase ^Throwable t]
  (binding [*out* *err*]
    (println (str "stube component error"
                  " cid=" cid
                  " iid=" iid
                  " phase=" (name phase)
                  " — " (ex-message t)))))

(defn build-fragment
  "Resolve the error fragment for an in-flight component failure.
  Invokes `*on-error*` if installed; falls back to [[default-fragment]].
  Also logs the cid/iid/phase to stderr so production failures show up
  in server logs."
  [conv iid throwable phase]
  (let [cid     (:conv/id conv)
        wrapped (wrap cid iid throwable phase)]
    (log-component-error! cid iid phase wrapped)
    (or (when-let [hook *on-error*]
          (try (hook conv wrapped)
               (catch Throwable t
                 (binding [*out* *err*]
                   (println "stube :on-error hook threw —" (ex-message t)))
                 nil)))
        (default-fragment cid iid wrapped phase (fragment-opts conv iid)))))

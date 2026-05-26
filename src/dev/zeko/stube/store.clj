(ns dev.zeko.stube.store
  "Pluggable persistence for conversation values.

  A conversation is just a map of plain Clojure data (see
  [[dev.zeko.stube.conversation]]).  Slice 3 adds a small protocol for swapping
  out the storage backend without touching the kernel:

      (s/start! {:port  8080
                 :store (dev.zeko.stube.store/file-store \"/var/lib/stube/convs\")})

  Three operations are enough:

  | op           | when                                        |
  |--------------|---------------------------------------------|
  | `load-all`   | once at startup, to repopulate memory       |
  | `save!`      | after every successful `swap-conv!`         |
  | `delete!`    | when a conversation ends (`:end`, reaper)   |

  The default is [[in-memory-store]], which keeps the slice-0 behaviour
  unchanged: the in-process conversation atom on the active
  `dev.zeko.stube.runtime` kernel is the only copy of the truth and
  `save!` is a no-op.

  ----------------------------------------------------------------------
  defflow is in-memory only — by design
  ----------------------------------------------------------------------

  `dev.zeko.stube.flow` continuations are live cloroutine objects, not
  EDN values.  A conversation that contains a `defflow` instance is
  therefore not durable: on a clean restart its on-disk copy is gone
  (the [[file-store]] logs a warning and skips the save).

  This is a deliberate property of the framework, not a gap.  `defflow`
  is the ergonomic for transient flows — wizards a user completes in
  one sitting, multi-step UIs whose value comes from the linear-code
  shape.  If the flow needs to survive a deploy or a process crash,
  write it as a hand-rolled task component instead: a `:start` hook
  plus named resume keys threads the same state through an EDN-clean
  map, and persists transparently through this store.  See the
  *Durable flows: defflow vs. task components* section of the tutorial
  for a side-by-side example.

  The kernel does not refuse to register or run a `defflow`-containing
  conversation against a [[file-store]]; the live behaviour is normal.
  Only the durable copy is skipped, and the warning fires so you can
  feel the boundary."
  (:require [clojure.edn  :as edn]
            [clojure.java.io :as io])
  (:import (java.time Instant)))

;; ---------------------------------------------------------------------------
;; Make `java.time.Instant` round-trip through EDN
;; ---------------------------------------------------------------------------
;;
;; `dev.zeko.stube.conversation` stamps every conversation with `Instant/now` for
;; `:conv/created` / `:conv/touched`.  By default Clojure prints an
;; `Instant` as `#object[java.time.Instant 0x… "…"]`, which is not a
;; tagged literal the EDN reader can read back.  Without a fix, EVERY
;; live conversation would be flagged as non-EDN by `safe-printable?`
;; and never persisted.
;;
;; We register a `print-method` that emits the same `#inst "…"` tag
;; Clojure already uses for `java.util.Date`, and below we hand the EDN
;; reader an `:inst` reader that builds an `Instant` back.  The disk
;; format is therefore plain `#inst "2026-…"` — nothing exotic, nothing
;; tied to JVM internals.

(defmethod print-method Instant [^Instant v ^java.io.Writer w]
  (.write w "#inst \"")
  (.write w (.toString v))
  (.write w "\""))

(defmethod print-dup Instant [v w] (print-method v w))

(def ^:private edn-readers
  {'inst (fn [s] (Instant/parse s))})

;; ---------------------------------------------------------------------------
;; The protocol
;; ---------------------------------------------------------------------------

(defprotocol ConversationStore
  "Backend for persisting conversation values.  See namespace docstring."
  (load-all [this]
    "Return a map `{cid → conv}` of every persisted conversation.
    Called once at server startup before the http listener accepts
    requests.")
  (save! [this conv]
    "Atomically replace the persisted value for `(:conv/id conv)`.
    Returns `conv` on success.  May log + return the conv unchanged
    on a non-fatal serialisation problem; should throw only on a
    backend-level failure (disk full, etc.).")
  (delete! [this cid]
    "Remove the persisted conversation with id `cid`.  Idempotent."))

;; ---------------------------------------------------------------------------
;; In-memory: no-op store, the default
;; ---------------------------------------------------------------------------

(defn in-memory-store
  "The default store.  Keeps no copy of its own — the runtime kernel's
  in-process conversation atom IS the source of truth — and treats every
  operation as a no-op.  Use this for tests, REPL iteration, and any
  deployment where you genuinely don't need crash-resume."
  []
  (reify ConversationStore
    (load-all [_] {})
    (save!    [_ conv] conv)
    (delete!  [_ _cid] nil)))

;; ---------------------------------------------------------------------------
;; File-per-cid EDN store
;; ---------------------------------------------------------------------------

(defn- cid-file ^java.io.File [^java.io.File dir cid]
  ;; Cids are minted by us and only contain hex + `cv-`, so they are safe
  ;; filename characters without further escaping.
  (io/file dir (str cid ".edn")))

(defn- safe-printable?
  "True if `v` survives a `pr-str` round trip without losing meaning.

  Implementation: print `v` and look for Clojure's catch-all
  `#object[…]` marker.  That marker is what `pr-str` emits for any
  value lacking a faithful printable representation (functions,
  cloroutine continuations, opaque Java objects, …).  Anything with a
  proper tagged-literal — `#inst`, `#uuid`, our own opt-in tags — comes
  out clean and parses back via `clojure.edn/read-string`.

  We deliberately do NOT call `read-string` here: doing so would either
  reject perfectly fine tagged literals we haven't pre-declared, or
  silently accept the very `#object[…]` forms we want to reject.  A
  string-level scan is both stricter and cheaper."
  [v]
  (try
    (let [s (binding [*print-dup* false] (pr-str v))]
      (not (re-find #"#object\[" s)))
    (catch Throwable _ false)))

(defn file-store
  "Persist every conversation as one EDN file in `dir`.  The directory
  is created if it does not exist.

      (file-store \"./conv-store\")

  Files are named `<cid>.edn`.  Writes go to a sibling temp file and
  are renamed atomically, so a crash mid-write never leaves a partial
  read on disk.  Reads use `clojure.edn/read-string` with no eval and
  the default tagged-literal handler, so a corrupted file is the worst
  attacker reach.

  If a conversation contains values that are not EDN-printable (almost
  always a `dev.zeko.stube.flow` cloroutine continuation), the store
  logs a warning to `*err*` and *skips* the save without raising.  The
  conversation stays live in memory; only its on-disk copy is stale.
  This is the documented `defflow` boundary — see the namespace
  docstring for how to write a durable equivalent."
  [dir]
  (let [^java.io.File dir-file (io/file dir)]
    (.mkdirs dir-file)
    (reify ConversationStore
      (load-all [_]
        ;; `keep` swallows the `nil` returned for files that fail to
        ;; parse, so a single corrupt file never poisons `into {}`.
        (into {}
              (keep (fn [^java.io.File f]
                      (when (.endsWith (.getName f) ".edn")
                        (try
                          (let [conv (edn/read-string
                                       {:readers edn-readers
                                        :default tagged-literal}
                                       (slurp f))]
                            ;; A successfully-parsed conversation must
                            ;; at minimum be a map with a string `:conv/id`.
                            ;; Anything else is junk masquerading as EDN.
                            (when-not (and (map? conv)
                                           (string? (:conv/id conv)))
                              (throw (ex-info "not a conversation map"
                                              {:got conv})))
                            [(:conv/id conv) conv])
                          (catch Throwable t
                            (binding [*out* *err*]
                              (println "dev.zeko.stube.store: skipping unreadable file"
                                       (.getPath f) "—" (ex-message t)))
                            nil))))
                    (.listFiles dir-file))))

      (save! [_ conv]
        (let [cid  (:conv/id conv)
              dst  (cid-file dir-file cid)
              tmp  (io/file dir-file (str cid ".edn.tmp"))]
          (if-not (safe-printable? conv)
            (binding [*out* *err*]
              (println (str "dev.zeko.stube.store: conv " cid
                            " contains non-EDN values (almost certainly a "
                            "defflow continuation). Skipping disk save; the "
                            "conversation stays live in memory but will not "
                            "survive a restart. Rewrite as a hand-rolled "
                            "task component (`:start` + named resume keys) "
                            "to make it durable — see the store ns docstring.")))
            (do (spit tmp (binding [*print-dup* false] (pr-str conv)))
                (.renameTo tmp dst)))
          conv))

      (delete! [_ cid]
        (let [f (cid-file dir-file cid)]
          (when (.exists f) (.delete f))
          nil)))))

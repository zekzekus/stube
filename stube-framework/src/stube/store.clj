(ns stube.store
  "Pluggable persistence for conversation values.

  A conversation is just a map of plain Clojure data (see
  [[stube.conversation]]).  Slice 3 adds a small protocol for swapping
  out the storage backend without touching the kernel:

      (s/start! {:port  8080
                 :store (stube.store/file-store \"/var/lib/stube/convs\")})

  Three operations are enough:

  | op           | when                                        |
  |--------------|---------------------------------------------|
  | `load-all`   | once at startup, to repopulate memory       |
  | `save!`      | after every successful `swap-conv!`         |
  | `delete!`    | when a conversation ends (`:end`, reaper)   |

  The default is [[in-memory-store]], which keeps the slice-0 behaviour
  unchanged: the in-process atom in `stube.server` is the only copy of
  the truth and `save!` is a no-op.

  ──────────────────────────────────────────────────────────────────────
  Cloroutine and persistence
  ──────────────────────────────────────────────────────────────────────

  `stube.flow` continuations are stateful objects, not EDN values.  A
  conversation that contains a live `defflow` instance therefore can't
  go through the EDN file store as-is; the store will log a warning
  and skip that conversation.  Hand-rolled task components from slice
  0 (the `:start` + resume-key pattern) ARE EDN-clean and persist
  perfectly.  Closing this gap is open work for a later slice."
  (:require [clojure.edn  :as edn]
            [clojure.java.io :as io])
  (:import (java.time Instant)))

;; ---------------------------------------------------------------------------
;; Make `java.time.Instant` round-trip through EDN
;; ---------------------------------------------------------------------------
;;
;; `stube.conversation` stamps every conversation with `Instant/now` for
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
  "The default store.  Keeps no copy of its own — `stube.server`'s
  in-process atom IS the source of truth — and treats every operation
  as a no-op.  Use this for tests, REPL iteration, and any deployment
  where you genuinely don't need crash-resume."
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

  If a conversation contains values that are not EDN-printable (the
  most common cause is a `stube.flow` cloroutine continuation), the
  store logs a warning to `*err*` and *skips* the save without raising.
  The conversation stays live in memory; only its on-disk copy is
  stale."
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
                              (println "stube.store: skipping unreadable file"
                                       (.getPath f) "—" (ex-message t)))
                            nil))))
                    (.listFiles dir-file))))

      (save! [_ conv]
        (let [cid  (:conv/id conv)
              dst  (cid-file dir-file cid)
              tmp  (io/file dir-file (str cid ".edn.tmp"))]
          (if-not (safe-printable? conv)
            (binding [*out* *err*]
              (println "stube.store: conv" cid
                       "contains non-EDN values (e.g. a defflow continuation);"
                       "skipping disk save."))
            (do (spit tmp (binding [*print-dup* false] (pr-str conv)))
                (.renameTo tmp dst)))
          conv))

      (delete! [_ cid]
        (let [f (cid-file dir-file cid)]
          (when (.exists f) (.delete f))
          nil)))))

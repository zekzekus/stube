(ns dev.zeko.stube.security
  "Host-facing security helpers.

  stube emits its own responses, so a handful of protections live in the
  kernel (unguessable cids, bounded parsing, `Secure` cookies, CSRF
  tokens — see `docs/security.md`).  But the *response headers* that
  harden a page against framing, sniffing, and referrer leakage are a
  host decision: only the host knows its CDN origins, analytics, and
  framing policy.  This namespace gives the host a baseline it can opt
  into and tune.

  Wrap your stube ring handler:

      (-> (embed/ring-handler k {:mounts ...})
          (security/wrap-defaults))

  or, with a Content-Security-Policy and a tweak:

      (security/wrap-defaults handler
        {:csp     (security/content-security-policy
                    {:default-src \"'self'\"
                     :script-src  [\"'self'\" \"https://cdn.jsdelivr.net\"]})
         :headers {\"X-Frame-Options\" nil}})   ; drop a default"
  (:require [clojure.string :as str]))

(def default-headers
  "Baseline security response headers [[wrap-defaults]] adds.

  - `X-Content-Type-Options: nosniff` — no MIME sniffing.
  - `Referrer-Policy: same-origin` — don't leak the cid in a referer.
  - `X-Frame-Options: SAMEORIGIN` — block cross-origin framing (relevant
    because a clickjacked GET can mint a conversation).  Supersede with
    a CSP `frame-ancestors` directive if you need finer control.
  - `Cross-Origin-Opener-Policy: same-origin` — isolate the browsing
    context group.
  - `Permissions-Policy` — deny powerful features by default."
  {"X-Content-Type-Options"     "nosniff"
   "Referrer-Policy"            "same-origin"
   "X-Frame-Options"            "SAMEORIGIN"
   "Cross-Origin-Opener-Policy" "same-origin"
   "Permissions-Policy"         "geolocation=(), microphone=(), camera=()"})

(defn content-security-policy
  "Build a `Content-Security-Policy` header value from a `directives`
  map.  Keys are directive names (keyword or string); values are a
  string or a sequence of source tokens.

      (content-security-policy
        {:default-src \"'self'\"
         :script-src  [\"'self'\" \"https://cdn.jsdelivr.net\"]
         :frame-ancestors \"'none'\"})
      ;; => \"default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; frame-ancestors 'none'\"

  Note: the stube shell uses an inline `data-init` attribute and
  Datastar uses inline `data-on:*` attributes, so a strict policy needs
  a per-render nonce (or hash) rather than blanket `unsafe-inline`.  See
  the CSP section of `docs/security.md`."
  [directives]
  (->> directives
       (map (fn [[k v]]
              (let [tokens (if (sequential? v) v [v])]
                (str/trim (str (name k) " " (str/join " " (map str tokens)))))))
       (str/join "; ")))

(defn- add-missing-headers
  "Add `headers` to `resp` without clobbering ones the handler already
  set, and only when `resp` is a response map."
  [resp headers]
  (if (map? resp)
    (update resp :headers
            (fn [h] (reduce-kv (fn [m k v] (if (contains? m k) m (assoc m k v)))
                               (or h {})
                               headers)))
    resp))

(defn wrap-defaults
  "Ring middleware that adds [[default-headers]] (and an optional CSP) to
  every response, without overwriting headers the handler already set.

  Options:

  - `:headers` — a map merged over [[default-headers]].  A value of
    `nil` *removes* that default (e.g. `{\"X-Frame-Options\" nil}` when
    you drive framing through a CSP `frame-ancestors` instead).
  - `:csp` — a `Content-Security-Policy` value (see
    [[content-security-policy]]).

  Works for both synchronous (1-arg) and asynchronous (3-arg) Ring
  handlers."
  ([handler] (wrap-defaults handler {}))
  ([handler {:keys [headers csp]}]
   (let [merged (cond-> (merge default-headers headers)
                  csp (assoc "Content-Security-Policy" csp))
         ;; A nil value (from `:headers`) means \"remove this default\".
         final  (into {} (remove (comp nil? val)) merged)]
     (fn
       ([req]
        (add-missing-headers (handler req) final))
       ([req respond raise]
        (handler req #(respond (add-missing-headers % final)) raise))))))

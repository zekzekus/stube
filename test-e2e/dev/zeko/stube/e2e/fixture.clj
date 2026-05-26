(ns dev.zeko.stube.e2e.fixture
  "Process-wide harness for browser-driven e2e tests.

  One JVM, one http-kit server, one Chromium browser — every test file
  uses [[ensure-up!]] as a `:once` fixture and grabs a fresh
  BrowserContext per test through [[with-page]].  Booting Chromium
  costs ~1s; restarting it per test would dominate runtime.

  Env vars:
    STUBE_E2E_PORT    bind port (default 18080)
    STUBE_E2E_HEADED  if \"1\", run with a visible browser

  Note on `:demo/example-browser`: it self-mounts at `/` and
  `/example-browser`, and its catalogue lists itself.  Clicking its
  nav entry only swaps in a :demo/example-detail panel, so that path
  is safe; the recursive trap is the entry's \"Open standalone\" link,
  which navigates back into another browser frame.  Any test that
  iterates entries must skip path `/example-browser`."
  (:require [dev.zeko.stube.core :as s]
            ;; Side-effecting require: mounts every shipped example.
            [dev.zeko.stube.examples.example-browser])
  (:import (com.microsoft.playwright Browser
                                     BrowserContext
                                     BrowserType$LaunchOptions
                                     Page
                                     Playwright)))

(def port
  (Integer/parseInt (or (System/getenv "STUBE_E2E_PORT") "18080")))

(def base-url (str "http://localhost:" port))

(defonce ^:private !state (atom nil))

(defn- start! []
  (s/start! {:port port :ui-css? true})
  (println "e2e mounts:" (sort (keys (s/mounts))))
  (let [pw      (Playwright/create)
        opts    (-> (BrowserType$LaunchOptions.)
                    (.setHeadless (not= "1" (System/getenv "STUBE_E2E_HEADED"))))
        browser (.launch (.chromium pw) opts)]
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable
               #(do (try (.close browser) (catch Throwable _))
                    (try (.close pw)      (catch Throwable _))
                    (try (s/stop!)        (catch Throwable _)))))
    {:pw pw :browser browser}))

(defn ensure-up!
  "`:once` fixture — boots the server + browser exactly once per JVM."
  [t]
  (swap! !state #(or % (start!)))
  (t))

(defn browser
  {:tag `Browser}
  []
  (or (:browser @!state)
      (throw (ex-info "e2e harness not started — add (use-fixtures :once ensure-up!)"
                      {}))))

(defn attach-listeners!
  "Record browser-side telemetry on `page` into `log-atom`: console
  messages, page errors, failed requests, and SSE/Datastar-related
  responses.  Lets us tell a CDN-blocked script from a JS error from
  a handshake that never connected.

  Public so the `with-page` macro can expand a call to it from any
  caller namespace; not meant to be called by hand."
  [^Page page log-atom]
  (.onConsoleMessage page
                     (reify java.util.function.Consumer
                       (accept [_ msg]
                         (swap! log-atom update :console (fnil conj [])
                                {:type (.type msg) :text (.text msg)}))))
  (.onPageError page
                (reify java.util.function.Consumer
                  (accept [_ err]
                    (swap! log-atom update :errors (fnil conj []) (str err)))))
  (.onRequestFailed page
                    (reify java.util.function.Consumer
                      (accept [_ req]
                        (swap! log-atom update :failed (fnil conj [])
                               {:url (.url req)
                                :failure (some-> req .failure)}))))
  (.onResponse page
               (reify java.util.function.Consumer
                 (accept [_ resp]
                   (let [u (.url resp)]
                     (when (or (re-find #"datastar|/sse/|preserve|multicounter|url-counter" u)
                               (>= (.status resp) 400))
                       (swap! log-atom update :responses (fnil conj [])
                              {:url u :status (.status resp)
                               :set-cookie (.headerValue resp "set-cookie")}))))))
  (.onRequest page
              (reify java.util.function.Consumer
                (accept [_ req]
                  (when (re-find #"/sse/" (.url req))
                    (swap! log-atom update :sse-requests (fnil conj [])
                           {:url     (.url req)
                            :method  (.method req)
                            :cookie  (.headerValue req "cookie")}))))))

(defmacro with-page
  "Open a fresh BrowserContext (isolated cookies → fresh stube_sid),
  navigate to `path`, wait for the first SSE patch to land, bind the
  page to `page-sym`, run `body`, then close the context.

  The shell only serves `<div id=\"root\"></div>`; the component's
  initial HTML arrives over SSE after Datastar's `@get(/sse/:cid)` runs.
  We wait for `#root` to gain any child element before proceeding.

  `page-sym/log` is exposed as a sibling binding holding the telemetry
  atom; `dump-page` reads it on test failure."
  [[page-sym path] & body]
  (let [log-sym (symbol (str page-sym "-log"))]
    `(let [ctx#      (.newContext (browser))
           ~page-sym (.newPage ctx#)
           ~log-sym  (atom {})]
       (attach-listeners! ~page-sym ~log-sym)
       (try
         (let [resp# (.navigate ~page-sym (str base-url ~path))
               st#   (some-> resp# .status)]
           (when-not (and st# (= 200 st#))
             (throw (ex-info (str "navigation to " ~path " returned status " st#)
                             {:url    (.url ~page-sym)
                              :status st#}))))
         (try
           (.waitFor (.locator ~page-sym "#root > *"))
           (catch Throwable t#
             (println "── #root never populated; browser log dump ──")
             (println "script srcs:" (.evaluate ~page-sym
                                                "() => [...document.scripts].map(s => s.src || '[inline]')"))
             (println "cookies:    " (.cookies (.context ~page-sym)))
             (println "telemetry:  " (pr-str @~log-sym))
             (throw t#)))
         ~@body
         (finally (.close ^BrowserContext ctx#))))))

(defn dump-page
  "Print URL, title, body excerpt — call from a failing test."
  [^Page page]
  (println "url:    " (.url page))
  (println "title:  " (.title page))
  (println "h2s:    " (.allTextContents (.locator page "h2")))
  (let [body (.innerHTML (.locator page "body"))]
    (println "body[:600]:")
    (println (subs body 0 (min 600 (count body))))))

;; ---------------------------------------------------------------------------
;; Tiny assertion helpers
;; ---------------------------------------------------------------------------

(defn text
  "Synchronously read `selector`'s textContent on `page`.  Use only
  after a deterministic settle point — see [[wait-text]] for the racy
  cases (anything that follows an SSE morph)."
  [^Page page selector]
  (-> page (.locator selector) .textContent))

(defn wait-text
  "Block until the first match of `selector` contains exactly `expected`
  (or up to ~5s).  Datastar morphs are fast but still asynchronous;
  every post-click read of changed DOM should go through this."
  [^Page page selector expected]
  (-> (com.microsoft.playwright.assertions.PlaywrightAssertions/assertThat
        (.locator page selector))
      (.hasText ^String expected)))

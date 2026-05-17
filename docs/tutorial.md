# Tutorial — a live collaborative todo list

stube is a personal research project, and the fastest way to see what
it's actually exploring is to build something small with it. This
walkthrough builds **standup**, a tiny shared todo board: every
visitor sees the same list, can post items, edit them in place,
delete them with a confirmation, and watch everyone else's edits
appear in real time. No JavaScript you have to write (the Datastar
runtime is loaded from a CDN and does the morphing), no
client/server contract to maintain by hand; at the end of it the
whole thing is a single Clojure file.

If you want to know *why* the framework looks the way it does before
you start typing, read the [rationale](rationale.md) first.

You will meet, in roughly the order you'd reach for them in a real
app:

1. **`defcomponent`** — declaring a UI component as data.
2. **`s/on` / `s/bind`** — wiring DOM events and inputs back to the
   server.
3. **`s/call` / `s/answer`** — Seaside‑style call/answer for
   confirmation dialogs.
4. **`s/call-in-slot`** — edit‑in‑place inside a list row.
5. **`s/subscribe` / `s/publish!`** — pushing live updates to every
   open browser.
6. **`s/defflow`** — sequencing an onboarding wizard as straight‑line
   code.

You can write the whole thing in `src/standup.clj` in any new
deps.edn project that depends on stube. The finished file is about
160 lines.

> **Heads up.** The screenshots and CSS in this tutorial are
> illustrative. The actual app inherits the stock stylesheet at
> `/stube/ui.css`; you can disable it with `(s/start! {:ui-css?
> false})` if you prefer to ship your own.

---

## 0 · Project setup

Create a new project:

```bash
mkdir standup && cd standup
```

`deps.edn`:

```clojure
{:paths ["src"]
 :deps
 {dev.zeko/stube {:mvn/version "0.0.2"}}}
```

Create `src/standup.clj` and require stube:

```clojure
(ns standup
  (:require [dev.zeko.stube.core :as s]
            [clojure.string :as str]))
```

Open a REPL (`clj`, `cider-jack-in`, etc.) and keep it running for
the rest of the tutorial. Every time you re‑evaluate a `defcomponent`
form, the registry picks up the new definition immediately — no
restart, no rebuild.

---

## 1 · The first component

Let's start with the smallest thing that puts a row on a page. A
single hand‑typed item:

```clojure
(s/defcomponent :standup/board
  :init   (constantly {:items []
                       :draft ""})
  :keep   #{:draft}
  :render (fn [self]
            [:section (s/root-attrs self {:class "stube-card"})
             [:h1 "Standup"]
             [:form (s/on self :submit :as :add)
              [:input (merge {:name "draft"
                              :placeholder "What did you do?"
                              :value (:draft self)}
                             (s/bind :draft))]
              [:button {:type "submit"} "Post"]]
             [:ul
              (for [item (:items self)]
                [:li {:key (:id item)} (:text item)])]])
  :handle (fn [self {:keys [event]}]
            (case event
              :add
              (let [t (str/trim (str (:draft self)))]
                (if (str/blank? t)
                  self
                  (-> self
                      (update :items conj {:id (random-uuid)
                                           :text t})
                      (assoc :draft ""))))
              self)))

(s/mount! "/" :standup/board)
(s/start! {:port 8080})
```

Open <http://localhost:8080/> and post a few items. They appear, the
form clears, the page never reloads. Everything you typed in the
input was kept on the server thanks to `:keep #{:draft}` and
`(s/bind :draft)` — that's the entire two‑way binding story.

Some things worth noting:

- **`:init`** returns the initial state of an *instance* of this
  component. Pass in whatever; the example takes no args.
- **`:render`** returns hiccup. The root element gets its instance id
  via `s/root-attrs` — that id is what Datastar morphs against on the
  next patch. Without it, you'd be playing whack‑a‑mole with DOM
  identity.
- **`s/on`** wires a DOM event back to the server. `:submit` is
  the DOM event we listen for; `:as :add` is the logical name your
  `:handle` sees in `:event`.
- **`:handle`** is `(fn [self event] …)`. It can return:
  - a map → the new self, no effects
  - a vector of effects → effects only, self unchanged
  - `[self' effects]` → both
  - `nil` → no change

  We return just the new self in this example.
- **The browser never sees JavaScript.** Datastar reads the data‑*
  attributes the server emitted and turns them into POSTs. Your
  handler returns a new value; the kernel re‑renders the component;
  Datastar morphs the difference into the DOM.

---

## 2 · Adding "delete" with a confirmation

Right now items are forever. Let's add a delete button — and put a
confirmation in front of it so accidental clicks don't nuke
yesterday's standup. This is where stube's flagship primitive
appears.

Update the row in `:render`:

```clojure
[:ul
 (for [item (:items self)]
   [:li {:key (:id item)
         :style "display:flex; gap:0.5rem;"}
    [:span {:style "flex:1;"} (:text item)]
    [:button (s/on self :click :as [:delete (:id item)])
     "✕"]])]
```

Notice `:as [:delete (:id item)]`. That's a **structured event**:
the handler will receive `{:event :delete :payload <id>}`. Anything
that needs to ride along with the event goes in the payload; no
hidden state, no signal hacks.

Then handle it:

```clojure
:handle
(fn [self {:keys [event payload]}]
  (case event
    :add    (let [t (str/trim (str (:draft self)))]
              (if (str/blank? t)
                self
                (-> self
                    (update :items conj {:id (random-uuid) :text t})
                    (assoc :draft ""))))
    :delete [(s/call :ui/confirm {:question "Delete this item?"}
                     :on-confirm-delete)]
    self))

:on-confirm-delete
(fn [self yes?]
  (if yes?
    ;; TODO: which item did we want to delete, again?
    self
    self))
```

You can see this works — clicking ✕ pops up a Yes/No card. But the
TODO is real: by the time the confirmation answers, we've forgotten
*which* item the user wanted to delete. We need to remember it.

The cleanest way is to put the pending id on `self` between the
question and the answer:

```clojure
:delete [(assoc self :pending-delete payload)
         [(s/call :ui/confirm {:question "Delete this item?"}
                  :on-confirm-delete)]]
```

…and read it back in the resume key:

```clojure
:on-confirm-delete
(fn [self yes?]
  (let [id (:pending-delete self)
        self' (dissoc self :pending-delete)]
    (if (and yes? id)
      (update self' :items #(into [] (remove (fn [x] (= (:id x) id))) %))
      self')))
```

What just happened, at the kernel level:

1. The click fired with `:event :delete`, `:payload <id>`.
2. `:handle` returned `[self' [(s/call ...)]]` — *update self,
   then emit one effect: push a child onto the stack.*
3. The kernel instantiated `:ui/confirm` (one of stube's stock
   components), recorded that when it `:answer`s the parent's
   `:on-confirm-delete` should fire, and rendered the new top
   frame over your list.
4. The user clicked Yes / No. `:ui/confirm` emitted
   `[:answer true]` or `[:answer false]`.
5. The kernel popped that frame, looked up `:on-confirm-delete` on
   the parent, and invoked it with the answered value.
6. Your code returned the cleaned‑up self; the kernel re‑rendered
   the parent frame.

This is *the* primitive: a component calls another component, gets
the answer, and continues. No callback hell, no client‑side modal
state, no risk of the user clicking ✕ on a different row in the
meantime — the parent never lost control of the page.

> **Aside.** `s/confirm`, `s/prompt`, `s/choose` and `s/info` are
> stock components shipped with stube. You can build your own — any
> component that emits `[:answer v]` is a valid callable — and we
> will, in §4.

---

## 3 · Edit in place

Click an item to edit it; submit to save; cancel to back out. We
want exactly one row at a time to be in edit mode, and we want
the rest of the page to keep working while it's open.

This is what **`s/call-in-slot`** is for. A normal `s/call` puts the
child *on top of the page*, hiding the parent. `call-in-slot` puts
the child *into a specific slot of the parent's render*, leaving
the rest of the page alone. The child still `:answer`s back to the
parent; the parent decides what answer means.

Define a small editor component:

```clojure
(s/defcomponent :standup/editor
  :init   (fn [{:keys [id text]}]
            {:id id :text text})
  :keep   #{:text}
  :render (fn [self]
            [:form (s/root-attrs self
                     {:style "display:flex; gap:0.5rem;"}
                     (s/on self :submit))
             [:input (merge {:name "text"
                             :value (:text self)
                             :autofocus true}
                            (s/local-bind self :text))]
             [:button {:type "submit"} "Save"]
             [:button (merge {:type "button"}
                             (s/on self :click :as :cancel))
              "Cancel"]])
  :handle (fn [self {:keys [event]}]
            [(s/answer (if (= event :cancel)
                         s/cancel
                         {:id (:id self) :text (:text self)}))]))
```

Two new things:

- **`s/local-bind`** instead of `s/bind`. Datastar signals are
  *page‑global*: if two `<input>`s on the same page both bind
  `:text`, they share a value. That would mean the editor and the
  parent's `:draft` could collide. `local-bind` suffixes the wire
  key with the instance id, so each editor instance gets its own
  signal while you still read `(:text self)` in code.
- **`s/cancel`** is a sentinel value provided by stube. The
  convention: cancellable components `:answer` it when the user
  bails.

Update the row render to switch between display and edit modes:

```clojure
[:ul
 (for [item (:items self)]
   [:li {:key (:id item)
         :style "display:flex; gap:0.5rem;"}
    (if (= (:editing-id self) (:id item))
      ;; This row is being edited: render the editor in its slot.
      [:span {:style "flex:1;"}
       (s/render-slot self :slot/editor)]
      ;; Normal display row: clicking the text opens the editor.
      [:span (merge {:style "flex:1; cursor:text;"}
                    (s/on self :click :as [:edit (:id item)]))
       (:text item)])
    [:button (s/on self :click :as [:delete (:id item)]) "✕"]])]
```

And in `:handle`:

```clojure
:edit
(let [item (some #(when (= (:id %) payload) %) (:items self))]
  (when item
    [(assoc self :editing-id (:id item))
     [(s/call-in-slot :slot/editor
                      :standup/editor {:id (:id item)
                                       :text (:text item)}
                      :on-edit)]]))
```

`call-in-slot` takes a slot key (yours to invent), the embed spec
of the child, and the resume key the parent listens on. When the
editor answers, the parent's `:on-edit` fires:

```clojure
:on-edit
(fn [self answer]
  (let [self' (assoc self :editing-id nil)]
    (if (= answer s/cancel)
      self'
      (let [{:keys [id text]} answer
            t (str/trim (str text))]
        (if (str/blank? t)
          self'
          (update self' :items
                  (fn [xs]
                    (mapv #(if (= (:id %) id)
                             (assoc % :text t) %) xs))))))))
```

Reload, click an item, edit it, Save. Click again, Cancel. Each
edit only re‑renders that one row.

---

## 4 · Live across browsers

Open `/` in two tabs. They don't see each other. Time to fix that.

stube's pub/sub is just two effects: `(s/subscribe topic event)` and
the function `(s/publish! topic msg)`. Subscribers get the published
message delivered as a regular event with `:payload` set to the
msg, so handlers handle network events exactly like clicks.

We'll keep the shared state outside the conversation — every
visitor's conversation starts by reading the current world and then
subscribing for changes.

```clojure
;; Process-global: the standup itself.
(defonce ^:private !world (atom {:items []}))

(def ^:private topic :standup/changed)

(defn- publish-world! []
  (s/publish! topic @!world))

(defn- update-world! [f]
  (let [w (swap! !world f)]
    (s/publish! topic w)))
```

Then wire it into the component:

```clojure
:init   (fn [_] (merge {:draft "" :editing-id nil :pending-delete nil}
                       @!world))
:start  (fn [_self]
          [(s/subscribe topic :world-changed)])
:wakeup (fn [self]
          [(merge self @!world)
           [(s/subscribe topic :world-changed)]])
:stop   (fn [_self]
          [(s/unsubscribe topic)])
```

- **`:start`** runs once when the component is first instantiated:
  here we sign up for the topic.
- **`:wakeup`** runs when a conversation is restored from history
  or persistence: re‑read the world and re‑subscribe.
- **`:stop`** runs when the frame leaves: unsubscribe so we don't
  leak.

Handle the topic delivery the same way you'd handle a click:

```clojure
:world-changed
(fn [self world]
  (merge self world))
```

Replace each local mutation with an `update-world!`:

```clojure
:add
(let [t (str/trim (str (:draft self)))]
  (if (str/blank? t)
    self
    (do (update-world! #(update % :items conj
                                  {:id (random-uuid) :text t}))
        (assoc self :draft ""))))
```

```clojure
:on-confirm-delete
(fn [self yes?]
  (let [id (:pending-delete self)
        self' (dissoc self :pending-delete)]
    (when (and yes? id)
      (update-world!
        #(update % :items
                 (fn [xs] (into [] (remove (fn [x] (= (:id x) id))) xs)))))
    self'))
```

```clojure
:on-edit
(fn [self answer]
  (let [self' (assoc self :editing-id nil)]
    (if (= answer s/cancel)
      self'
      (let [{:keys [id text]} answer
            t (str/trim (str text))]
        (when-not (str/blank? t)
          (update-world!
            #(update % :items
                     (fn [xs] (mapv (fn [x]
                                      (if (= (:id x) id)
                                        (assoc x :text t) x)) xs)))))
        self'))))
```

Open two tabs. Type in one. The other updates instantly. Edit in
one; the other follows. Delete; both vanish. That is the entire
live‑update story: two effects and one resume key.

> **Why publish from inside `:handle` and *also* update `self`?**
> The publish goes out to *every* subscriber, including the current
> conversation — and yours will receive its own `:world-changed`
> event a moment later. Returning the updated `self` immediately
> just means the user posting the message doesn't see a frame of
> latency before their own message appears.

---

## 5 · An onboarding wizard with `defflow`

Right now anyone can post anonymously. Let's gate the page behind a
two‑step intro: ask the user's name, confirm it's right, then drop
them on the board.

You could write this as a parent task component with `:start` and
resume keys, but stube has a sweeter shape for linear flows:

```clojure
(s/defflow :standup/onboard []
  (loop []
    (let [name (s/await (s/prompt "Who's standing up?"))]
      (if (= name s/cancel)
        (recur)                     ; refuse to proceed without a name
        (let [ok? (s/await (s/confirm (str "Welcome, " name "!  Begin?")))]
          (if ok?
            (s/await (s/embed :standup/board {:user name}))
            (recur)))))))

(s/mount! "/" :standup/onboard)
```

That's *literally* what runs. `defflow` compiles the body into a
component whose state is a cloroutine continuation;
`(s/await child)` is the suspend point. Between awaits is ordinary
Clojure — `let`, `if`, `recur`, side effects, you name it.

A few rules of the road:

- `await` cannot appear inside a nested `(fn …)` or a lazy seq.
  `let`, `if`, `cond`, `when`, `loop`/`recur`, `do` are all fine.
- The body's final value becomes the flow's `:answer`. As the
  root flow, that turns into `:end` and closes the SSE stream.
- Persistence: `defflow` continuations are not EDN, so the
  file‑backed store skips them. Use defcomponent task components
  with explicit resume keys when you need EDN persistence.

Take `:user` into the board's `:init` so the user's name flows
through:

```clojure
:init (fn [{:keys [user]}]
        (merge {:user user
                :draft ""
                :editing-id nil
                :pending-delete nil}
               @!world))
```

…and stamp each item with the author:

```clojure
{:id (random-uuid) :text t :by (:user self)}
```

Reload. You'll be greeted by a Datastar‑rendered modal asking your
name, then a confirmation, then the standup. Refresh: the flow
restarts because it's the root flow, but the world (which lives in
`!world`) persists across refreshes.

---

## 6 · Where to go from here

You now have a real Clojure app, server‑rendered, live‑updating,
zero JavaScript, ~160 lines. Real applications stitch the same
primitives together for everything they do.

A few next steps worth exploring:

- **History and Back.** Every dispatch snapshots the previous
  conversation. Add `(s/back-button "Back")` somewhere and watch
  the kernel rewind. See `examples/dev/zeko/stube/examples/wizard.clj`
  for the pattern.
- **`s/after` for timers.** Refresh "X seconds ago" labels by
  emitting `(s/after 30000 :tick)` from `:start`, and re‑emitting
  it on `:tick`.
- **`s/upload-attrs` for zero‑JS file uploads.** See
  `examples/dev/zeko/stube/examples/file_upload.clj`.
- **Custom decorations.** Wrap any component with a site header /
  permission check / breadcrumb with `s/decorate!`.
- **The REPL.** `(s/inspect cid)` shows the live conversation;
  `(s/tree cid)` prints the component tree; `(s/replay :standup/board
  [{:event :add :signals {:draft "test"}}])` walks the same code
  path the browser does, with no server running.

Read the **[API reference](api.md)** for everything in
`dev.zeko.stube.core`, and the **[internals](internals.md)** for
how the kernel makes all of this go.

---

## Appendix — the finished file

The complete `src/standup.clj` is below for copy‑paste convenience.

```clojure
(ns standup
  (:require [dev.zeko.stube.core :as s]
            [clojure.string      :as str]))

(defonce ^:private !world (atom {:items []}))
(def     ^:private topic  :standup/changed)

(defn- update-world! [f]
  (let [w (swap! !world f)]
    (s/publish! topic w)))

(s/defcomponent :standup/editor
  :init   (fn [{:keys [id text]}] {:id id :text text})
  :keep   #{:text}
  :render (fn [self]
            [:form (s/root-attrs self
                     {:style "display:flex; gap:0.5rem;"}
                     (s/on self :submit))
             [:input (merge {:name "text" :value (:text self) :autofocus true}
                            (s/local-bind self :text))]
             [:button {:type "submit"} "Save"]
             [:button (merge {:type "button"} (s/on self :click :as :cancel))
              "Cancel"]])
  :handle (fn [self {:keys [event]}]
            [(s/answer (if (= event :cancel)
                         s/cancel
                         {:id (:id self) :text (:text self)}))]))

(s/defcomponent :standup/board
  :init (fn [{:keys [user]}]
          (merge {:user user
                  :draft ""
                  :editing-id nil
                  :pending-delete nil}
                 @!world))
  :keep #{:draft}
  :start  (fn [_self] [(s/subscribe topic :world-changed)])
  :wakeup (fn [self]  [(merge self @!world)
                       [(s/subscribe topic :world-changed)]])
  :stop   (fn [_self] [(s/unsubscribe topic)])

  :render
  (fn [self]
    [:section (s/root-attrs self {:class "stube-card"})
     [:h1 (str "Standup — " (:user self))]
     [:form (s/on self :submit :as :add)
      [:input (merge {:name "draft" :placeholder "What did you do?"
                      :value (:draft self)}
                     (s/bind :draft))]
      [:button {:type "submit"} "Post"]]
     [:ul
      (for [item (:items self)]
        [:li {:key (:id item) :style "display:flex; gap:0.5rem;"}
         (if (= (:editing-id self) (:id item))
           [:span {:style "flex:1;"} (s/render-slot self :slot/editor)]
           [:span (merge {:style "flex:1; cursor:text;"}
                         (s/on self :click :as [:edit (:id item)]))
            (:text item)
            [:small {:style "color:#888;"} " — " (:by item)]])
         [:button (s/on self :click :as [:delete (:id item)]) "✕"]])]])

  :handle
  (fn [self {:keys [event payload]}]
    (case event
      :add
      (let [t (str/trim (str (:draft self)))]
        (if (str/blank? t)
          self
          (do (update-world!
                #(update % :items conj
                         {:id (random-uuid) :text t :by (:user self)}))
              (assoc self :draft ""))))

      :delete
      [(assoc self :pending-delete payload)
       [(s/call :ui/confirm {:question "Delete this item?"} :on-confirm-delete)]]

      :edit
      (when-let [item (some #(when (= (:id %) payload) %) (:items self))]
        [(assoc self :editing-id (:id item))
         [(s/call-in-slot :slot/editor
                          :standup/editor {:id (:id item) :text (:text item)}
                          :on-edit)]])

      self))

  :on-confirm-delete
  (fn [self yes?]
    (let [id    (:pending-delete self)
          self' (dissoc self :pending-delete)]
      (when (and yes? id)
        (update-world!
          #(update % :items
                   (fn [xs] (into [] (remove (fn [x] (= (:id x) id))) xs)))))
      self'))

  :on-edit
  (fn [self answer]
    (let [self' (assoc self :editing-id nil)]
      (if (= answer s/cancel)
        self'
        (let [{:keys [id text]} answer
              t (str/trim (str text))]
          (when-not (str/blank? t)
            (update-world!
              #(update % :items
                       (fn [xs]
                         (mapv (fn [x] (if (= (:id x) id)
                                         (assoc x :text t) x)) xs)))))
          self'))))

  :world-changed
  (fn [self world]
    (merge self world)))

(s/defflow :standup/onboard []
  (loop []
    (let [name (s/await (s/prompt "Who's standing up?"))]
      (if (= name s/cancel)
        (recur)
        (let [ok? (s/await (s/confirm (str "Welcome, " name "!  Begin?")))]
          (if ok?
            (s/await (s/embed :standup/board {:user name}))
            (recur)))))))

(s/mount! "/" :standup/onboard)

(defn -main [& _]
  (s/start! {:port 8080})
  @(promise))
```

Run it:

```bash
clojure -M -m standup
```

Open <http://localhost:8080/>, and you're done. Open it in a second
tab to watch the live updates fly. ✦

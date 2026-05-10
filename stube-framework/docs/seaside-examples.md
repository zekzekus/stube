# Seaside examples → stube examples

A working list of canonical Seaside apps to port. The point is not to
clone Seaside exactly — it's to drive the framework with concrete user
stories so we can spot:

* gaps in primitives (likely future slices),
* missing DX (helpers / sugar that should ship in `stube.core`),
* design assumptions that don't survive contact with real UI.

Examples already in the tree (slices 1–3):

| file                     | seaside analogue          | what it exercises                         |
|--------------------------|---------------------------|-------------------------------------------|
| `guess.clj`              | `WAConvenienceTest`-style | `defflow`, linear `s/await`, end          |
| `multicounter.clj`       | `WAMultiCounter`          | `:children`, `s/render-slot`, morph-by-id |
| `wizard.clj`             | task with back            | hand-rolled flow, child→parent `::back`   |

---

## Tier 1 — implementable today, expand the surface

These ship in this commit. Each one was picked because it stresses the
**existing** kernel along an axis we hadn't really touched yet, and
because it points at a small DX win or a clearly-scoped follow-up.

### `calc.clj` — Calculator (Seaside book demo)

* **Pattern**: pure state machine, lots of buttons, single component.
* **Why**: stresses the case where one component handles ~20 distinct
  events. A grid of buttons is the densest practical test of `s/on
  self :click :as :foo` ergonomics.
* **DX note**: typing `(s/on self :click :as :digit-7)` ×10 for a
  number pad is repetitive. Worth considering whether a `:click :as
  [:digit n]` form (route a structured event instead of one keyword
  per button) belongs in the helper. *Open question, no commit.*

### `dialogs.clj` — Confirm / Prompt / Choose (`WAYesOrNoDialog`,
`WAInputDialog`, `WAChoiceDialog`)

* **Pattern**: three small reusable modal components, plus a task that
  exercises them.
* **Why**: this is the "dialog-as-call/answer" feature Seaside is most
  famous for; we already have it but no demo until now. Also the
  natural place to introduce **convenience helpers** equivalent to
  Seaside's `confirm:`, `request:` and `chooseFrom:` on `WAComponent`.
* **DX note**: the demo defines local `confirm`/`prompt`/`choose`
  functions that wrap `s/embed`. If they prove useful across examples
  they should graduate into `stube.core`.

### `tabs.clj` — Tabbed Navigation (`WASimpleNavigation`)

* **Pattern**: parent declares N children, header switches which one
  gets rendered, the rest stay alive in `:conv/instances`.
* **Why**: first demo where embedded children outlive the rendered
  view. It checks that morph-by-id only re-renders the active slot
  *and* that the inactive children's state survives a tab away/back
  cycle. Quietly tests `:instance/children` more than multicounter
  does (multicounter renders all three children every time).

### `calendar.clj` — Mini calendar picker (`WAMiniCalendar`)

* **Pattern**: stateful month-nav widget; clicking a day `:answer`s
  back the chosen `LocalDate`.
* **Why**: the most date-arithmetic-heavy thing we'll put on the demo
  page, useful as a real reusable widget. Exercises a single
  component that renders a non-trivial grid and routes per-cell
  clicks with structured payload (which day was clicked).
* **Reveals**: the current `:click :as :keyword` route can't easily
  carry "which cell". The example sidesteps this by using one
  per-day signal name; the cleaner long-term path is to allow `(s/on
  self :click :as [:pick-day day-of-month])` and have the kernel
  route into a multi-arity resume / handler.

### `todo.clj` — Todo list with in-place editor (`WATodo` + `WATodoItem`
+ `WATodoItemEditor`)

* **Pattern**: dynamic list (add / remove / edit / toggle).
* **Why**: this is the canonical Seaside example that demands local
  composition. Seaside does in-place editing via `self call: editor`
  *from inside the child*, which works because `call:` swaps the
  receiver in its parent's render slot.
* **What we ship**: the editor is now a temporary child mounted with
  `[:call-in-slot :slot/editor ... :resume :on-edit]`. The parent uses
  `:editing-id` only to choose which row renders that slot; the edit
  form itself is a real component that answers back.
* **Reveals**: `:call-in-slot` is the small primitive that makes this
  faithful without a client-side island or parent-owned form state.

---

## Tier 2 — implementable with current kernel, deferred

Worth doing once Tier 1 lands and we have feedback on the helpers
surface. None need new primitives.

| seaside class             | demo idea                          | new patterns it would exercise                  |
|---------------------------|------------------------------------|-------------------------------------------------|
| `WABatchedList`           | paginated list of N items          | render callback as init arg; pagination state  |
| `WATableReport`           | sortable column table              | column config maps; click-to-sort header        |
| `WATree` (class browser)  | expand/collapse tree of namespaces | per-node expansion set; recursive render        |
| `WAPath` / `WATrail`      | breadcrumb header decoration       | first real use of `s/decorate` in demos         |
| `WAExampleBrowser`        | menu of all loaded demos           | dynamic component lookup + child swap           |

---

## Tier 3 — needs new framework functionality

These are the demos that *should* drive whichever slice 4+ we pick up
first. They are listed in roughly increasing order of how much they
push the framework.

| seaside class                 | demo            | what's missing in stube                                |
|-------------------------------|-----------------|--------------------------------------------------------|
| `WAFileUploadExample`         | file upload     | a non-SSE multipart route; an `[:upload-received]` event hook |
| `WAClock` / `WATurboCounter`  | live clock      | timer / scheduled-tick effect (`[:after ms event]`)    |
| `CTCounter` / `CTReport`      | shared counter  | broadcast: "patch this conv from outside its handler"  |
| `CTChat`                      | multi-user chat | `(s/publish! topic msg)` + per-conv subscription       |
| `WATodoItemEditor` (faithful) | in-place edit   | `[:call-in-slot]` — embedded call/answer (see todo.clj) |
| `WASessionProtectedCounter`   | session auth    | binding a conversation to an authenticated session     |
| `WANavigationBar` introspect  | reflective nav  | a registry-introspection helper for "list root mounts" |

---

## Out of scope for this catalogue

* The Hotwire Turbo and JQuery demos in the upstream repo — they exist
  to demonstrate non-Seaside transports and would not teach us
  anything about our own.
* The REST examples — orthogonal to the conversation model; better
  served by a thin Reitit guide.

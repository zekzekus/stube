# Seaside examples → stube examples

A working list of canonical Seaside apps to port. The point is not to
clone Seaside exactly — it's to drive the framework with concrete user
stories so we can spot:

* gaps in primitives (likely future slices),
* missing DX (helpers / sugar that should ship in `stube.core`),
* design assumptions that don't survive contact with real UI.

Examples already in the tree:

| file                     | seaside analogue          | what it exercises                         |
|--------------------------|---------------------------|-------------------------------------------|
| `guess.clj`              | `WAConvenienceTest`-style | `defflow`, linear `s/await`, end          |
| `multicounter.clj`       | `WAMultiCounter`          | `:children`, `s/render-slot`, morph-by-id |
| `wizard.clj`             | task with back            | hand-rolled flow, child→parent `::back`   |
| `seaside_todo.clj`       | HPI tutorial ToDo app     | login/register task, filters, Magritte-like descriptions |
| `calc.clj`               | calculator demo           | dense structured click routing            |
| `dialogs.clj`            | `WAYesOrNoDialog` / etc.  | stock call/answer dialog helpers          |
| `tabs.clj`               | `WASimpleNavigation`      | inactive embedded child preservation      |
| `calendar.clj`           | `WAMiniCalendar`          | grid rendering, structured cell payloads  |
| `todo.clj`               | `WATodo`                  | slot-local in-place editing               |
| `paginated_list.clj`     | `WABatchedList`           | pagination state, EDN-safe render callback |
| `table_report.clj`       | `WATableReport`           | EDN column maps, click-to-sort            |
| `tree.clj`               | `WATree`                  | recursive render, expansion set           |
| `breadcrumb.clj`         | `WAPath` / `WATrail`      | `s/decorate` end-to-end                   |
| `example_browser.clj`    | `WAExampleBrowser`        | mount/registry lookup, detail child swap  |

---

## Book app — HPI “An Introduction to Seaside” ToDo

`seaside_todo.clj` ports the tutorial's running application as a
single stube example mounted at `/seaside-todo`.  It intentionally
keeps the Seaside chapter names visible in the code:

* `StUser` / `StTask` → plain EDN maps and pure helpers.
* `StRootTask` → a hand-rolled task component that `:call`s login,
  register, and logged-in screens.
* `StLoginComponent` / `StRegisterComponent` / `StTaskEditor` →
  answering components.
* `StMenuComponent` → a reusable menu component whose entries route
  EDN events to the parent instead of holding Smalltalk callback
  blocks.
* `StImageDatabase` → an EDN `:db` value carried by the root task, so
  the example remains pure and file-store friendly.
* Magritte → small description maps that generate both the task editor
  fields and the report columns.

Findings from the port:

* The tutorial's Ajax chapter maps naturally to stube's default
  Datastar/SSE transport; checkbox toggles and edit/save already patch
  incrementally without a separate script.aculo.us layer.
* A shared application database is still outside the framework surface.
  The example keeps its database in the conversation to avoid side
  effects during kernel dispatch.  A production app would want an
  explicit, synchronous app-store boundary.
* The Atom feed chapter wants a custom XML route (`/atomTasks`), while
  stube currently only mounts component shells plus conversation
  endpoints.  That is the clearest missing primitive if we want to port
  the book literally end to end.

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

## Tier 2 — implemented with the current kernel

These shipped as a full sweep after the Tier 1 helper surface hardened.
No new primitive was needed; each demo is ordinary component state,
structured events, embedding, or decoration.

### `paginated_list.clj` — Batched list (`WABatchedList`)

* **Pattern**: reusable list component with `:items`, `:page-size`, and
  a row renderer supplied at init.
* **Why**: exercises pagination state and Seaside's "render block"
  shape without adding framework callbacks.
* **Finding**: raw functions in instance state would break the
  EDN-clean conversation invariant. The demo passes the row renderer as
  a qualified symbol and resolves it at render time.

### `table_report.clj` — Sortable report (`WATableReport`)

* **Pattern**: EDN column configuration maps drive labels, value lookup,
  formatting, alignment, and sortability.
* **Why**: demonstrates a report-style widget where UI behaviour comes
  from data, not component-specific branches.
* **Finding**: structured event payloads are enough for sortable headers:
  every header sends `[:sort column-id]` to one handler.

### `tree.clj` — Expandable tree (`WATree` / class browser)

* **Pattern**: recursive render over an EDN tree plus an `:expanded`
  set of node ids.
* **Why**: first recursive example in the catalogue; useful for spotting
  lazy-seq/render-slot issues.
* **Finding**: no parent/child primitive is needed when the tree is pure
  data owned by one component.

### `breadcrumb.clj` — Breadcrumb trail (`WAPath` / `WATrail`)

* **Pattern**: base page component owns path state and content; a
  decorated component wraps the base renderer with a breadcrumb header.
* **Why**: first shipped demo that exercises `s/decorate` end to end.
* **Finding**: decoration remains just component-map composition. The
  only care point is DOM ids: the decorated wrapper owns the live
  instance id, so the inner base render has its root id stripped.

### `example_browser.clj` — Example browser (`WAExampleBrowser`)

* **Pattern**: menu of all shipped demos, one detail child per entry,
  selected by slot; each detail panel reads `(s/mounts)` and `(s/help
  flow-id)` at render time.
* **Why**: replaces the static index with an interactive landing page
  and exercises dynamic registry/mount introspection from user code.
* **Finding**: eager detail children are sufficient for the browser. A
  true "instantiate any flow on demand" preview would be a different
  problem because task/defflow components need stack `:call`, not
  structural embedding.

---

## Tier 3 — needs new framework functionality

These are the demos that *should* drive whichever slice 4+ we pick up
first. They are listed in roughly increasing order of how much they
push the framework.

| seaside class                | demo            | what's missing in stube                                |
|------------------------------|-----------------|--------------------------------------------------------|
| `WAFileUploadExample`        | file upload     | a non-SSE multipart route; an `[:upload-received]` event hook |
| `WAClock` / `WATurboCounter` | live clock      | timer / scheduled-tick effect (`[:after ms event]`)    |
| `CTCounter` / `CTReport`     | shared counter  | broadcast: "patch this conv from outside its handler"  |
| `CTChat`                     | multi-user chat | `(s/publish! topic msg)` + per-conv subscription       |
| `WASessionProtectedCounter`  | session auth    | binding a conversation to an authenticated session     |

---

## Out of scope for this catalogue

* The Hotwire Turbo and JQuery demos in the upstream repo — they exist
  to demonstrate non-Seaside transports and would not teach us
  anything about our own.
* The REST examples — orthogonal to the conversation model; better
  served by a thin Reitit guide.

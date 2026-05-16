# Rationale

> **This page is a placeholder.** It sketches the shape of the story
> the maintainer plans to tell about why stube exists; the prose will
> be filled in over time. If you came here looking for a sales pitch,
> the [tutorial](tutorial.md) is the better read for now.

---

## Where stube comes from

stube is a deliberate descendant of two ideas the mainstream web has
mostly forgotten:

1. **Seaside.** Avi Bryant's
   [Seaside](https://en.wikipedia.org/wiki/Seaside_(software)) framework
   for Smalltalk (and later Pharo / Squeak) treated web pages the way
   GUI toolkits treat windows: as objects with their own state that can
   *call* each other and *answer* back. The control flow of a wizard,
   a confirmation dialog or a login modal was written in the same
   straight‑line style as a desktop app — `self call: WAEditor` then
   read the answer. The framework took care of routing the user's
   clicks back to the right continuation. This was wildly different
   from the request/response–per‑URL model that dominated.

2. **The uncommon web.** A loose family of frameworks — Seaside,
   [UnCommon Web](https://common-lisp.net/project/ucw/), Borges,
   continuation‑server, Weblocks — that asked, "what if the server
   remembered who you were and what you were doing, the way a desktop
   app does?" Continuations on the server, components on the page,
   plain values on the wire. The mainstream went the other way:
   stateless servers, fat clients, JSON contracts to maintain by hand.

The uncommon web was right about one thing in particular: **call and
answer is the natural shape of a UI**. A wizard is a sequence. A
confirmation is a function call. An in‑place editor is a value asking
to be edited. Squashing all of that into push‑based events plus a
client‑side state machine is a category error.

stube reaches back to pick that thread up. The wire is different —
[Datastar](https://data-star.dev/) and SSE, not bespoke continuations
— and the language is Clojure, not Smalltalk, so the implementation is
a small effect kernel over plain maps instead of a continuation
heap. But the mental model is the same: **components are values, the
conversation is a value, and call/answer is the primary control flow.**

---

## Why now

A few things have come back around:

- **Server‑driven rendering is in fashion again.** HTMX, Liveview,
  Hotwire, and Datastar have all rediscovered that the browser is a
  fine renderer if you stop pretending it has to be the brain.
- **Datastar in particular** is small enough that a server framework
  can drive it without ceremony. One SSE stream, one DOM morph, no
  client framework. That is exactly the wire shape Seaside always
  wanted — Seaside just didn't have it.
- **Clojure stayed sober** about value semantics while every other
  ecosystem rewrote its rendering layer four times. Building a server
  framework where the *conversation* is a plain map of plain maps
  finally feels obvious instead of weird.

So stube exists to ask: *what would Seaside look like if it were a
Clojure library in 2026, with a real wire protocol underneath it?*

This page will, when it grows up, tell that story properly — the
Smalltalk lineage, the failed attempts to translate continuations to
the JVM, the lessons from Lively/Coast/Liveview/Hyperfiddle, and the
specific things stube tries to do differently. For now, treat this as
a stake in the ground.

---

## See also

- [`docs/v2.md`](v2.md) — the original design document.
- [`docs/v2_1.md`](v2_1.md) — the revised plan that drove the actual
  slices, with §0 covering the five Datastar facts that had to be
  discovered the hard way.
- [`docs/seaside-examples.md`](seaside-examples.md) — Seaside's
  canonical examples translated into stube, with the porting notes.
- [`docs/halos-spike.md`](halos-spike.md) — the dev tool that lets you
  click any component on a live page and jump to its definition,
  shaped after Smalltalk's halos.

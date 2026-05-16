# Rationale

> stube is a personal research project — somewhere I am working out an
> old idea about web applications that has been pulling at me for
> nearly twenty years. This page is the story of how I got here.

---

## ~2005 — first encounter: UCW

The first framework that taught me something genuinely new about web
applications was [UnCommon Web](https://common-lisp.net/project/ucw/),
Marco Baringer's Common Lisp continuation framework. Two ideas stuck:

- **Page boundaries could be made invisible.** You wrote what looked
  like a straight-line program in the language you already used for
  the rest of the system, and the framework arranged for the right
  HTML to be sent and the right callback to fire when the user
  clicked.
- **HTML was data in the host language.** UCW had **YACLML**, a small
  s-expression DSL for building HTML — the same instinct that would
  later become hiccup and the Seaside canvas API.

I remember showing this to colleagues and the reaction being, almost
universally, *"this is an abomination."* Mixing HTML and code broke a
deeply-held habit that templates were templates and code was code.
Anyone who has been on the receiving end of "you're doing templating
in *Lisp?*" knows the look. The model felt right to me anyway.

## Seaside, and Evrim's core-server

[Seaside](https://en.wikipedia.org/wiki/Seaside_(software)) — Avi
Bryant and Julian Fitzell's Smalltalk framework, later maintained by
Lukas Renggli and the Pharo community — took the same ideas further
than UCW had. Components could `call:` other components like methods and
`answer:` results back like return values. A wizard was a *sequence*.
A confirmation was a *function call*. The "canvas" API turned HTML
generation into Smalltalk message sends.

I also spent time around [Evrim
Ulu](https://github.com/evrim)'s
[core-server](https://github.com/evrim/core-server), another Common
Lisp framework in the same lineage. It had its own takes on
continuations and on the host-language-DSL question, and it was a
useful second data point: there was nothing fundamentally weird about
this style; several smart people in different languages had
independently converged on it.

This was, by some distance, the most interesting cluster of web
frameworks I had ever met. None of them ever became mainstream.

## React, JSX, and an old idea becoming respectable

Then React happened. JSX happened. Suddenly writing HTML inside the
host language was the **default** — not an abomination anymore, just
what you did. The thing colleagues had recoiled from in YACLML and the
Seaside canvas was now table stakes in a hiring quiz.

It was a strange kind of vindication. The peripheral idea — HTML as
host-language data — had won. The *central* idea — call/answer,
invisible page boundaries, the server remembering what the user was
doing — got quietly left behind. The web went the other way: thick
clients, JSON contracts, URL-based navigation replaced by client-side
router libraries, the server treated as a dumb data API. Seaside's
center of gravity drifted out of fashion right as its sidekick became
universal.

Seaside itself, to be clear, has not stood still — the Smalltalk
community has kept working on it, including modern work I won't
pretend to be familiar with the details of. But for me the
square-peg problem was specific: I wanted this model **in Clojure**,
on a wire shape I happened to enjoy, with the data shapes Clojure
makes obvious. None of that was Seaside's problem to solve.

## Datastar

The piece I'd been missing in Clojure was a wire I liked. htmx,
Hotwire / Turbo, LiveView, and now [Datastar](https://data-star.dev/)
are all in the same conceptual neighbourhood: server-rendered
fragments over a long-lived channel, the browser as a renderer rather
than a thick client. Any of them would, in principle, support the
call/answer kernel I had in mind.

I happened to land on Datastar. It is small enough that a server
framework can drive it without taking on a JS framework's worth of
complexity; the SSE + morph-by-id protocol clicked with how I wanted
handlers to compose; and the signal model played nicely with how the
conversation already wanted to carry per-input state. It is a
personal preference, not a claim that the others wouldn't work — if
you reach for htmx, Hotwire or LiveView instead, you are in good
company.

## stube

stube is what happened when I sat down with all of these threads in
one hand:

- **Seaside's call / answer / become** semantics, modelled as a tiny
  effect language so they could live inside ordinary Clojure
  functions.
- **The conversation as a plain Clojure map** — values all the way
  down, the way Clojure makes obvious. (Architecturally, the
  conversation here is closer in spirit to a re-frame app-db than to
  a Smalltalk image; that's deliberate.)
- **HTML as hiccup,** the same UCW / Seaside instinct, now
  unremarkable.
- **Datastar over the wire,** doing the morphing.
- **LLM assistants doing a lot of the typing.** Most of the kernel,
  the SDK glue, the multipart upload plumbing, the halos overlay —
  much of that was paired-with-LLM work. The *shape* of the framework
  — what to include and what to keep out, the namespace layout, the
  obsessive readability of the docstrings, the test structure, the
  decision to make effects data and the conversation a value — is
  mine, and reflects how I think about systems. I would not have
  shipped this in a reasonable amount of time without LLM
  assistance; I also would not have shipped *this particular*
  framework with anyone else's hands on the keyboard.

It is not a product. It is the artefact of an idea I have been
carrying for a long time and finally had the tooling to build
faithfully. If it is useful to someone else, that is good news. If it
just exists, and someone picks up these ideas from it the way I
picked them up from UCW and Seaside, that is also fine.

---

## See also

- [`docs/v2.md`](v2.md) — the original design.
- [`docs/v2_1.md`](v2_1.md) — the revised plan, including §0 on the
  five Datastar facts the implementation had to discover the hard
  way.
- [`docs/seaside-examples.md`](seaside-examples.md) — Seaside's
  canonical examples ported to stube.
- [`docs/halos-spike.md`](halos-spike.md) — the dev overlay, shaped
  after Smalltalk's halos.

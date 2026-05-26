# Architecture decisions

Short records of design calls that shaped stube. Each ADR captures one
question, the answer, why we chose it, and what it cost — the same
spirit as Michael Nygard's
[ADR template](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions),
trimmed to one screenful so they actually get written.

Read in order if you're new; the early decisions set the noun set the
later ones inherit.

| # | Decision |
|---|----------|
| [0001](0001-resume-key-naming.md) | Resume keys live on the parent under explicit names, not on the child |
| [0002](0002-edn-clean-conversation-state.md) | Conversation state must round-trip through EDN |
| [0003](0003-embed-vs-call.md) | `:children` materialises eagerly; `:call` pushes a stack frame |
| [0004](0004-app-store-and-principal.md) | `:app` is a per-kernel value; `:principal` is fixed at mint time |
| [0005](0005-answer-error-and-resume.md) | `s/answer-error` is its own effect; `:on-error-<key>` mirrors `:on-<key>` |
| [0006](0006-embed-as-direct-runtime-facade.md) | `dev.zeko.stube.embed` is a thin direct facade over `runtime`; adapters reach into `runtime` themselves |

New ADRs should follow the same shape — Context, Decision,
Consequences. Number sequentially.  Don't rewrite history: when a
decision is reversed, add a new ADR that supersedes the old one and
link both directions; the old file stays as a record of how we used
to think.

# amp-noskills — design document

> An Amp CLI plugin that integrates [@eserstack/noskills](https://github.com/eser/stack/tree/main/pkg/@eserstack/noskills)
> into Amp, promoting Amp from "no support" to noskills' **"Full" enforcement
> tier** — on par with Claude Code, Kiro, Codex, Copilot, OpenCode.

Status: **draft v0.1** — design only, no code yet. Open questions are flagged ⚠.

---

## 1. Motivation

noskills is a state-machine orchestrator for AI coding agents. It enforces a
discovery → refinement → proposal → executing pipeline by mechanically blocking
operations the agent shouldn't perform in the current phase ("hooks block the
operation, not rules"). Its supported-platforms matrix today reads:

| Platform     | Enforcement                                 |
|--------------|---------------------------------------------|
| Claude Code  | Full — hooks block unauthorized actions     |
| Kiro         | Full — steering files + hooks               |
| Codex CLI    | Full — hooks + agents                       |
| Copilot CLI  | Full — hooks + agents                       |
| OpenCode     | Full — plugins + agents                     |
| Cursor       | Behavioural — rules synced, no hooks        |
| Windsurf     | Behavioural — rules synced, no hooks        |
| **Amp**      | **(absent)**                                |

Amp users today can only call `noskills next` as a shell command — no
enforcement. With the new Amp Plugin API (`tool.call` interception,
`agent.start`/`agent.end` injection, `registerCommand`, `registerTool`), the
primitives needed for "Full" enforcement are all present. A small plugin
unlocks the whole noskills value proposition for Amp.

The thesis: **don't reimplement noskills inside Amp; make Amp a faithful
adapter for the existing noskills CLI.** The plugin is glue, not a port.

---

## 2. The user's mental model

Three things change for an Amp user once the plugin is loaded:

1. **Phase awareness.** Every agent turn begins with a fresh `noskills next`
   payload describing the current phase, current task, and constraints. The
   agent doesn't have to remember to ask.
2. **Mechanical guardrails.** During DISCOVERY the agent literally cannot edit
   files. During EXECUTING it cannot run `git commit` (the CLI owns git when
   `autoCommit: true`). Violations are surfaced as `reject-and-continue` tool
   results so the agent learns and adapts within the same turn.
3. **Palette-driven workflow.** `noskills: spec new`, `noskills: approve`,
   `noskills: revisit`, `noskills: status` appear in Amp's command palette.
   Humans drive phase transitions; the agent drives execution.

A worked session (UI mode, palette-first):

```diagram
╭───────────────────────────────────────────────╮
│ user > "build photo upload with validation"   │
│                                               │
│ palette: noskills: spec new                   │
│ ├─ ui.input "spec title?"                     │
│ ├─ ui.input "short description?"              │
│ └─ creates spec, advances to DISCOVERY        │
│                                               │
│ agent turn 1 (auto)                           │
│ ├─ agent.start injects: 6 discovery questions │
│ ├─ agent attempts edit_file → REJECTED        │
│ │   "edits blocked in DISCOVERY phase"        │
│ ├─ agent answers q1..q6 via noskills_answer   │
│ └─ agent.end → continue (more questions left) │
│                                               │
│ palette: noskills: approve  → APPROVED        │
│                                               │
│ agent turn N (executing)                      │
│ ├─ agent.start injects: current task + AC     │
│ ├─ edits allowed; git rejected (CLI owns it)  │
│ ├─ runs tests, reports against AC             │
│ └─ agent.end → continue OR stop on BLOCKED    │
╰───────────────────────────────────────────────╯
```

---

## 3. Architecture

### 3.1 High-level

```diagram
╭──────────────────────────────────────────────────────────────────╮
│                          Amp process                             │
│                                                                  │
│   ╭──────────────────╮     ╭──────────────────────────────╮      │
│   │   Agent loop     │◀───▶│  amp-noskills plugin         │      │
│   │  (turns, tools)  │     │  (this package)              │      │
│   ╰──────────────────╯     │                              │      │
│            │               │  on(agent.start) — inject    │      │
│            │               │  on(tool.call)   — gate      │      │
│            │               │  on(tool.result) — observe   │      │
│            │               │  on(agent.end)   — Ralph     │      │
│            │               │  registerCommand × N         │      │
│            │               │  registerTool   × N          │      │
│            ▼               ╰──────────────┬───────────────╯      │
│   ╭──────────────────╮                    │ amp.$`noskills …`    │
│   │   Tool runtime   │                    ▼                      │
│   ╰──────────────────╯           ╭────────────────────╮          │
│                                  │  noskills CLI      │          │
│                                  │  (subprocess)      │          │
│                                  ╰─────────┬──────────╯          │
╰────────────────────────────────────────────┼─────────────────────╯
                                             ▼
                              ╭───────────────────────────╮
                              │ .eser/.state/progresses/  │
                              │   state.json              │
                              │ .eser/specs/<id>/…        │
                              │ AGENTS.md, .cursorrules…  │
                              ╰───────────────────────────╯
```

### 3.2 The turn lifecycle (sequence)

```diagram
User           Amp agent           Plugin              noskills CLI       Filesystem
 │                │                   │                       │                 │
 │ "do X"         │                   │                       │                 │
 ├───────────────▶│ agent.start ─────▶│ $`noskills next       │                 │
 │                │                   │   -o json`           ─┼────────────────▶│
 │                │                   │ ◀── JSON payload ─────│                 │
 │                │ ◀── inject as     │                       │                 │
 │                │     system msg ───┤                       │                 │
 │                │                   │                       │                 │
 │                │ tool.call(edit) ─▶│ phase==DISCOVERY?     │                 │
 │                │                   │   reject-and-continue │                 │
 │                │ ◀── reject ───────┤                       │                 │
 │                │                   │                       │                 │
 │                │ tool.call(noskills_answer) ──▶│ allow     │                 │
 │                │   shell `noskills next --answer=…`        ─┼───────────────▶│
 │                │ ◀── ok ───────────│                       │                 │
 │                │                   │                       │                 │
 │                │ agent.end ───────▶│ status==BLOCKED?      │                 │
 │                │                   │   stop : continue     │                 │
 │                │ ◀── continue ─────┤                       │                 │
 │                │                   │                       │                 │
```

### 3.3 Mapping noskills needs to Amp Plugin API primitives

| noskills need                                | Amp Plugin API                                                                                      |
|----------------------------------------------|------------------------------------------------------------------------------------------------------|
| Push current-phase instructions every turn   | `amp.on('agent.start', …)` returning `{message:{content, display:false}}`                            |
| Block edits in DISCOVERY/REFINEMENT/PROPOSAL | `amp.on('tool.call', …)` → `{action:'reject-and-continue', message}` for `edit_file`/`create_file`   |
| Block git ops in EXECUTING (CLI owns git)    | same hook, filter `Bash` via `helpers.shellCommandFromToolCall` for `git commit/push/...`            |
| Verification backpressure                    | `agent.end` inspects `helpers.toolCallsInMessages` for test runs, blocks/continues accordingly       |
| Ralph-loop autonomous mode                   | `agent.end` returning `{action:'continue', userMessage:'<noskills next prompt>'}`                    |
| Human controls (approve, revisit, status…)   | `amp.registerCommand(...)` × N, with `ctx.ui.input/select/confirm`                                   |
| Agent-callable noskills ops                  | `amp.registerTool(...)` × N (e.g. `noskills_next`, `noskills_answer`, `noskills_status`)             |
| Run the CLI                                  | `amp.$\`noskills … -o json\``                                                                        |
| Per-thread state isolation                   | Key in-memory caches by `ctx.thread.id`; pass `--spec=<id>` to CLI as needed                         |
| Open dashboard / docs                        | `ctx.system.open('https://noskills.dev/…')`                                                          |

There are no API holes. The plugin is feasible end-to-end on Amp's current
plugin surface.

---

## 4. The plugin (pseudocode)

```ts
import type { PluginAPI } from '@ampcode/plugin'

type Phase =
  | 'IDLE' | 'DISCOVERY' | 'REFINEMENT' | 'PROPOSAL'
  | 'APPROVED' | 'EXECUTING' | 'BLOCKED' | 'COMPLETED'

type NextPayload = {
  phase: Phase
  spec?: { id: string; title: string }
  task?: { id: string; description: string; acceptance: string[] }
  reminders: string[]      // tier-1 concern reminders for this phase
  rules: string[]          // behavioural rules
  prompt: string           // the literal text the agent should "see" this turn
  status: 'awaiting-answer' | 'awaiting-approve' | 'running' | 'blocked' | 'done'
}

const cache = new Map<string, { payload: NextPayload; mtime: number }>()

async function readNext(amp: PluginAPI, threadId: string): Promise<NextPayload> {
  const stat = await amp.$`stat -c %Y .eser/.state/progresses/state.json`
  const mtime = parseInt(stat.stdout.trim(), 10)
  const hit = cache.get(threadId)
  if (hit && hit.mtime === mtime) return hit.payload
  const out = await amp.$`noskills next -o json`
  const payload = JSON.parse(out.stdout) as NextPayload
  cache.set(threadId, { payload, mtime })
  return payload
}

const EDIT_TOOLS = new Set(['edit_file', 'create_file', 'apply_patch'])
const GIT_RX = /^\s*git\s+(commit|push|reset|checkout|merge|rebase)\b/

export default function (amp: PluginAPI) {
  amp.on('agent.start', async (event, ctx) => {
    const next = await readNext(amp, ctx.thread.id)
    if (next.status === 'done') return
    return {
      message: {
        content: `[noskills · ${next.phase}]\n${next.prompt}`,
        display: false,
      },
    }
  })

  amp.on('tool.call', async (event, ctx) => {
    const next = await readNext(amp, ctx.thread.id)
    const phase = next.phase

    // Rule 1: no edits during discovery / refinement / proposal
    if (EDIT_TOOLS.has(event.tool) &&
        (phase === 'DISCOVERY' || phase === 'REFINEMENT' || phase === 'PROPOSAL')) {
      return {
        action: 'reject-and-continue',
        message: `noskills: edits are blocked in ${phase}. Use \`noskills_answer\` to advance.`,
      }
    }

    // Rule 2: no git from agent during EXECUTING (CLI owns git)
    if (event.tool === 'Bash') {
      const sh = amp.helpers.shellCommandFromToolCall(event)
      if (sh && GIT_RX.test(sh.command) && phase === 'EXECUTING') {
        return {
          action: 'reject-and-continue',
          message: `noskills: git is owned by the CLI in EXECUTING. Let the loop commit.`,
        }
      }
    }

    return { action: 'allow' }
  })

  amp.on('agent.end', async (event, ctx) => {
    const next = await readNext(amp, ctx.thread.id)

    // Verification backpressure: if executing and no test run was recorded,
    // ask the agent to run tests before declaring done.
    if (next.phase === 'EXECUTING') {
      const calls = amp.helpers.toolCallsInMessages(event.messages)
      const ranTests = calls.some(c => /\b(test|pytest|jest|go test|cargo test)\b/
        .test(amp.helpers.shellCommandFromToolCall(c.call)?.command ?? ''))
      if (!ranTests) {
        return {
          action: 'continue',
          userMessage: 'noskills: run the test suite before declaring this task done.',
        }
      }
    }

    // Ralph loop: continue iterations unless BLOCKED or COMPLETED.
    if (next.phase !== 'BLOCKED' && next.phase !== 'COMPLETED') {
      const nextNext = await readNext(amp, ctx.thread.id)
      return {
        action: 'continue',
        userMessage: `[noskills · ${nextNext.phase}]\n${nextNext.prompt}`,
      }
    }
  })

  // ── Palette commands ──────────────────────────────────────────────
  amp.registerCommand('spec-new', { title: 'new spec', category: 'noskills' },
    async (ctx) => {
      const title = await ctx.ui.input({ title: 'spec title' })
      if (!title) return
      await amp.$`noskills spec new ${title}`
      await ctx.ui.notify(`spec '${title}' created — DISCOVERY started`)
    })

  amp.registerCommand('approve', { title: 'approve current spec', category: 'noskills' },
    async (ctx) => {
      const ok = await ctx.ui.confirm({ title: 'approve & advance?' })
      if (!ok) return
      await amp.$`noskills approve`
    })

  amp.registerCommand('revisit', { title: 'revisit (back to DISCOVERY)', category: 'noskills' },
    async () => { await amp.$`noskills revisit` })

  amp.registerCommand('status', { title: 'show current status', category: 'noskills' },
    async (ctx) => {
      const out = await amp.$`noskills status -o markdown`
      await ctx.ui.notify(out.stdout)
    })

  amp.registerCommand('init-for-amp', { title: 'init noskills (Amp adapter)', category: 'noskills' },
    async (ctx) => {
      await amp.$`noskills init --platform=amp || noskills init`
      await ctx.ui.notify('noskills initialised. Reload plugins to activate hooks.')
    })

  // ── Agent-callable tools ──────────────────────────────────────────
  amp.registerTool({
    name: 'noskills_answer',
    description: 'Submit an answer to the current noskills phase question(s).',
    inputSchema: { type: 'object', properties: { answer: { type: 'string' } }, required: ['answer'] },
    execute: async ({ answer }) => {
      const out = await amp.$`noskills next --answer=${answer} -o json`
      return out.stdout
    },
  })

  amp.registerTool({
    name: 'noskills_status',
    description: 'Get the current noskills phase, spec, and task as JSON.',
    inputSchema: { type: 'object', properties: {} },
    execute: async () => (await amp.$`noskills status -o json`).stdout,
  })
}
```

That's the plugin in full — call it ~250 LOC once compiled cleanly.

---

## 5. Enforcement matrix (what gets blocked, when)

| phase        | edits | create | bash (general) | bash: git write | bash: tests | noskills_answer |
|--------------|-------|--------|----------------|-----------------|-------------|-----------------|
| IDLE         | ✗     | ✗      | ✓              | ✓               | ✓           | ✗               |
| DISCOVERY    | ✗     | ✗      | ✓              | ✗               | ✗           | ✓               |
| REFINEMENT   | ✗     | ✗      | ✓              | ✗               | ✗           | ✓               |
| PROPOSAL     | ✗     | ✗      | ✓              | ✗               | ✗           | ✓               |
| APPROVED     | ✓     | ✓      | ✓              | ✗               | ✓           | ✓               |
| EXECUTING    | ✓     | ✓      | ✓              | ✗ (CLI owns)    | ✓ (required)| ✓               |
| BLOCKED      | ✗     | ✗      | ✓              | ✗               | ✓           | ✓               |
| COMPLETED    | ✗     | ✗      | ✓              | ✓               | ✓           | (no-op)         |

Read-only tools (`Read`, `Grep`, `glob`) are always allowed. `Bash` defaults to
allowed unless the command matches a denied subcommand pattern derived from the
phase.

⚠ The exact deny patterns and the EXECUTING/test-required rule should be
configurable via `manifest.yml` so projects can tune them per noskills'
existing convention rather than hard-coding here.

---

## 6. Public surface

### 6.1 Palette commands (Amp command palette)

| id            | title                          | description                                |
|---------------|--------------------------------|--------------------------------------------|
| `spec-new`    | new spec                       | Create a spec, enter DISCOVERY              |
| `approve`     | approve current spec           | Advance APPROVED → EXECUTING                |
| `revisit`     | revisit                        | Go back to DISCOVERY preserving progress    |
| `status`      | show current status            | Show markdown status                        |
| `init-for-amp`| init noskills (Amp adapter)    | Scaffold AGENTS.md + plugin install         |
| `cancel`      | cancel current spec            | Phase → COMPLETED(cancelled)                |
| `wontfix`     | wontfix current spec           | Phase → COMPLETED(wontfix)                  |

### 6.2 Agent-callable tools

| name              | purpose                                            |
|-------------------|----------------------------------------------------|
| `noskills_answer` | Submit an answer to the current question(s)        |
| `noskills_status` | Read current phase / spec / task as JSON           |
| `noskills_next`   | Read the next instruction payload as JSON          |

### 6.3 Configuration (read via `amp.configuration`)

| key                                  | default | meaning                                          |
|--------------------------------------|---------|--------------------------------------------------|
| `noskills.binary`                    | `noskills` | Override the CLI invocation                  |
| `noskills.autoContinue`              | `true`  | Enable Ralph-style `agent.end → continue`         |
| `noskills.requireTestsInExecuting`   | `true`  | Force a test run before each task accept          |
| `noskills.injectDisplay`             | `false` | Whether the injected system msg is shown to user  |
| `noskills.gitDenyPatterns`           | (rx)    | Bash patterns rejected while EXECUTING            |

---

## 7. State, caching, and concurrency

- **State of record** stays where noskills puts it: `.eser/.state/progresses/state.json`
  and the `.eser/specs/<id>/` tree. The plugin is stateless except for an
  in-memory cache.
- **Cache invalidation** is by `mtime` on `state.json`. Every plugin call
  `stat`s the file (cheap) and only re-shells `noskills next` on change.
- **Concurrency.** noskills already supports concurrent CLI + agent writers.
  The plugin doesn't add a writer of its own — every mutation is a CLI
  invocation, so file-locking remains noskills' responsibility.
- **Multi-thread.** Cache is keyed by `ctx.thread.id`. Different Amp threads
  may be working different specs; the plugin forwards `--spec=<id>` when the
  thread carries one in its initial message (⚠ binding mechanism is open —
  see §10).

---

## 8. Installation & lifecycle

### 8.1 Layout

```
.amp/plugins/                         ← project-local
└── noskills.ts                        ← this plugin

~/.config/.amp/plugins/                ← user-wide alternative
└── noskills.ts
```

Default to user-wide unless the project already has a `.amp/plugins/`
directory.

### 8.2 First-run

```diagram
1. User installs noskills CLI            (npm i -g jsr:@eserstack/noskills)
2. User drops noskills.ts into plugins   (curl … or `noskills install --amp`)
3. Amp loads plugin on next session       (or palette: plugins: reload)
4. Palette: noskills: init for amp        (creates AGENTS.md, hooks paths)
5. Palette: noskills: spec new            (begin first spec)
```

### 8.3 Upgrade

The plugin is one TypeScript file; replace it and reload via Amp's command
palette (`plugins: reload`). No restart of Amp required.

---

## 9. Comparison

| capability                              | bare Amp + manual `noskills next` | Amp + this plugin |
|-----------------------------------------|-----------------------------------|-------------------|
| noskills tier                           | Behavioural                       | **Full**          |
| Phase instructions injected each turn   | no (agent must remember)          | yes (`agent.start`)|
| File edits blocked in DISCOVERY         | no                                | yes (`tool.call`) |
| Git blocked in EXECUTING                | no                                | yes               |
| Test-run gate before accept             | no                                | yes               |
| Ralph-loop autonomous iteration         | manual `eser noskills run` outside| in-thread via `agent.end → continue` |
| Palette controls for humans             | no                                | yes (`registerCommand`) |
| Agent-callable noskills tools           | only via Bash                     | first-class tools |

---

## 10. Open questions

1. **Spec ↔ thread binding.** When does an Amp thread "own" a noskills spec?
   Options: (a) thread id == spec id mapping kept in plugin state file; (b)
   first user message names the spec; (c) plugin reads
   `.eser/.state/active-spec`. Probably (c) for v0.
2. **`noskills init --platform=amp` upstream.** noskills' init currently
   detects Claude Code, Kiro, Codex, etc. Amp adapter (where to drop the
   plugin file, how to wire AGENTS.md) needs a small upstream PR. Until it
   lands, the plugin's `init-for-amp` palette command can scaffold locally.
3. **Tier classification.** Is "Full" appropriate when tool.call rejection
   only blocks the tool — not arbitrary side-channels (e.g. agent could
   `Bash` `node -e "fs.writeFileSync(…)"` to bypass `edit_file` checks)?
   Either widen rejection to suspicious bash, or accept that "Full" is
   "as full as plugin hooks allow." Probably the latter, documented honestly.
4. **Display vs hidden injection.** `display: false` keeps the injected
   noskills payload out of the user's view. Some users will want to see what
   the agent is being told each turn. Make it configurable.
5. **Subagents.** Amp can spawn subagents. Should subagent threads also see
   the noskills payload? Default: yes — `agent.start` fires for them too.
   Risk: token bloat. Open until tested.
6. **Oracle calls.** The Oracle is not a normal turn. Probably leave Oracle
   alone (no injection, no rejection); confirm against API.
7. **Conflict with Amp `AGENTS.md`.** noskills generates instruction files
   per platform; Amp uses `AGENTS.md`. Coordinate so noskills writes
   `AGENTS.md` rather than something Amp ignores.
8. **`agent.end` continue + user input.** If the user types a new message
   during a planned auto-continue, whose message wins? Need to verify Amp's
   semantics — probably user input cancels the queued continuation. If not,
   guard with a "pending continuation" flag.

---

## 11. Implementation plan

### Slice 0 — read-only adapter (½ day)

- Plugin scaffold (`.amp/plugins/noskills.ts`).
- `agent.start` injection of `noskills next` payload (no enforcement yet).
- `noskills: status` palette command.
- Verify on a hand-driven spec.

### Slice 1 — enforcement (1 day)

- `tool.call` rejection for edits during non-edit phases.
- Bash + git deny rules in EXECUTING.
- Configuration plumbing (`amp.configuration`).

### Slice 2 — agent tools + Ralph loop (1 day)

- `registerTool` for `noskills_answer`, `noskills_status`, `noskills_next`.
- `agent.end → continue` autopilot, with `noskills.autoContinue` toggle.
- Verification backpressure (test-run gate).

### Slice 3 — palette UX (½ day)

- `spec-new`, `approve`, `revisit`, `cancel`, `wontfix`, `init-for-amp`.
- `ctx.ui.input/select/confirm` flows for each.

### Slice 4 — upstream (open-ended)

- PR `noskills init --platform=amp` to eser/stack.
- Add Amp to noskills' supported-platforms README table at "Full" tier.
- Publish plugin via the noskills install path.

After slices 0–2 the plugin is publishable; 3–4 are polish + ecosystem.

---

## 12. Things deliberately out of scope

- **Replacing Amp's own skills system.** Amp's skills coexist with noskills;
  this plugin doesn't try to migrate them.
- **Replacing the oracle/subagent primitives.** They overlap with noskills'
  sub-agent pipeline conceptually but are unrelated mechanically.
- **A web dashboard.** noskills already has one; the plugin can `system.open`
  to it but doesn't reimplement.
- **Persisting plugin state across Amp restarts.** All durable state lives in
  `.eser/.state/`; the plugin's only memory is an mtime cache.
- **CPS-style continuations of agent turns.** The Ralph loop is the
  continuation; that's enough.

---

## 13. Risks

| risk                                                       | mitigation                                                               |
|------------------------------------------------------------|--------------------------------------------------------------------------|
| Bash bypass of edit blocking (e.g. `node -e "fs.write…"`)  | Widen deny patterns; accept that hooks are as strong as Amp allows       |
| Latency from per-turn `noskills next` shell-out             | mtime-keyed cache; CLI is fast (<50ms typical)                            |
| Two Amp threads racing on the same spec                    | Already noskills' problem — file locking on `state.json`                  |
| User confused by silent rejections                          | Make rejection messages explicit ("blocked because phase=DISCOVERY")     |
| `agent.end → continue` runaway loop                        | `noskills run --max-iterations` parity via plugin config; user can cancel|
| noskills upstream changes JSON shape                       | Validate payload, log + degrade gracefully if fields missing             |
| Duplicate plugins also listening on `tool.call`            | Document the "any plugin can veto" semantic; encourage one orchestrator   |

---

## 14. Glossary

- **noskills** — eserstack's state-machine orchestrator for AI agents.
- **Phase** — one of IDLE/DISCOVERY/REFINEMENT/PROPOSAL/APPROVED/EXECUTING/
  BLOCKED/COMPLETED.
- **Concern** — a project-level lens (e.g. "open-source", "compliance") that
  injects extra rules per phase.
- **Spec** — a unit of work with discovery answers, tasks, acceptance criteria.
- **Ralph loop** — fresh-context iteration pattern (Geoff Huntley); here
  realised via Amp's `agent.end → continue` rather than spawning processes.
- **Full / Behavioural** — noskills' two enforcement tiers.
- **Hook** — a noskills callback that mechanically blocks an operation; in
  Amp, implemented via `tool.call → reject-and-continue`.

---

*End of design doc.*

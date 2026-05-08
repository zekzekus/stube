# AGENTS.md

Project-wide preferences and conventions for agents working in this repository.

## Version Control

- **Use Jujutsu (`jj`) as the VCS**, not raw `git`. The repo may have a colocated `.git/` directory, but all VCS operations should go through `jj`.
- Common commands:
  - `jj st` to inspect working copy state
  - `jj diff` to view changes
  - `jj log` for history
  - `jj new` to start a new change, `jj describe -m "..."` to set a message
  - `jj squash` / `jj split` for restructuring changes
- Do **not** run `git commit`, `git push`, `git rebase`, `git reset`, etc. unless I explicitly ask.
- Never run destructive `jj` operations (`jj abandon`, `jj op restore`, `jj undo` past my changes, etc.) without confirmation.

## Reproducible Environment

- **A `flake.nix` is required.** All toolchains, language runtimes, formatters, linters, LSPs, and CLI dependencies the project needs must be declared in the flake's `devShells.default`.
- Prefer `nixpkgs` inputs pinned via `flake.lock`. Add other inputs (e.g. `rust-overlay`, `flake-parts`, language-specific overlays) only when they provide a clear benefit.
- When adding a new tool/library to the project, also add it to the flake so `nix develop` provides a complete environment.
- Don't assume tools are globally installed on my system — verify they come from the dev shell.
- If the flake changes, mention that I should re-enter `nix develop` (or that `direnv reload` is needed if `.envrc` uses `use flake`).

## Shell

- **I use Nushell.** Adapt all suggested/run commands accordingly:
  - No `&&` / `||` / `;` chaining the POSIX way — use separate calls, or Nushell's `;` / `if` / `try` constructs.
  - No `export FOO=bar` — use `$env.FOO = "bar"` or `with-env`.
  - No `$(...)` or backticks — use `(command)` for sub-expressions.
  - Pipelines pass structured data; use `| get`, `| where`, `| select`, `| from json`, `| to json`, etc., instead of `awk`/`sed`/`cut` when convenient.
  - Globs: prefer `ls **/*.rs` style or `glob` builtin; remember Nushell expands them differently than bash.
  - Redirection: `out>`, `err>`, `out+err>` instead of `>`, `2>`, `&>`.
  - `for` / `each` syntax differs from bash — use Nushell's form.
- When running commands via the Bash tool, plain POSIX `sh`/`bash` is fine (the tool uses bash). But any commands you tell **me** to run must be Nushell-compatible.
- If a tool only ships bash completions/hooks, note it and suggest the Nushell equivalent or a wrapper.

## General Working Style

- Keep changes small and focused; don't refactor adjacent code unprompted.
- Prefer editing existing files over creating new ones.
- Verify changes by actually running the relevant command (build, test, lint) from inside the dev shell.
- If something can't be verified, say so explicitly rather than implying success.
- Ask before taking destructive or hard-to-reverse actions.

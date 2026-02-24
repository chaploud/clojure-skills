# clojure-skills

An [Agent Skill](https://agentskills.io) for Clojure development. Works with Claude Code, Codex CLI, Gemini CLI, and other compatible agents.

Provides parenthesis repair, nREPL evaluation, and clojure-lsp integration for `.clj`, `.cljs`, `.cljc`, `.cljd`, `.edn`, `.bb`, `.lpy` files.

## Features

- **Parenthesis Repair** — Auto-fix unbalanced delimiters via parinferish + cljfmt (Claude Code: automatic via hooks, others: `clj-paren-repair` command)
- **REPL Evaluation** — `clj-nrepl-eval` with persistent sessions, timeout handling, and auto-discovery of nREPL servers
- **Code Navigation** — Diagnostics, references, definition, hover via clojure-lsp bridge (per-project, auto-started)
- **Multi-Project** — Detects project root from file path; works across multiple projects
- **All Variants** — `.clj`, `.cljs`, `.cljc`, `.cljd` (ClojureDart), `.edn`, `.bb`, `.lpy`

## Prerequisites

| Tool | Required | Install |
|------|----------|---------|
| [Babashka](https://github.com/babashka/babashka#installation) | Yes | `brew install borkdude/brew/babashka` |
| [bbin](https://github.com/babashka/bbin) | Yes | `bb install io.github.babashka/bbin` |
| [clojure-lsp](https://clojure-lsp.io/installation/) | Optional | `brew install clojure-lsp/brew/clojure-lsp-native` |

## Install

```bash
git clone https://github.com/chaploud/clojure-skills.git ~/clojure-skills
cd ~/clojure-skills

# Install CLI tools + register Claude Code hooks
bb install
```

`bb install` does two things:
1. Installs 5 CLI tools via `bbin install`
2. Registers Claude Code hooks (`PreToolUse`, `PostToolUse`, `Stop`) in `~/.claude/settings.json`

Running `bb install` again is safe (idempotent). Hook registration is Claude Code specific; Codex/Gemini users only need step 1 (the tools).

Then register the skill for whichever agents you use:

```bash
# Claude Code
mkdir -p ~/.claude/skills
ln -s ~/clojure-skills ~/.claude/skills/clojure-skills

# Codex CLI
mkdir -p ~/.agents/skills
ln -s ~/clojure-skills ~/.agents/skills/clojure-skills

# Gemini CLI
mkdir -p ~/.gemini/skills
ln -s ~/clojure-skills ~/.gemini/skills/clojure-skills
```

## Usage

### Parenthesis Repair

On Claude Code, hooks fire automatically on every Write/Edit. No action needed.

On other agents, the SKILL.md instructs the agent to run `clj-paren-repair <file>` when it encounters delimiter errors.

### REPL Evaluation

```
"Connect to the REPL and evaluate (+ 1 2 3)"
"Test this function in the REPL"
```

The agent will run `clj-nrepl-eval --discover-ports` to find servers, then evaluate code via `clj-nrepl-eval -p <port>`.

### Code Navigation (requires clojure-lsp)

```
"Find all references to this function"
"Show diagnostics for this file"
```

The agent will use `clj-lsp-client` for diagnostics, references, definition, and hover.

## Uninstall

```bash
# Remove CLI tools + hooks
bb uninstall

# Remove skill symlinks (whichever you created)
rm ~/.claude/skills/clojure-skills
rm ~/.agents/skills/clojure-skills
rm ~/.gemini/skills/clojure-skills
```

## Attribution

Derived from [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) by Bruce Hauman (EPL-2.0).

## License

[Eclipse Public License 2.0](./LICENSE)

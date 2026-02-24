# clojure-claude-skill

A comprehensive Clojure development skill for [Claude Code](https://docs.anthropic.com/en/docs/claude-code).

Provides automatic parenthesis repair, nREPL evaluation, and clojure-lsp integration — all activated automatically when working with Clojure files.

## Features

- **Parenthesis Repair** — Automatically detects and fixes unbalanced delimiters via hooks on every Write/Edit, then formats with cljfmt
- **REPL Evaluation** — Evaluate Clojure code via nREPL with persistent sessions, timeout handling, and auto-discovery of running servers
- **Code Navigation** — Diagnostics, find references, go-to-definition, and hover via clojure-lsp bridge
- **All Clojure Variants** — `.clj`, `.cljs`, `.cljc`, `.cljd`, `.edn`, `.bb`, `.lpy`
- **ClojureDart Support** — Reader conditionals with `:cljd` feature flag

## Quick Start

### Prerequisites

| Tool | Required | Install |
|------|----------|---------|
| [Babashka](https://github.com/babashka/babashka#installation) | Yes | `brew install borkdude/brew/babashka` |
| [bbin](https://github.com/babashka/bbin) | Yes | `bb install io.github.babashka/bbin` |
| [clojure-lsp](https://clojure-lsp.io/installation/) | Optional | `brew install clojure-lsp/brew/clojure-lsp-native` |

### Install

```bash
git clone https://github.com/your-user/clojure-claude-skill.git ~/Documents/MyProducts/clojure-claude-skill

cd ~/Documents/MyProducts/clojure-claude-skill

# Install CLI tools via bbin
bbin install .

# Create skill symlink
mkdir -p ~/.claude/skills
ln -s ~/Documents/MyProducts/clojure-claude-skill ~/.claude/skills/clojure
```

### Verify

```bash
which clj-paren-repair-claude-hook  # Should show bbin path
which clj-nrepl-eval
which clj-paren-repair
which clj-lsp-bridge
which clj-lsp-client
```

## Usage

Once installed, the skill activates automatically when Claude Code opens files matching `**/*.clj`, `**/*.cljs`, `**/*.cljc`, `**/*.cljd`, `**/*.edn`, or `**/*.bb`.

See [SKILL.md](./SKILL.md) for full usage instructions available to Claude.

## Global Hooks (Alternative)

If you prefer to configure hooks globally instead of via the skill, copy the hooks from `references/hooks-example.json` into your `~/.claude/settings.json` or project `.claude/settings.local.json`.

## Attribution

This project is a derivative work of [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) by Bruce Hauman, licensed under the Eclipse Public License 2.0.

## License

Eclipse Public License 2.0 — see [LICENSE](./LICENSE).

# clojure-claude-skill

A [Claude Code](https://docs.anthropic.com/en/docs/claude-code) skill for Clojure development.

Automatically activates when working with Clojure files (`.clj`, `.cljs`, `.cljc`, `.cljd`, `.edn`, `.bb`). Provides parenthesis repair hooks, nREPL evaluation, and clojure-lsp integration.

## Features

- **Parenthesis Repair** — Hooks on every Write/Edit automatically detect and fix unbalanced delimiters via parinfer, then format with cljfmt
- **REPL Evaluation** — `clj-nrepl-eval` with persistent sessions, timeout handling, and auto-discovery of running nREPL servers
- **Code Navigation** — Diagnostics, find references, go-to-definition, and hover via clojure-lsp bridge (per-project, auto-started)
- **Multi-Project** — Works across projects added via `/add-dir`; detects project root from file path and resolves symlinks
- **All Variants** — `.clj`, `.cljs`, `.cljc`, `.cljd` (ClojureDart), `.edn`, `.bb`, `.lpy`

## Prerequisites

| Tool | Required | Install |
|------|----------|---------|
| [Babashka](https://github.com/babashka/babashka#installation) | Yes | `brew install borkdude/brew/babashka` |
| [bbin](https://github.com/babashka/bbin) | Yes | `bb install io.github.babashka/bbin` |
| [clojure-lsp](https://clojure-lsp.io/installation/) | Optional | `brew install clojure-lsp/brew/clojure-lsp-native` |

## Install

Choose where to clone (the path will be embedded in the tools):

```bash
# Clone
git clone https://github.com/chaploud/clojure-claude-skill.git
cd clojure-claude-skill

# Install all 5 CLI tools
bb install

# Register as Claude Code skill
mkdir -p ~/.claude/skills
ln -s "$(pwd)" ~/.claude/skills/clojure
```

Verify:

```bash
which clj-paren-repair-claude-hook  # → ~/.local/bin/clj-paren-repair-claude-hook
which clj-nrepl-eval
which clj-paren-repair
which clj-lsp-bridge
which clj-lsp-client
```

> **Note:** `bb install` runs `bbin install` for each tool individually.
> `bbin install .` alone only installs the first tool — always use `bb install`.

## How It Works

### Automatic (via hooks)

Once installed, parenthesis repair runs automatically on every Write/Edit of Clojure files. No manual steps needed.

### REPL Evaluation

```bash
# Discover nREPL servers in current directory
clj-nrepl-eval --discover-ports

# Evaluate code (always use heredoc to avoid zsh escaping issues)
clj-nrepl-eval -p <port> <<'EOF'
(require '[my.namespace :as ns] :reload)
(ns/my-function 42)
EOF
```

### Code Navigation (requires clojure-lsp)

```bash
clj-lsp-client diagnostics --file src/my/ns.clj
clj-lsp-client references --file src/my/ns.clj --line 10 --col 5
clj-lsp-client definition --file src/my/ns.clj --line 10 --col 5
clj-lsp-client hover --file src/my/ns.clj --line 10 --col 5
```

The bridge auto-starts per project on first query and stops on session end.

## Uninstall

```bash
# Remove CLI tools
for tool in clj-paren-repair-claude-hook clj-nrepl-eval clj-paren-repair clj-lsp-bridge clj-lsp-client; do
  bbin uninstall $tool
done

# Remove skill
rm ~/.claude/skills/clojure
```

## Global Hooks (Alternative)

If you prefer hooks without the skill system, copy from [`references/hooks-example.json`](./references/hooks-example.json) into `~/.claude/settings.json` or project `.claude/settings.local.json`.

## Attribution

Derived from [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) by Bruce Hauman (EPL-2.0).

## License

[Eclipse Public License 2.0](./LICENSE)

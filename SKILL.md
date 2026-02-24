---
name: clojure
description: >-
  Clojure/ClojureScript/ClojureDart development skill. Activates when
  working with .clj, .cljs, .cljc, .cljd, .edn, .bb files. Provides
  parenthesis repair hooks, REPL evaluation via nREPL, and code
  navigation (diagnostics, references, definition) via clojure-lsp.
globs:
  - "**/*.clj"
  - "**/*.cljs"
  - "**/*.cljc"
  - "**/*.cljd"
  - "**/*.edn"
  - "**/*.bb"
user-invocable: false
hooks:
  PreToolUse:
    - matcher: "Write|Edit"
      hooks:
        - type: command
          command: "clj-paren-repair-claude-hook --cljfmt"
  PostToolUse:
    - matcher: "Edit|Write"
      hooks:
        - type: command
          command: "clj-paren-repair-claude-hook --cljfmt"
  SessionEnd:
    - hooks:
        - type: command
          command: "clj-paren-repair-claude-hook"
        - type: command
          command: "clj-lsp-client stop 2>/dev/null; true"
---

# Clojure Development Skill

## Dependency Check

!`which bb 2>/dev/null && echo "✓ bb" || echo "✗ bb MISSING"`
!`which bbin 2>/dev/null && echo "✓ bbin" || echo "✗ bbin MISSING"`
!`which clj-paren-repair-claude-hook 2>/dev/null && echo "✓ clj-tools" || echo "✗ clj-tools MISSING"`
!`which clojure-lsp 2>/dev/null && echo "✓ clojure-lsp" || echo "✗ clojure-lsp MISSING (optional: needed for diagnostics/references/definition)"`

If any required dependency shows ✗ MISSING, **STOP** and show the user how to install:

- **bb** (Babashka): https://github.com/babashka/babashka#installation
- **bbin**: `bb install io.github.babashka/bbin` or https://github.com/babashka/bbin
- **clj-tools**: `cd <clojure-claude-skill-dir> && bbin install .`
- **clojure-lsp** (optional): https://clojure-lsp.io/installation/

## Parenthesis Repair

Parenthesis repair runs **automatically** via hooks on every Write/Edit of Clojure files.
It detects and fixes unbalanced delimiters using parinfer, then formats with cljfmt.

### Manual repair

```bash
clj-paren-repair <files...>
clj-paren-repair path/to/file1.clj path/to/file2.clj
```

**IMPORTANT:** Do NOT try to manually repair parenthesis/bracket/brace errors yourself.
If you encounter unbalanced delimiters, run `clj-paren-repair` on the file.
If the tool doesn't fix it, report to the user that they need to fix the delimiter error manually.

## REPL Evaluation

The command `clj-nrepl-eval` evaluates Clojure code via nREPL.

### Discover nREPL servers

```bash
clj-nrepl-eval --discover-ports
```

This scans `.nrepl-port` file and running JVM/Babashka processes to find nREPL servers.

### Evaluate code

```bash
clj-nrepl-eval -p <port> "<clojure-code>"
```

With timeout (milliseconds):

```bash
clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"
```

With heredoc for multi-line code:

```bash
clj-nrepl-eval -p <port> <<'EOF'
(require '[my.namespace :as ns] :reload)
(ns/my-function 42)
EOF
```

### Session persistence

The REPL session persists between evaluations — namespaces and state are maintained.
Always use `:reload` when requiring namespaces to pick up code changes:

```clojure
(require '[my.namespace :as ns] :reload)
```

### Other commands

```bash
clj-nrepl-eval --connected-ports    # List active connections
clj-nrepl-eval -p PORT --reset-session  # Reset persistent session
```

## Code Navigation (clojure-lsp)

If `clojure-lsp` is installed, use `clj-lsp-client` for code intelligence.
The bridge auto-starts when you first query.

### Diagnostics

```bash
clj-lsp-client diagnostics                        # All files
clj-lsp-client diagnostics --file src/my/ns.clj   # Single file
```

### Find references

```bash
clj-lsp-client references --file src/my/ns.clj --line 10 --col 5
```

### Go to definition

```bash
clj-lsp-client definition --file src/my/ns.clj --line 10 --col 5
```

### Hover information

```bash
clj-lsp-client hover --file src/my/ns.clj --line 10 --col 5
```

### Bridge management

```bash
clj-lsp-client start    # Start bridge (idempotent)
clj-lsp-client stop     # Stop bridge
clj-lsp-client status   # Check bridge status
```

The bridge is automatically stopped on SessionEnd via hooks.

## Important Notes

- **All Clojure variants supported**: .clj, .cljs, .cljc, .cljd, .edn, .bb, .lpy
- **ClojureDart (.cljd)**: Reader conditionals with `:cljd` feature are fully supported
- **Parenthesis repair is automatic**: Hooks fire on every Write/Edit — you don't need to run it manually unless troubleshooting
- **REPL state persists**: Each host:port has its own persistent session. Use `--reset-session` to start fresh
- **LSP bridge auto-starts**: First `clj-lsp-client` query starts the bridge automatically; it stops on session end
- **Line numbers**: `clj-lsp-client` uses 1-based line numbers and 0-based column numbers (matching Emacs conventions)

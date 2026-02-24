---
name: clojure-skills
description: >-
  Clojure/ClojureScript/ClojureDart development skill. Activates when
  working with .clj, .cljs, .cljc, .cljd, .edn, .bb files. Provides
  parenthesis repair hooks, REPL evaluation via nREPL, and code
  navigation (diagnostics, references, definition) via clojure-lsp.
compatibility: >-
  Hooks (auto paren repair) require Claude Code. REPL evaluation and
  code navigation work with any agent that has shell access.
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
  Stop:
    - hooks:
        - type: command
          command: "clj-paren-repair-claude-hook"
        - type: command
          command: "clj-lsp-client stop 2>/dev/null; true"
---

# Clojure Development Skill

## Dependency Check

!`which bb 2>/dev/null && echo "✓ bb" || echo "✗ bb MISSING — install: brew install borkdude/brew/babashka (https://github.com/babashka/babashka#installation)"`
!`which bbin 2>/dev/null && echo "✓ bbin" || echo "✗ bbin MISSING — install: bb install io.github.babashka/bbin (https://github.com/babashka/bbin)"`
!`which clj-paren-repair-claude-hook 2>/dev/null && echo "✓ clj-tools" || echo "✗ clj-tools MISSING — install: cd ~/.claude/skills/clojure-skills && bb install"`
!`which clojure-lsp 2>/dev/null && echo "✓ clojure-lsp" || echo "✗ clojure-lsp MISSING (optional) — install: brew install clojure-lsp/brew/clojure-lsp-native (https://clojure-lsp.io/installation/)"`

If any **required** dependency (bb, bbin, clj-tools) shows ✗ MISSING:
1. **STOP** immediately — do not attempt Clojure file edits without the hooks working
2. Show the user the exact install command from the line above
3. After install, the hooks will activate automatically on next Write/Edit

clojure-lsp is optional — without it, diagnostics/references/definition are unavailable but paren repair and REPL evaluation still work.

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

**IMPORTANT: ALWAYS use heredoc syntax** to avoid zsh `!` escaping issues.
Do NOT use `clj-nrepl-eval -p PORT "code"` style — it will break on strings containing `!`.

```bash
clj-nrepl-eval -p <port> <<'EOF'
(+ 1 2 3)
EOF
```

With timeout (milliseconds):

```bash
clj-nrepl-eval -p <port> --timeout 5000 <<'EOF'
(Thread/sleep 10000)
EOF
```

Multi-line code:

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
The bridge auto-starts per project when you first query with `--file`.

Project root is **auto-detected** from the `--file` path by walking up to find
`deps.edn`, `project.clj`, `bb.edn`, or `shadow-cljs.edn`. This works across
multiple projects added via `/add-dir`.

### Diagnostics

```bash
clj-lsp-client diagnostics                        # All files (current project)
clj-lsp-client diagnostics --file src/my/ns.clj   # Single file (auto-detects project)
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
clj-lsp-client start                             # Start bridge for CWD project
clj-lsp-client start --project-root /path/to/project  # Start for specific project
clj-lsp-client stop                              # Stop bridge
clj-lsp-client status                            # Check bridge status
```

The bridge is automatically stopped on Stop via hooks.

## Important Notes

- **All Clojure variants supported**: .clj, .cljs, .cljc, .cljd, .edn, .bb, .lpy
- **ClojureDart (.cljd)**: Reader conditionals with `:cljd` feature are fully supported
- **Parenthesis repair is automatic**: Hooks fire on every Write/Edit — you don't need to run it manually unless troubleshooting
- **REPL state persists**: Each host:port has its own persistent session. Use `--reset-session` to start fresh
- **LSP bridge is per-project**: Each project gets its own bridge instance, auto-detected from file paths
- **Line numbers**: `clj-lsp-client` uses 1-based line numbers and 0-based column numbers (matching Emacs conventions)
- **Always use heredoc** for `clj-nrepl-eval`: `<<'EOF' ... EOF` — never use quoted argument style

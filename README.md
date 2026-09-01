# clojure-skills

An [Agent Skill](https://agentskills.io) for Clojure development. Works with
Claude Code, Codex CLI, Gemini CLI, and other agents that can run shell commands.

Gives a coding agent Clojure-aware tools in place of the line-oriented ones it
would otherwise reach for: structural search and editing instead of `sed`, a
file outline instead of reading two thousand lines, failing assertions instead
of a test log, and the three project frames of a stacktrace instead of sixty.

Covers `.clj`, `.cljs`, `.cljc`, `.cljd`, `.edn`, `.bb` and `.lpy`.

## What you get

Everything is one binary, `clj-skill`. Each group states what it needs, and
degrades to a named alternative when that is missing.

| Group | Needs | Commands |
|---|---|---|
| Structure | babashka only | `outline` `find` `replace` `repair` |
| REPL | a running nREPL | `repl ports` `repl eval` `repl reset` |
| Runtime | cider-nrepl middleware | `cider info/refs/deps/test/retest/stacktrace/inspect/apropos/ns-vars` |
| Static | clojure-lsp | `lsp diagnostics/references/definition/hover` |

`clj-skill doctor` reports which of those work on the machine it runs on.

Structural commands go through [rewrite-clj](https://github.com/clj-commons/rewrite-clj),
which round-trips source byte for byte, so an edit cannot disturb the comments,
blank lines or indentation around it.

## Prerequisites

| Tool | Required | Install |
|---|---|---|
| [Babashka](https://github.com/babashka/babashka#installation) | yes | `brew install borkdude/brew/babashka` |
| [bbin](https://github.com/babashka/bbin) | yes | `bb install io.github.babashka/bbin` |
| [ripgrep](https://github.com/BurntSushi/ripgrep) | no | `brew install ripgrep` — keeps `find` fast on large trees |
| [clojure-lsp](https://clojure-lsp.io/installation/) | no | `brew install clojure-lsp/brew/clojure-lsp-native` — enables the `lsp` group |
| [cider-nrepl](https://github.com/clojure-emacs/cider-nrepl) | no | a REPL dependency, see below — enables the `cider` group |

## Install

```bash
git clone https://github.com/chaploud/clojure-skills.git ~/clojure-skills
cd ~/clojure-skills
bb install
```

`bb install` installs the `clj-skill` binary with bbin and then runs
`clj-skill doctor`, which tells you what is missing and how to get it. Running
it again is safe.

Then register the skill with whichever agents you use:

```bash
mkdir -p ~/.claude/skills && ln -s ~/clojure-skills ~/.claude/skills/clojure-skills   # Claude Code
mkdir -p ~/.agents/skills && ln -s ~/clojure-skills ~/.agents/skills/clojure-skills   # Codex CLI
mkdir -p ~/.gemini/skills && ln -s ~/clojure-skills ~/.gemini/skills/clojure-skills   # Gemini CLI
```

### Optional: automatic paren repair (Claude Code)

```bash
bb install-hooks
```

Registers `PreToolUse`/`PostToolUse` hooks so unbalanced delimiters are repaired
on every Write and Edit, and an Edit that cannot be repaired is rolled back
rather than left broken. Off by default; `bb uninstall-hooks` removes it.

### Optional: cider-nrepl

Add the middleware to the alias you start your REPL with:

```clojure
{:aliases
 {:nrepl {:extra-deps {nrepl/nrepl {:mvn/version "1.4.0"}
                       cider/cider-nrepl {:mvn/version "0.62.2"}}
          :main-opts ["-m" "nrepl.cmdline"
                      "--middleware" "[cider.nrepl/cider-middleware]"]}}}
```

`clj-skill repl ports` marks which running servers have it.

## Usage

Run `clj-skill --help` for the full command list, or read
[SKILL.md](./SKILL.md) — that is what the agent reads.

```bash
clj-skill outline src/my/ns.clj                    # 2000 lines -> 40
clj-skill find '(defmethod &)' src                 # search by shape
clj-skill replace src/my/ns.clj '(defn my-fn &)' <<'EOF'
(defn my-fn [x] (inc x))
EOF
clj-skill repl eval -p 7888 <<'EOF'
(+ 1 2 3)
EOF
clj-skill cider test my.ns-test                    # only what failed
clj-skill cider stacktrace                         # only your frames
```

## Development

```bash
bb test        # clojure.test over test/
bb tasks       # everything available
```

## Uninstall

```bash
bb uninstall                          # binary + hooks (also removes pre-1.0 binaries)
rm ~/.claude/skills/clojure-skills    # and any other symlinks you made
```

## Attribution

Derived from [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light)
by Bruce Hauman (EPL-2.0).

## License

[Eclipse Public License 2.0](./LICENSE)

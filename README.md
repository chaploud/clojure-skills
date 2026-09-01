# clojure-skills

An [Agent Skill](https://agentskills.io) that gives a coding agent Clojure-aware
tools in place of the line-oriented ones it would otherwise reach for: structural
search and editing instead of `sed`, a file outline instead of reading two
thousand lines, failing assertions instead of a test log, and the three project
frames of a stacktrace instead of sixty.

Follows the [Agent Skills](https://agentskills.io) open standard, so it works
with Claude Code, Codex CLI, Gemini CLI and the other clients that adopted it.
Covers `.clj`, `.cljs`, `.cljc`, `.cljd`, `.edn`, `.bb` and `.lpy`.

## Install

```bash
git clone https://github.com/chaploud/clojure-skills.git ~/clojure-skills
cd ~/clojure-skills
bb install
```

That installs one binary, `clj-skill`, and then runs `clj-skill doctor`, which
reports what works on your machine and how to enable the rest. Running it again
is safe.

Then link the skill where your agents look for one:

```bash
# Codex CLI and Gemini CLI both read this one
mkdir -p ~/.agents/skills && ln -s ~/clojure-skills ~/.agents/skills/clojure-skills

# Claude Code
mkdir -p ~/.claude/skills && ln -s ~/clojure-skills ~/.claude/skills/clojure-skills
```

### Requirements

| Tool | Required | Install |
|---|---|---|
| [Babashka](https://github.com/babashka/babashka#installation) | yes | `brew install borkdude/brew/babashka` |
| [bbin](https://github.com/babashka/bbin) | yes | `bb install io.github.babashka/bbin` |
| [ripgrep](https://github.com/BurntSushi/ripgrep) | no | `brew install ripgrep` — keeps `find` fast on large trees |
| [clojure-lsp](https://clojure-lsp.io/installation/) | no | `brew install clojure-lsp/brew/clojure-lsp-native` — enables `clj-skill lsp` |
| [cider-nrepl](https://github.com/clojure-emacs/cider-nrepl) | no | a REPL dependency, see below — enables `clj-skill cider` |

Nothing optional is assumed: a command whose prerequisite is missing says so and
names the alternative, rather than failing or quietly returning nothing.

## Commands

Run `clj-skill --help` for the full list, or read [SKILL.md](./SKILL.md) — that
is what the agent reads.

### Structure — needs nothing but Babashka

```bash
clj-skill outline src/my/ns.clj          # every top-level form as path:start-end
clj-skill find '(defmethod &)' src       # search by shape: _ is any form, & is the rest
clj-skill replace src/my/ns.clj '(defn my-fn &)' <<'EOF'
(defn my-fn [x] (inc x))
EOF
clj-skill repair src/my/ns.clj           # balance delimiters and format
```

`outline` turns a 2000-line file into 40 lines the agent can read a range out of.
`replace` swaps one whole top-level form and leaves every other byte alone; it
refuses to write unless the pattern matches exactly one form.

### REPL — needs a running nREPL server

```bash
clj-skill repl ports                     # servers here, and which have cider-nrepl
clj-skill repl eval -p 7888 <<'EOF'
(+ 1 2 3)
EOF
```

State persists between calls. `--port` can be left out when exactly one
discovered server's directory is the working directory.

### Runtime queries — needs the cider-nrepl middleware

```bash
clj-skill cider info my-fn --ns my.ns    # where it is defined, arglists, doc
clj-skill cider refs my-fn --ns my.ns    # who calls it
clj-skill cider test my.ns-test          # only what failed, with expected/actual
clj-skill cider stacktrace               # last exception, project frames only
clj-skill cider inspect '(big-thing)'    # a paged view instead of the whole value
```

To enable these, add the middleware to the alias you start your REPL with:

```clojure
{:aliases
 {:nrepl {:extra-deps {nrepl/nrepl {:mvn/version "1.4.0"}
                       cider/cider-nrepl {:mvn/version "0.62.2"}}
          :main-opts ["-m" "nrepl.cmdline"
                      "--middleware" "[cider.nrepl/cider-middleware]"]}}}
```

### Static analysis — needs clojure-lsp

```bash
clj-skill lsp diagnostics --file src/my/ns.clj
clj-skill lsp references --file src/my/ns.clj --line 10 --col 5
clj-skill lsp definition --file src/my/ns.clj --line 10 --col 5
clj-skill lsp hover      --file src/my/ns.clj --line 10 --col 5
```

`cider` answers about what is loaded right now; `lsp` answers from a static index
and works on code that was never evaluated.

## Optional: automatic paren repair (Claude Code)

```bash
bb install-hooks
```

Registers hooks so unbalanced delimiters are repaired on every Write and Edit,
and an Edit that cannot be repaired is rolled back instead of left broken. Off by
default; `bb uninstall-hooks` removes it.

## Development

```bash
bb test     # clojure.test over test/
bb tasks    # everything available
```

## Uninstall

```bash
bb uninstall                          # binary + hooks
rm ~/.claude/skills/clojure-skills    # and any other symlinks you made
```

## Upgrading from before the single-binary rewrite

Up to [`37dda76`](https://github.com/chaploud/clojure-skills/commit/37dda76) this
skill installed five separate binaries. They are now one, `clj-skill`, with the
old tools as subcommands:

| Before | Now |
|---|---|
| `clj-paren-repair FILE` | `clj-skill repair FILE` |
| `clj-nrepl-eval -p PORT CODE` | `clj-skill repl eval -p PORT CODE` |
| `clj-nrepl-eval --discover-ports` | `clj-skill repl ports` |
| `clj-nrepl-eval --connected-ports` | `clj-skill repl connected` |
| `clj-nrepl-eval -p PORT --reset-session` | `clj-skill repl reset -p PORT` |
| `clj-lsp-client diagnostics` | `clj-skill lsp diagnostics` |
| `clj-lsp-client references --file F --line N --col N` | `clj-skill lsp references --file F --line N --col N` |
| `clj-lsp-bridge warm ROOT` | `clj-skill lsp-bridge warm ROOT` |
| `clj-paren-repair-claude-hook` | `clj-skill hook` |

Run `git pull && bb install` from your clone. `bb install` removes the five old
binaries, and `bb install-hooks` rewrites the hook entry in
`~/.claude/settings.json` if you had it. Anything of your own that calls the old
names — shell aliases, editor config, scripts — needs updating by hand.

## Attribution

Derived from [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light) by
Bruce Hauman (EPL-2.0).

## License

[Eclipse Public License 2.0](./LICENSE)

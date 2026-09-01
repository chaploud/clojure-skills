---
name: clojure-skills
description: >-
  Clojure/ClojureScript/ClojureDart development skill. Activates when
  working with .clj, .cljs, .cljc, .cljd, .edn, .bb files. Provides
  structural search and editing of S-expressions, nREPL evaluation,
  runtime queries via cider-nrepl, and static navigation via clojure-lsp.
compatibility: >-
  Every command is a shell command, so any agent with shell access can use
  them. The optional automatic paren-repair hooks are Claude Code specific.
license: EPL-2.0
paths:
  - "**/*.clj"
  - "**/*.cljs"
  - "**/*.cljc"
  - "**/*.cljd"
  - "**/*.edn"
  - "**/*.bb"
  - "**/*.lpy"
user-invocable: false
hooks:
  Stop:
    - hooks:
        - type: command
          command: "clj-skill lsp stop 2>/dev/null; true"
---

# Clojure Development Skill

All commands live under a single binary, `clj-skill`.
Line and column numbers are **1-based everywhere**, matching editors and ripgrep.

## Check the environment first

!`command -v clj-skill >/dev/null && clj-skill doctor || echo "clj-skill NOT INSTALLED — git clone https://github.com/chaploud/clojure-skills.git ~/clojure-skills && cd ~/clojure-skills && bb install"`

The report above says which command groups work here. Do not attempt a group it
reports as unavailable — use the alternative named below instead.

## Which command to reach for

| Goal | Command | Needs |
|---|---|---|
| Map a large file before reading it | `clj-skill outline FILE` | nothing |
| Find code by its *shape* | `clj-skill find PATTERN [PATH…]` | nothing |
| Rewrite a whole top-level form | `clj-skill replace FILE PATTERN` | nothing |
| Find code by its *text* | `rg` | ripgrep |
| Balance delimiters / format | `clj-skill repair FILE…` | nothing |
| Evaluate code | `clj-skill repl eval` | nREPL |
| Callers of a function | `clj-skill cider refs SYM` | cider-nrepl |
| Where a symbol is defined | `clj-skill cider info SYM` | cider-nrepl |
| Run tests | `clj-skill cider test [NS…]` | cider-nrepl |
| Explain the last exception | `clj-skill cider stacktrace` | cider-nrepl |
| Look inside a huge value | `clj-skill cider inspect CODE` | cider-nrepl |
| References / definition / hover | `clj-skill lsp …` | clojure-lsp |
| Project diagnostics | `clj-skill lsp diagnostics` | clojure-lsp |

`cider` answers about **what is loaded right now**; `lsp` answers from a
**static index** and works on code that was never evaluated. When both are
available, prefer `lsp` for navigation and `cider` for behaviour — tests,
exceptions and values.

## Structural commands (always available)

### outline — read a big file cheaply

```bash
clj-skill outline src/my/ns.clj
```

Prints `path:START-END: <first line of the form>` for every top-level form. Use
it before reading a file over a few hundred lines, then read only the range you
need with `sed -n 'START,ENDp' FILE`.

### find — search by shape, not by text

```bash
clj-skill find '(defmethod &)' src
clj-skill find '(rf/reg-event-fx _ &)' src/app --limit 100
```

A pattern is a Clojure form in which:

- `_` matches any single form
- `&` matches the rest of a sequence, and must be its last element

Everything else matches exactly as written:

- collection type counts — `(a b)` does not match `[a b]`
- arity is exact without a trailing `&` — `(inc _)` matches `(inc 1)`, not `(inc 1 2)`
- collections match in written order — `{:a 1 :b 2}` does not match `{:b 2 :a 1}`
- `'x` and `(quote x)` are different forms and do not match each other
- metadata is seen through — `(defn foo &)` matches `(defn ^:private foo [] 1)`

Prints `path:line:col: <first line of the match>`. Defaults to 50 matches;
raise it with `--limit`.

Use `rg` when you are looking for a name or a string. Use `find` when the
*shape* is the question and no substring identifies it:

```bash
clj-skill find '(defn _ [_ _] &)' src        # every two-argument defn
clj-skill find '(defn _ _ {:status _} &)' src # a handler taking a status map
clj-skill find '(assoc _ :id &)' src          # calls that set :id
clj-skill find '(defmethod _ [::a ::b] &)' src # one multimethod dispatch value
clj-skill find '{:status status}' src         # an argument destructured this way
```

That last one is the case `rg` cannot narrow: `:status` appears everywhere, but
`{:status status}` as a *form* appears only where it is destructured.

### replace — edit a whole form

```bash
clj-skill replace src/my/ns.clj '(defn my-fn &)' <<'EOF'
(defn my-fn [x]
  (inc x))
EOF
```

Reads the new form from stdin and swaps it in, leaving every other byte of the
file — comments, blank lines, indentation of neighbouring forms — untouched.

It **refuses to write** unless the pattern matches exactly one top-level form,
and lists the candidates when it matches several. Prefer this over `Edit` for
whole-function rewrites: it cannot mismatch on whitespace and cannot unbalance
the file. Use `--dry-run` to see the effect first.

### repair — fix delimiters

```bash
clj-skill repair src/my/ns.clj              # in place
echo '(defn f [x] (+ x 1' | clj-skill repair # as a filter
```

**Do not hand-count parentheses.** If a file has unbalanced delimiters, run
`clj-skill repair` on it. If that does not fix it, tell the user; do not guess.

## REPL

**Always use a heredoc** — `zsh` mangles `!` inside a quoted argument.

```bash
clj-skill repl eval -p 7888 <<'EOF'
(require '[my.ns :as n] :reload)
(n/my-fn 42)
EOF
```

- `clj-skill repl ports` — servers on this machine, and whether each has cider-nrepl
- `--port` may be omitted when exactly one discovered server's directory is the
  working directory; otherwise you are told to pass it
- State persists between calls, per host:port. Use `:reload` when requiring a
  namespace you just edited
- `clj-skill repl reset -p PORT` starts a fresh session

A form that switches the REPL's evaluation environment — `(shadow/repl :build)`,
`:cljs/quit` — takes effect for the **next** call, not for the rest of the
heredoc it appears in. nREPL evaluates the whole heredoc as one request, and the
switch applies to the request after it. Send the switch on its own:

```bash
clj-skill repl eval -p 7888 '(shadow/repl :app)'
clj-skill repl eval -p 7888 '(js/parseInt "42")'
```

## Runtime queries (cider-nrepl)

```bash
clj-skill cider info my-fn --ns my.ns        # definition site, arglists, doc
clj-skill cider refs my-fn --ns my.ns        # who calls it
clj-skill cider deps my-fn --ns my.ns        # what it calls
clj-skill cider test my.ns-test              # failures only, with expected/actual
clj-skill cider retest                       # only what failed last time
clj-skill cider stacktrace                   # last exception, project frames only
clj-skill cider inspect '(big-thing)'        # paged view instead of the whole value
clj-skill cider ops                          # what this server supports
```

`test` reports only what broke, and exits non-zero when anything did. It can only
run namespaces the REPL has already loaded, so require the test namespace first:

```bash
clj-skill repl eval -p 7888 "(require 'my.ns-test :reload)"
clj-skill cider test -p 7888 my.ns-test
```

`stacktrace` reads the exception raised by the last `repl eval` — the session is
shared — and prints only frames in project code; pass `--all` for the rest.

`refs` and `deps` read Clojure vars on the JVM. On a ClojureScript session they
say so and exit non-zero rather than reporting no callers; use
`clj-skill lsp references` there.

If the server lacks cider-nrepl, each command says so and names the static
alternative. `clj-skill cider ops` lists what the server does support.

## Static navigation (clojure-lsp)

```bash
clj-skill lsp diagnostics --file src/my/ns.clj
clj-skill lsp references --file src/my/ns.clj --line 10 --col 5
clj-skill lsp definition --file src/my/ns.clj --line 10 --col 5
clj-skill lsp hover      --file src/my/ns.clj --line 10 --col 5
```

The bridge starts on first use and is reused. The project root is detected by
walking up from `--file` to the nearest `deps.edn`/`project.clj`/`bb.edn`/
`shadow-cljs.edn`, so several projects added with `/add-dir` each get their own.

A definition inside a dependency is printed as `/abs/to/foo.jar:inner/ns.clj:LINE:COL`.

The bridge writes its own output to
`~/.cache/clj-skill/lsp-bridge/<hash>.log` — read it when a query times out.

The first start on a cold, large project can take minutes while clojure-lsp
indexes. Run `clj-skill lsp-bridge warm ROOT` ahead of time — at login, or after
creating a worktree — to do that indexing before it is needed.

## Notes

- All variants are supported: `.clj`, `.cljs`, `.cljc`, `.cljd` (ClojureDart),
  `.edn`, `.bb`, `.lpy`, plus files with a `bb` shebang
- Reader conditionals, `#js`, `#dart` and other tagged literals are handled
  throughout — structural commands compare what is written, not what it expands to
- Automatic paren repair on every Write/Edit is opt-in: `bb install-hooks`
- A command that could not do its job exits non-zero and says why on stderr.
  `;; no match` and `;; no references` mean the search ran and found nothing —
  they are never printed for a search that failed
- `find` stops at `--limit` matches, so files after that point are not read.
  When the count matters, raise the limit

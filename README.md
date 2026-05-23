# Arx

Architecture health monitoring for Java and TypeScript codebases. Tracks dependency violations, module coupling, churn hotspots, and architectural drift over git history — all from a single binary.

## Quick start

```bash
# Generate a blueprint from your repo
arx infer --repo .

# Review the output, save it
arx infer --repo . > arch.blu

# Analyze architecture health
arx scan --repo . --blueprint arch.blu
```

That's it. Two commands, zero to value.

---

## Installation

### Native binary (recommended)

Download the binary for your platform from [GitHub Releases](https://github.com/WattWozy/arx/releases/latest):

| Platform | Binary |
|----------|--------|
| Linux x86-64 | `arx-linux-amd64` |
| macOS arm64 | `arx-darwin-arm64` |
| Windows x86-64 | `arx-windows-amd64.exe` |

```bash
# Linux / macOS
chmod +x arx-linux-amd64
mv arx-linux-amd64 /usr/local/bin/arx
```

### Fat JAR (any platform with Java 21+)

```bash
java -jar arx.jar scan --repo . --blueprint arch.blu
```

### Build from source

Requires GraalVM 21.

```bash
mvn package -Pnative -DskipTests
# binary at target/arx
```

---

## The blueprint

A blueprint is a plain-text file that declares your intended architecture. Three directives:

```
scope <relative-path>           (optional, for monorepos)
module <name>  <package-pattern>  [layer=<N>]
allow  <source> -> <target>
```

Blueprint files conventionally use the `.blu` extension. The parser also accepts `.blueprint` for backward compatibility.

**Example — clean layered architecture:**

```
module domain      dev.myapp.domain.**      layer=0
module application dev.myapp.application.** layer=1
module adapter     dev.myapp.adapter.**     layer=2

allow application -> domain
allow adapter     -> application
allow adapter     -> domain
```

Everything not in an `allow` rule is a violation. Modules at lower `layer` numbers are inner (more stable); higher `layer` is outer.

### Monorepo / subdirectory scans

If your repo contains multiple services, pass the subdirectory as `--repo`. `arx` walks up from there to find the git root automatically, then restricts all history and file analysis to that subtree:

```bash
arx infer --repo services/billing > billing.blu
arx scan  --repo services/billing --blueprint billing.blu
```

`infer` emits a `scope services/billing` line at the top of the output so the blueprint is self-describing. Each subdirectory scan is stored independently in the H2 database — `/monolith/services/billing` and `/monolith` are separate entries.

### Generating a blueprint with `infer`

```bash
arx infer --repo . > arch.blu
arx infer --repo . --depth 3 > arch.blu
```

`infer` scans your source, groups packages by prefix depth, and emits `module` + `allow` declarations from observed imports. When `--repo` points to a subdirectory of a git repo, a `scope` line is prepended automatically. Edit the output to reflect your *intended* architecture (the inferred deps are your current actual deps — the point is to tighten them).

---

## Subcommands

### `scan` — full analysis report

```bash
arx scan --repo <path> --blueprint <path> [options]
```

Analyzes git history, computes all metrics, and prints a health report.

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--blueprint <path>` | — | Blueprint file (required) |
| `--commits <n>` | 20 | Number of commits to analyze |
| `--format <fmt>` | `console` | `console` \| `json` \| `markdown` \| `html` \| `ai-feedback` |
| `--out <file>` | stdout | Write output to file |
| `--language <lang>` | `java` | `java` \| `typescript` \| `auto` |
| `--coverage <file>` | — | JaCoCo XML or `lcov.info` for CRAP scores |
| `--db <path>` | `~/.arx/arx` | H2 database file for persistent history (created automatically) |

**Examples:**

```bash
# Console report (default)
arx scan --repo . --blueprint arch.blu

# Export HTML report
arx scan --repo . --blueprint arch.blu --format html --out report.html

# Analyze last 50 commits with coverage
arx scan --repo . --blueprint arch.blu \
  --commits 50 --coverage target/site/jacoco/jacoco.xml

# TypeScript monorepo
arx scan --repo . --blueprint arch.blu --language typescript

# Store results in a team-shared DB
arx scan --repo . --blueprint arch.blu --db /shared/team/arx.db
```

---

### `check` — CI gate

```bash
arx check --repo <path> --blueprint <path> [options]
```

Like `scan` but designed for pipelines: silent on pass, exits 1 on violations.

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--blueprint <path>` | — | Blueprint file (required) |
| `--commits <n>` | 20 | Number of commits to analyze |
| `--language <lang>` | `java` | `java` \| `typescript` \| `auto` |
| `--coverage <file>` | — | JaCoCo XML or `lcov.info` |
| `--db <path>` | `~/.arx/arx` | H2 database file for persistent history |
| `--fail-on <condition>` | `any-violations` | See conditions below |

**Fail conditions:**

| Condition | Triggers when |
|-----------|--------------|
| `any-violations` | Any violation exists (default) |
| `new-violations` | New violations appeared since previous commit |
| `new-cycles` | Dependency cycles exist in latest snapshot |
| `stale-blueprint` | Blueprint declares modules with no matching files |
| `instability-threshold=<N>` | Any module instability exceeds N (0.0–1.0) |

Multiple `--fail-on` flags are OR'd together.

**GitHub Actions example:**

```yaml
- name: Architecture check
  run: |
    arx check \
      --repo . \
      --blueprint arch.blu \
      --fail-on new-violations \
      --fail-on new-cycles
```

---

### `watch` — real-time feedback

```bash
arx watch --blueprint <path> [options]
```

Two modes depending on whether `--changed` is provided:

**Filesystem watcher (continuous)** — monitors source files and re-analyzes on every save:

```bash
arx watch --blueprint arch.blu --src src/main/java
arx watch --blueprint arch.blu --repo .
```

**One-shot incremental** — analyzes only the listed changed files against the last committed baseline:

```bash
arx watch --blueprint arch.blu --changed Foo.java Bar.java
git diff --name-only | arx watch --blueprint arch.blu --changed
```

| Flag | Default | Description |
|------|---------|-------------|
| `--blueprint <path>` | — | Blueprint file (required) |
| `--src <dir>` | `<repo>/src/main/java` | Source directory |
| `--repo <path>` | — | Git repository (for baseline in incremental mode) |
| `--language <lang>` | `java` | `java` \| `typescript` \| `auto` |
| `--format <fmt>` | `console` | `console` \| `ai-feedback` |
| `--changed <files>...` | — | Triggers one-shot incremental mode |

**AI harness mode** — incremental with `ai-feedback` format outputs structured JSON for AI coding assistants:

```bash
arx watch --blueprint arch.blu \
  --changed src/Foo.java \
  --format ai-feedback
```

---

### `infer` — blueprint generation

```bash
arx infer --repo <path> [--depth 2]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--depth <n>` | 2 | Package segments after common prefix to use as module name |

Scans all `.java` files, groups by package prefix, and emits a blueprint draft. Increase `--depth` for more granular modules.

---

### `query` — natural language interface

```bash
arx query --repo <path> --blueprint <path> "question"
```

Asks an LLM about your architecture based on the current metrics and violations.

```bash
arx query --repo . --blueprint arch.blu "where is my highest risk?"
arx query --repo . --blueprint arch.blu "which modules should I refactor first?"
arx query --repo . --blueprint arch.blu "explain the current violations"
```

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--blueprint <path>` | — | Blueprint file (required) |
| `--commits <n>` | 20 | Commits to include in context |

Requires `ARX_API_KEY` (Anthropic API key). Optionally set `ARX_MODEL` to override the model (default: `claude-haiku-4-5-20251001`).

---

### `mcp-serve` — MCP server for AI tools

```bash
arx mcp-serve [--db <path>]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--db <path>` | `~/.arx/arx` | H2 database file; enables history tools and fast trend queries |

Starts an [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server on stdio. Lets Claude Code and other MCP-compatible AI tools call arx directly as a set of structured tools — no shell commands, no parsing, just typed inputs and structured JSON output.

#### Setting up with Claude Code

Add to your project's `.claude/settings.json` (or `~/.claude/settings.json` for global):

**Native binary:**
```json
{
  "mcpServers": {
    "arx": {
      "command": "arx",
      "args": ["mcp-serve"]
    }
  }
}
```

**Fat JAR:**
```json
{
  "mcpServers": {
    "arx": {
      "command": "java",
      "args": ["-jar", "/path/to/arx.jar", "mcp-serve"]
    }
  }
}
```

Restart Claude Code after editing. The tools appear automatically — Claude will use them when you ask architecture questions.

#### Available tools

| Tool | Description |
|------|-------------|
| `check_violations` | Violations in the repo or filtered to specific files |
| `get_metrics` | Per-module instability, fan-in/out, hotspot, CRAP, hub score |
| `infer_blueprint` | Infer a blueprint from package structure |
| `scan_report` | Full report: violations, cycles, chronics, trend, metrics |
| `query_architecture` | Natural language question answered with full context |
| `get_violation_trend` | Violation counts per commit over N recent commits (DB-first, falls back to live scan) |
| `get_metric_history` | Instability, abstractness, hub score trends for a module over time (requires `--db`) |
| `get_hotspot_history` | Hotspot score history for a file or module over time (requires `--db`) |
| `is_scanned` | Check if a commit has already been scanned with the current blueprint (requires `--db`) |

#### Example prompts in Claude Code

```
What are the current architecture violations in my repo at /Users/me/myapp using arch.blu?

Which modules are highest risk? Use arx get_metrics on /Users/me/myapp.

Infer a blueprint for /Users/me/myapp and show me the module structure.

Run a full architecture scan on /Users/me/myapp — I want to see cycles, chronics, and hotspots.
```

Claude will call the appropriate tool, receive structured JSON, and reason over the results directly.

#### Notes

- `query_architecture` requires `ARX_API_KEY` in the environment where `arx mcp-serve` runs
- All tools accept absolute paths for `repo` and `blueprint`
- The server logs to stderr; stdout is exclusively JSON-RPC
- History tools (`get_metric_history`, `get_hotspot_history`, `is_scanned`) return empty results when no DB is available — they never block the scan
- `get_violation_trend` queries the DB first (fast); falls back to a live git scan if no DB data exists and stores the results automatically

---

## Persistent history

Arx optionally writes scan results to an embedded H2 database. This enables:

- **Fast trend queries** — `get_violation_trend` returns in milliseconds from DB instead of re-scanning git history
- **Metric history** — track instability, hub score, and other metrics per module across commits
- **Hotspot history** — watch hotspot scores evolve over time for a file or module
- **Skip-if-scanned** — check whether a commit was already analyzed before scheduling work

The database is created automatically at `~/.arx/arx.mv.db` on first use. No setup required.

```bash
# Default location: ~/.arx/arx.mv.db
arx scan --repo . --blueprint arch.blu

# Custom location (e.g. team-shared or CI workspace)
arx scan --repo . --blueprint arch.blu --db /shared/arx.db
arx mcp-serve --db /shared/arx.db
```

If the DB can't be created (permissions, disk full), arx logs a warning to stderr and continues normally. Every command works without the DB — it's a performance and history enhancement, never a requirement.

---

## Metrics reference

Every module in the latest snapshot gets these metrics:

| Metric | Description |
|--------|-------------|
| **Fan-In** | Modules that depend on this module |
| **Fan-Out** | Modules this module depends on |
| **Instability** | `Fan-Out / (Fan-In + Fan-Out)`. 0 = stable, 1 = unstable |
| **Abstractness** | Ratio of abstract types (interfaces/abstract classes) |
| **Distance** | `|Abstractness + Instability − 1|`. 0 = on main sequence |
| **WMC** | Weighted Method Count — sum of method complexities |
| **Hotspot** | `WMC × commit count` — high = complex and frequently changed |
| **ChurnAcceleration** | Rate of change increase across recent commits |
| **BusFactor Risk** | Concentration of commits among few authors |
| **PageRank** | Graph centrality — how many other modules point to this |
| **Betweenness** | How often this module lies on shortest dependency paths |
| **HubScore** | `PageRank × Betweenness × WMC` — architectural risk multiplier |
| **CRAP Score** | `Complexity² × (1 − coverage)²`. Requires `--coverage` |
| **Test Debt** | Aggregate uncovered complexity. Requires `--coverage` |

**Trend:** Arx analyzes N commits, computing a snapshot at each one, and reports whether violations are IMPROVING, STABLE, or DEGRADING over time.

**Violations** that persist 3+ snapshots are flagged as **chronic**.

**Cycles** are detected with Tarjan's SCC algorithm. Any SCC of size ≥ 2 is reported.

**Refactoring suggestions** are emitted when a module is too large (SPLIT) or when two modules are tightly coupled with no violations (MERGE candidate).

**Architecture communities** group modules by their actual coupling graph (union-find), and flag cross-layer communities as warnings.

---

## Output formats

| Format | Use case |
|--------|----------|
| `console` | Human-readable terminal report |
| `json` | Machine-readable, for dashboards or further processing |
| `markdown` | Documentation, PR comments |
| `html` | Standalone report with styled tables |
| `ai-feedback` | Structured JSON for AI coding assistants (file + line + fix suggestion) |

---

## Environment variables

| Variable | Description |
|----------|-------------|
| `ARX_API_KEY` | Anthropic API key (required for `query`) |
| `ARX_MODEL` | Model for `query` (default: `claude-haiku-4-5-20251001`) |

---

## Coverage integration

Pass a JaCoCo XML or lcov report to get CRAP scores and test debt per module:

```bash
# Maven: generate coverage first
mvn test jacoco:report

arx scan --repo . --blueprint arch.blu \
  --coverage target/site/jacoco/jacoco.xml
```

```bash
# JavaScript/TypeScript with lcov
arx scan --repo . --blueprint arch.blu \
  --coverage coverage/lcov.info
```

CRAP score: `complexity² × (1 − line_coverage)²`. A score above 30 indicates a method that is both complex and poorly tested.

---

## Blueprint syntax reference

```
# comment

# Optional: restrict scan to a subdirectory of the git root (monorepo use)
scope <relative-path-from-git-root>

# Declare a module: name, package glob, optional layer
module <name>  <package-prefix>.**  [layer=<N>]

# Permit a dependency direction
allow <source-module> -> <target-module>
```

- `scope` is optional metadata — `arx infer` emits it automatically for subdirectory scans; `arx scan` reads it but does not enforce it
- Package patterns support `.**` suffix (matches the prefix and all sub-packages)
- `layer=0` is innermost (domain), higher numbers are outer (adapters, infrastructure)
- Dependencies from lower layer to higher layer are flagged with dependency-inversion guidance
- Multiple `module` lines with the same name add multiple package patterns to the same module

**Full example:**

```
module domain         com.myapp.domain.**          layer=0
module application    com.myapp.application.**     layer=1
module ports          com.myapp.application.port.** layer=1
module adapter-web    com.myapp.adapter.web.**     layer=2
module adapter-db     com.myapp.adapter.db.**      layer=2
module adapter-cli    com.myapp.adapter.cli.**     layer=2

allow application  -> domain
allow application  -> ports
allow adapter-web  -> application
allow adapter-web  -> ports
allow adapter-db   -> application
allow adapter-db   -> ports
allow adapter-cli  -> application
allow adapter-cli  -> ports
```

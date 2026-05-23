# Arx — Reference Documentation

Full reference for all commands, flags, formats, and configuration. For an overview, see [README.md](README.md).

---

## Installation options

### Native binary (recommended)

| Platform | Binary |
|----------|--------|
| Linux x86-64 | `arx-linux-amd64` |
| macOS arm64 | `arx-darwin-arm64` |
| Windows x86-64 | `arx-windows-amd64.exe` |

```bash
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

## Blueprint syntax

```
# comment

# Optional: restrict scan to a subdirectory of the git root (monorepo use)
scope <relative-path-from-git-root>

# Declare a module: name, package glob, optional layer
module <name>  <package-prefix>.**  [layer=<N>]

# Permit a dependency direction
allow <source-module> -> <target-module>
```

- `scope` is optional metadata — `arx infer` emits it automatically for subdirectory scans
- Package patterns support `.**` suffix (matches the prefix and all sub-packages)
- `layer=0` is innermost (domain), higher numbers are outer
- Dependencies from lower layer to higher layer are flagged with dependency-inversion guidance
- Multiple `module` lines with the same name add multiple package patterns to the same module

### Full example

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

### Monorepo / subdirectory scans

If your repo contains multiple services, pass the subdirectory as `--repo`. Arx walks up from there to find the git root automatically, then restricts all history and file analysis to that subtree:

```bash
arx infer --repo services/billing > billing.blu
arx scan  --repo services/billing --blueprint billing.blu
```

`infer` emits a `scope services/billing` line at the top of the output so the blueprint is self-describing. Each subdirectory scan is stored independently in the H2 database.

---

## Command reference

### `scan` — full analysis report

```bash
arx scan --repo <path> --blueprint <path> [options]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--blueprint <path>` | — | Blueprint file (required) |
| `--commits <n>` | 20 | Number of commits to analyze |
| `--format <fmt>` | `console` | `console` \| `json` \| `markdown` \| `html` \| `ai-feedback` |
| `--out <file>` | stdout | Write output to file |
| `--language <lang>` | `java` | `java` \| `typescript` \| `auto` |
| `--coverage <file>` | — | JaCoCo XML or `lcov.info` for CRAP scores |
| `--db <path>` | `~/.arx/arx` | H2 database file for persistent history |

Examples:

```bash
arx scan --repo . --blueprint arch.blu
arx scan --repo . --blueprint arch.blu --format html --out report.html
arx scan --repo . --blueprint arch.blu --commits 50 --coverage target/site/jacoco/jacoco.xml
arx scan --repo . --blueprint arch.blu --language typescript
arx scan --repo . --blueprint arch.blu --db /shared/team/arx.db
```

### `check` — CI gate

```bash
arx check --repo <path> --blueprint <path> [options]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--blueprint <path>` | — | Blueprint file (required) |
| `--commits <n>` | 20 | Number of commits to analyze |
| `--language <lang>` | `java` | `java` \| `typescript` \| `auto` |
| `--coverage <file>` | — | JaCoCo XML or `lcov.info` |
| `--db <path>` | `~/.arx/arx` | H2 database file for persistent history |
| `--fail-on <condition>` | `any-violations` | See conditions below |

Fail conditions:

| Condition | Triggers when |
|-----------|--------------|
| `any-violations` | Any violation exists (default) |
| `new-violations` | New violations appeared since previous commit |
| `new-cycles` | Dependency cycles exist in latest snapshot |
| `stale-blueprint` | Blueprint declares modules with no matching files |
| `instability-threshold=<N>` | Any module instability exceeds N (0.0–1.0) |

Multiple `--fail-on` flags are OR'd together.

### `watch` — real-time feedback

```bash
arx watch --blueprint <path> [options]
```

Two modes depending on whether `--changed` is provided:

**Filesystem watcher (continuous)** — monitors source files and re-analyzes on save:

```bash
arx watch --blueprint arch.blu --src src/main/java
arx watch --blueprint arch.blu --repo .
```

**One-shot incremental** — analyzes only listed changed files against committed baseline:

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

AI harness mode — incremental with `ai-feedback` format outputs structured JSON for AI coding assistants:

```bash
arx watch --blueprint arch.blu --changed src/Foo.java --format ai-feedback
```

### `infer` — blueprint generation

```bash
arx infer --repo <path> [--depth 2]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--depth <n>` | 2 | Package segments after common prefix to use as module name |

### `query` — natural language interface

```bash
arx query --repo <path> --blueprint <path> "question"
```

| Flag | Default | Description |
|------|---------|-------------|
| `--repo <path>` | — | Git repository root (required) |
| `--blueprint <path>` | — | Blueprint file (required) |
| `--commits <n>` | 20 | Commits to include in context |

Requires `ARX_API_KEY` environment variable.

```bash
arx query --repo . --blueprint arch.blu "where is my highest risk?"
arx query --repo . --blueprint arch.blu "which modules should I refactor first?"
```

### `mcp-serve` — MCP server for AI tools

```bash
arx mcp-serve [--db <path>]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--db <path>` | `~/.arx/arx` | H2 database file |

#### Claude Code setup

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

#### Available MCP tools

| Tool | Description |
|------|-------------|
| `check_violations` | Violations in the repo or filtered to specific files |
| `get_metrics` | Per-module instability, fan-in/out, hotspot, CRAP, hub score |
| `infer_blueprint` | Infer a blueprint from package structure |
| `scan_report` | Full report: violations, cycles, chronics, trend, metrics |
| `query_architecture` | Natural language question answered with full context |
| `get_violation_trend` | Violation counts per commit over N recent commits |
| `get_metric_history` | Instability, abstractness, hub score trends for a module over time |
| `get_hotspot_history` | Hotspot score history for a file or module over time |
| `is_scanned` | Check if a commit has already been scanned |

Notes:
- `query_architecture` requires `ARX_API_KEY` in the environment where `arx mcp-serve` runs
- All tools accept absolute paths for `repo` and `blueprint`
- The server logs to stderr; stdout is exclusively JSON-RPC
- History tools return empty results when no DB is available
- `get_violation_trend` queries DB first (fast), falls back to live git scan

---

## Metrics reference

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

Violations persisting 3+ snapshots are flagged as **chronic**.

Cycles are detected with Tarjan's SCC algorithm. Any SCC of size ≥ 2 is reported.

Refactoring suggestions are emitted when a module is too large (SPLIT) or when two modules are tightly coupled with no violations (MERGE candidate).

Architecture communities group modules by actual coupling graph (union-find) and flag cross-layer communities as warnings.

---

## Persistent history (H2 database)

Arx writes scan results to an embedded H2 database, enabling fast trend queries, metric history tracking, hotspot evolution, and skip-if-scanned checks.

The database is created automatically at `~/.arx/arx.mv.db` on first use. No setup required.

```bash
# Default location
arx scan --repo . --blueprint arch.blu

# Custom location
arx scan --repo . --blueprint arch.blu --db /shared/arx.db
```

If the DB can't be created, arx logs a warning and continues normally. Every command works without the DB.

---

## Output formats

| Format | Use case |
|--------|----------|
| `console` | Human-readable terminal report |
| `json` | Machine-readable, for dashboards or further processing |
| `markdown` | Documentation, PR comments |
| `html` | Standalone report with styled tables |
| `ai-feedback` | Structured JSON for AI coding assistants |

---

## Environment variables

| Variable | Description |
|----------|-------------|
| `ARX_API_KEY` | Anthropic API key (required for `query`) |
| `ARX_MODEL` | Model for `query` (default: `claude-haiku-4-5-20251001`) |

---

## Coverage integration

Pass a JaCoCo XML or lcov report to get CRAP scores and test debt:

```bash
mvn test jacoco:report
arx scan --repo . --blueprint arch.blu --coverage target/site/jacoco/jacoco.xml

# TypeScript with lcov
arx scan --repo . --blueprint arch.blu --coverage coverage/lcov.info
```

CRAP score: `complexity² × (1 − line_coverage)²`. Above 30 indicates complex and poorly tested code.
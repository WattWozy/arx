# H2 Database — Schema & DAO Reference

## Tables

### `scan_results`
One row per distinct `(repo_path, commit_hash, blueprint_hash)` — the idempotency anchor.

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK AUTO_INCREMENT | |
| repo_path | VARCHAR(1024) NOT NULL | |
| commit_hash | VARCHAR(40) NOT NULL | |
| commit_time | TIMESTAMP NOT NULL | |
| blueprint_hash | VARCHAR(64) NOT NULL | hash of the rule config used |
| scanned_at | TIMESTAMP DEFAULT NOW | |

Unique constraint: `(repo_path, commit_hash, blueprint_hash)`

---

### `violations`
Raw violation details — one row per dependency rule breach.

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK | |
| scan_id | INTEGER FK → scan_results.id | |
| source_module | VARCHAR(256) NOT NULL | |
| target_module | VARCHAR(256) NOT NULL | |
| source_file | VARCHAR(1024) | nullable |
| line_number | INTEGER | nullable |
| suggested_fix | VARCHAR(2048) | nullable |

Indexes: `scan_id`, `(source_module, target_module)`

---

### `module_metrics`
Full metric snapshot per module per scan.

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK | |
| scan_id | INTEGER FK → scan_results.id | |
| module_name | VARCHAR(256) NOT NULL | |
| fan_in | INTEGER | incoming deps |
| fan_out | INTEGER | outgoing deps |
| instability | DOUBLE | fanOut / (fanIn + fanOut) |
| abstractness | DOUBLE | abstraction ratio |
| distance | DOUBLE | \|abstractness + instability − 1\| |
| hub_score | DOUBLE | network hub metric |
| crap_score | DOUBLE | Change Risk Anti-Pattern |
| wmc | INTEGER | Weighted Method Count |
| page_rank | DOUBLE | PageRank centrality |
| betweenness | DOUBLE | betweenness centrality |

Indexes: `scan_id`, `module_name`

> **Note:** `testDebtScore`, `churnAcceleration`, and `busFactorRisk` exist on the domain object `ModuleMetrics` but are **not persisted** — they are not mapped to columns.

---

### `hotspots`
File-level churn × complexity scores.

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK | |
| scan_id | INTEGER FK → scan_results.id | |
| file_path | VARCHAR(1024) NOT NULL | |
| churn | INTEGER | commit frequency |
| complexity | INTEGER | cyclomatic or similar |
| score | DOUBLE | combined hotspot score |

Index: `scan_id`

---

## Relationships

```
scan_results (1)
    ├── (N) violations
    ├── (N) module_metrics
    └── (N) hotspots
```

Child rows are **deleted then re-inserted** on each scan — no append-only history within a single scan_id. History comes from multiple scan_result rows across commits.

---

## DAO — `H2ScanResultStore`

Implements: `ScanResultStore` (port interface)

| Method | What it does |
|---|---|
| `storeScanResult(ScanRecord)` | MERGE into scan_results, DELETE+INSERT into all child tables. Atomic. Returns scan_id. |
| `hasBeenScanned(repoPath, commitHash, blueprintHash)` | Existence check on scan_results unique constraint. Used to skip re-scans. |
| `getViolationTrend(repoPath, moduleName, lastN)` | COUNT(violations) per commit, optional module filter, ordered by commit_time DESC. |
| `getMetricHistory(repoPath, moduleName, lastN)` | All 10 persisted metrics for a module across last N scans. |
| `getHotspotHistory(repoPath, filePath, lastN)` | churn/complexity/score for a file across last N scans. |

---

## Query Result DTOs

| DTO | Fields |
|---|---|
| `ViolationTrend` | commitHash, timestamp, violationCount |
| `MetricSnapshot` | commitHash, timestamp, moduleName, all 10 metrics |
| `HotspotSnapshot` | commitHash, timestamp, filePath, churn, complexity, score |

---

## What Is and Isn't Persisted

| Data | Persisted? |
|---|---|
| Violation source_file, line_number, suggested_fix | Yes (nullable) |
| All 10 module metrics | Yes |
| testDebtScore, churnAcceleration, busFactorRisk | **No** — domain only |
| Module packagePatterns / layer config | **No** — blueprint not stored |
| Raw class/method level data | **No** — module-level aggregates only |

---

## Connection

- In-memory (default): `jdbc:h2:mem:arx`
- File-based: `jdbc:h2:file:./path/to/db`
- Dependency: `com.h2database:h2:2.2.224`
- Schema created at startup via DDL in `H2ScanResultStore` constructor.

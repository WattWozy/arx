# Value-Features Extracted from ideas.patch

Source: jqassistant integration for a large Java monorepo ("Rider"). This patch adds a complete architectural analysis pipeline using Neo4j graph queries, JaCoCo coverage, git history, and GDS algorithms. Below are the discrete value-features worth replicating in archiTele.

---

## 1. Graph-Based Architecture Model

*What:* Scan Maven POMs + compiled bytecode + git history + JaCoCo XML into a Neo4j graph. Nodes = modules, classes, methods, authors, git files. Edges = DEPENDS_ON, EXTENDS, INVOKES, MODIFIES, etc.

*Why valuable:* Unlocks arbitrary cross-cutting queries impossible with static analysis alone — "which classes are both complex AND frequently changed AND poorly tested?"

*Key design choices:*
- Remote Neo4j store (bolt://localhost:7687) not embedded — separates scan from query, enables browser exploration
- jqassistant reset before each scan (idempotent full reload)
- JaCoCo + git + Maven all in one graph = cross-domain joins

---

## 2. Layered Metric Enrichment Pipeline

*What:* Ordered Cypher scripts (00-prereq/ -> 10-method/ -> 20-class/ -> 30-module/ -> 40-author/) that compute derived properties on graph nodes. All idempotent, all re-runnable.

*Why valuable:* Separates raw scan data from computed metrics. Each layer reads the previous layer's outputs. Adding a new metric = drop a file in the right folder.

*Key metrics by level:*

### Method-level (10-method/)
- metric_paramCount, metric_fanIn, metric_fanOut — shape
- lineCoverage, branchCoverage, crapScore — test quality (CRAP = cc^2 * (1-cov)^3 + cc)
- metric_hotspot = cyclomaticComplexity * file-level commitCount

### Class-level (20-class/)
- metric_methodCount, metric_fieldCount, metric_publicFieldCount — size
- metric_fanIn, metric_fanOut, metric_cbo (Coupling Between Objects) — coupling
- metric_rfc (Response For Class), metric_wmc (Weighted Methods per Class)
- metric_dit (Depth of Inheritance), metric_noc (Number of Children)
- metric_godScore = methodCount + fanIn + fanOut
- metric_hubScore = pageRank * betweenness * methodCount
- metric_lcom4 (Lack of Cohesion v4 via GDS WCC on method-coupling graph)
- metric_hotspot = wmc * commitCount
- metric_hotspotRecent = wmc * commitCount90d
- metric_hotspotCovered = sum(crapScore) * commitCount
- metric_churnAcceleration = commitCount30d / baseline60d (ratio > 1 = accelerating)

### Module-level (30-module/)
- metric_typeCount, metric_publicTypes, metric_abstractCount, metric_concreteCount
- metric_efferentCoupling (Ce), metric_afferentCoupling (Ca), metric_transitiveFanIn
- metric_internalDeps, metric_externalDeps
- metric_abstractness (A), metric_instability (I), metric_distance (D = |A+I-1|) — Robert C. Martin main-sequence
- metric_cohesionRatio, metric_connectivityRatio, metric_splitScore
- metric_bottleneckScore = betweenness * log(typeCount + 1)
- metric_testDebtScore = totalCrap * (undercoveredMethods / coveredMethods)
- metric_busFactorRisk = commitCount / authorCount
- metric_churnDensity = commitCount / typeCount
- metric_dominantAuthor, metric_dominantAuthorShare

### Author-level (40-author/)
- metric_commitCount, metric_filesTouched, metric_typesTouched, metric_modulesTouched
- metric_dominantModule, metric_dominantModuleShare
- metric_recentShare = commitCount90d / total (active vs departed)
- metric_commitsPerActiveDay
- metric_hotspotTypesTouched — who works in dangerous code

---

## 3. Graph Data Science (GDS) Integration

*What:* Neo4j GDS algorithms projected onto the class-graph and module-graph:
- PageRank (weighted by coupling edges)
- Betweenness centrality (sampled for perf)
- Louvain community detection (undirected)
- Strongly-connected components (directed)
- Triangle count (class-level)
- Weakly-connected components (for LCOM4 cohesion)

*Why valuable:*
- PageRank finds classes that are transitively important (not just direct fan-in)
- Betweenness finds architectural chokepoints
- Louvain detects natural clusters that may not match module boundaries — reveals module reorganization candidates
- SCC finds cycles of any length (not just 2-3 hop)
- WCC on method-coupling graph computes class cohesion (LCOM4)

---

## 4. Pre-Built Query Catalogue (30+ queries)

*What:* Read-only Cypher queries organized by concern, each answering one question:

### Refactoring targets
- god-classes (methodCount + fanIn + fanOut)
- high-wmc (sum of cyclomatic complexity > 50)
- high-cbo (coupling > 20 distinct collaborators)
- low-cohesion (LCOM4 > 1, methods split into independent clusters)
- long-methods (> 50 effective lines)
- many-params (> 5 parameters)
- high-complexity (cyclomatic > 10)

### Test gaps
- test-debt (modules ranked by totalCrap * undercovered ratio)
- untested-hotspots (high churn + high complexity + low coverage)
- uncovered-public-api (public methods with 0% line coverage)
- line-vs-branch-gap (happy-path tested, error branches not)
- crap-worst (CRAP > 30 threshold)

### Dead code / consolidation
- dead-code (no inbound DEPENDS_ON/EXTENDS/IMPLEMENTS)
- merge-candidates (tiny modules < 15 types coupled to one partner)
- split-candidates (large modules with low cohesion)

### Architecture health
- layer-violations (-pub/-api depending on -priv/-impl)
- internal-leaks (.internal./.priv. types referenced from other modules)
- cycles / scc-cycles (module-level circular deps)
- bidirectional-pairs (mutual module dependencies)
- cross-module-scc (class-level SCCs spanning multiple modules)
- deep-inheritance (DIT > 4)
- public-fields (encapsulation violations)
- communities (Louvain clusters split across modules)

### Blast radius
- high-fan-in (most inbound dependencies)
- architectural-hubs (pageRank * betweenness * methodCount)
- most-called methods
- bottleneck modules (betweenness * log typeCount)

### Git behavioral analysis
- git-hotspots (wmc * commitCount — where bugs cluster)
- churn-acceleration (ratio > 1 = getting worse)
- bus-factor (busy module, few contributors)
- dominant-author (> 60% of commits from one person)

### Team/author insights
- top-contributors (ranked by commits, with breadth and recency)
- focused-vs-generalist (specialist = high dominantModuleShare)
- recent-vs-departed (recentShare near 0 = departed contributor)
- hotspot-touchers (who works in the dangerous code)

---

## 5. Architectural Rule Enforcement (CI Gate)

*What:* XML constraint rules in jqassistant/rules/ that fail the build on violations.

*Example rule:* "priv module must not depend on another priv module" — enforces pub/priv SPI boundary. Has an exclusion list for known legacy debt (explicitly documented, to be removed over time).

*Design:*
- Constraints return rows = violations; zero rows = pass
- Severity: major = fail build, minor = warn
- Rules only affect the analyze phase (no rescan needed when iterating)
- Schema version v2.8 for jqassistant 2.9.x

---

## 6. Coverage Pipeline (Two Modes)

### Mode A: Standard (coverage.sh)
- Four sequential mvn test phases under -Pjacoco: units-fast, mysql-rider, mysql-accountx, mongodb
- JaCoCo agent in append mode -> cumulative per-module exec
- Single jacoco:report pass at the end

### Mode B: Bundled (coverage-bundle.sh)
- All modules' classes loaded into ONE JVM via bundle/junit.sh
- JUnit Platform Console Launcher with parallel execution
- Same four test suites but ~3-5x faster (eliminates 300 surefire forks)
- JaCoCo attached as plain -javaagent (not Maven plugin)
- Per-suite parallelism control: concurrent (units), same_thread (DB suites), off (mongo)
- Per-module reports still generated via jacoco:report reading the shared exec

---

## 7. Docker Infrastructure

*What:* docker-compose with Neo4j 5.26 (+ GDS + APOC), MySQL 8.0, MongoDB 7.0.

*Key config:*
- Neo4j: 2-4G heap, 1G pagecache, transaction memory limits disabled (for large resets)
- MySQL: init script auto-creates test schemas/users
- Healthchecks on all three containers; up.sh waits for healthy before proceeding
- Named volumes for persistence; down.sh --wipe for clean slate

---

## 8. Generated Code Filtering

*What:* A prerequisite Cypher labels generated types (Dagger factories, Immutables, _Impl, _Provider, _MembersInjector, .generated.) as :GeneratedType. All queries then exclude with NOT t:GeneratedType — single source of truth.

*Why valuable:* Prevents false positives across all 30+ queries without duplicating substring lists.

---

## 9. Natural Language Query Interface (Claude Command)

*What:* .claude/commands/jqa.md — take a plain-English question, pick the right Cypher query, run it against Neo4j, and return actionable findings.

*Output format per finding:*
- WHAT — one sentence naming the smell
- WHY IT HURTS — concrete risk
- NEXT ACTION — one move this week (with specific class/method name)
- EFFORT — S/M/L
- SKIP IF — falsifier to dismiss

*Final synthesis:* SUGGESTED ORDER of 1-2 highest-leverage actions + first concrete PR (title, scope, what NOT to include).

---

## 10. Key Composite Scores (Novel Combinations)

These are the most interesting derived metrics combining multiple signals:

| Score | Formula | Insight |
|-------|---------|---------|
| Hotspot | wmc * commitCount | Complex + churning = bugs |
| HotspotCovered | sum(crapScore) * commitCount | Above + untested = highest risk |
| ChurnAcceleration | c30 / ((c90-c30)/2) | Getting worse recently |
| BusFactorRisk | commits / authors | Knowledge concentrated |
| BottleneckScore | betweenness * log(types+1) | Architectural chokepoint |
| GodScore | methods + fanIn + fanOut | Too big + too connected |
| HubScore | pageRank * betweenness * methods | Transitively critical |
| SplitScore | types * (1 - cohesion) | Large + incoherent |
| TestDebtScore | totalCrap * (undercovered/measured) | Where to add tests |
| CRAP | cc^2 * (1-cov)^3 + cc | Complexity weighted by coverage gap |

---

## 11. Prerequisite Edge Construction

*What:* Before computing metrics, the pipeline materializes implicit relationships:
- BELONGS_TO — link target/classes dirs to Maven artifacts
- RESOLVES_TO — link stub :Type refs to real :Type nodes
- MODULE_DEPENDS_ON — aggregate class deps to module-level edges
- CLASS_DEPENDS_ON — resolved class-to-class (through RESOLVES_TO)
- HAS_GIT_SOURCE — bridge Java :Type to :File:Git via fqn->path derivation
- METHOD_COUPLING — intra-class method pairs sharing fields or invoking each other

*Why valuable:* These derived edges make downstream queries 10-100x simpler and faster.

---

## 12. Git-to-Code Bridging Strategy

*What:* The git plugin tracks files; the Java plugin tracks types. Bridging:
1. Derive expected source path from type fqn (handle nested classes via $-split)
2. Scope to owning module's path (metric_modulePath)
3. MERGE :HAS_GIT_SOURCE edge

*Trailing windows:* Anchored at repo's latest commit (not wall-clock time) so stale scans give stable, comparable numbers.

---

## Summary: What to Implement in archiTele

Priority order for maximum value:

1. *Graph model* — scan bytecode/POMs/git into a queryable graph
2. *Prerequisite edges* — BELONGS_TO, RESOLVES_TO, MODULE_DEPENDS_ON, HAS_GIT_SOURCE
3. *Core metrics* — WMC, CBO, fan-in/out, coverage transcription, CRAP
4. *Hotspot composites* — wmc*commits, churnAcceleration, busFactorRisk
5. *Query catalogue* — pre-built queries for common questions
6. *GDS algorithms* — PageRank, betweenness, Louvain, SCC for deeper insights
7. *Rule enforcement* — constraint rules that fail CI on architectural violations
8. *Coverage pipeline* — bundled JVM execution for fast feedback
9. *NL interface* — question -> query selection -> actionable findings
10. *Generated code filtering* — single label, one place to maintain
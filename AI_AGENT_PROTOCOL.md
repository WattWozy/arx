# Arx — AI Agent Integration Protocol

## Overview

Arx provides a machine-readable feedback loop for AI coding agents. The agent generates code freely; Arx enforces architectural invariants continuously. Zero violations = safe to commit.

## Protocol

```
AI agent subprocess
       |
       | spawns
       v
arx --watch --format ai-feedback --blueprint arch.blu --src src/main/java
       |
       | stdout: JSON violation arrays (one per file-change event)
       | stderr: human-readable status logs
       v
AI agent reads stdout, self-corrects, re-saves files
       |
       | loop until stdout emits []
       v
git commit
```

## Modes

### 1. Watch mode (continuous)

```bash
arx --watch \
  --blueprint arch.blu \
  --src src/main/java \
  --format ai-feedback
```

- Watches `src/main/java` for file changes via OS events
- On each save, runs incremental analysis
- Emits JSON array to stdout within ~1 second
- Emits status logs to stderr (safe to discard)
- Empty array `[]` = no new violations = safe to commit

### 2. Incremental mode (one-shot pre-commit check)

```bash
# From git diff
git diff --name-only HEAD | \
  arx --incremental \
    --blueprint arch.blu \
    --repo . \
    --format ai-feedback

# Explicit file list
arx --incremental \
  --blueprint arch.blu \
  --repo . \
  --format ai-feedback \
  --changed src/main/java/com/example/Foo.java src/main/java/com/example/Bar.java
```

- Baseline = git HEAD (one commit, fast)
- Re-resolves only the changed files from working tree
- Emits violations introduced by the change
- Exit code 0 = clean, 1 = new violations

### 3. Normal mode (full report)

```bash
arx --repo . --blueprint arch.blu --format ai-feedback
```

- Full git history analysis
- Violations from latest snapshot, no file/line info
- Use for dashboards and PRs

## Output format

Each call emits a JSON array. Empty array = clean.

```json
[
  {
    "sourceModule": "domain",
    "targetModule": "adapter",
    "sourceFile": "/abs/path/to/Foo.java",
    "lineNumber": 7,
    "importStatement": "dev.archtelemetry.adapter.cli.Main",
    "violatedRule": "allow domain -> adapter (missing)",
    "suggestedFix": "Dependency inversion required: 'domain' (layer 0, inner) must not import 'adapter' (layer 2, outer). Declare a port interface in 'domain' and implement it in 'adapter'. The inner layer depends on the abstraction, not the outer implementation."
  }
]
```

Fields:
| Field | Description |
|---|---|
| `sourceModule` | Module containing the offending import |
| `targetModule` | Module being illegally imported |
| `sourceFile` | Absolute path (null in normal mode) |
| `lineNumber` | Line of the import statement (null in normal mode) |
| `importStatement` | Full import FQN (null in normal mode) |
| `violatedRule` | The missing blueprint allow rule |
| `suggestedFix` | Deterministic fix hint based on layer topology |

## Fix hints

Fix hints are deterministic — derived from blueprint layer annotations, not AI-generated.

| Scenario | Hint |
|---|---|
| Inner layer (low N) imports outer layer (high N) | Dependency inversion: declare port in inner, implement in outer |
| Same-layer or outer→inner with missing allow | Add `allow source -> target` to blueprint, or remove the import |

## Integration examples

### Claude Code (subprocess)

```python
import subprocess, json, sys

proc = subprocess.Popen(
    ["arx", "--watch", "--blueprint", "arch.blu",
     "--src", "src/main/java", "--format", "ai-feedback"],
    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True
)

for line in proc.stdout:
    line = line.strip()
    if not line:
        continue
    violations = json.loads(line)
    if violations:
        for v in violations:
            # Feed back to agent: v["sourceFile"], v["lineNumber"], v["suggestedFix"]
            print(f"Fix {v['sourceFile']}:{v['lineNumber']} — {v['suggestedFix']}")
```

### Cursor / generic subprocess

Any tool that can read stdout from a subprocess can consume the JSON stream. Key rules:
1. Spawn `arx --watch --format ai-feedback` as a subprocess
2. Read stdout line-by-line (each change event produces one JSON line)
3. Parse as JSON array
4. If array is non-empty, surface violations to the agent
5. Agent self-corrects and saves; next save triggers next event
6. When array is `[]`, commit is safe

### Pre-commit hook

```bash
#!/bin/sh
# .git/hooks/pre-commit
CHANGED=$(git diff --cached --name-only --diff-filter=ACM | grep '\.java$')
if [ -z "$CHANGED" ]; then exit 0; fi

RESULT=$(echo "$CHANGED" | arx \
  --incremental --blueprint arch.blu --repo . --format ai-feedback)

if [ "$RESULT" != "[]" ]; then
  echo "Architectural violations detected:"
  echo "$RESULT" | jq -r '.[] | "  \(.sourceFile):\(.lineNumber) — \(.suggestedFix)"'
  exit 1
fi
```

## Blueprint quick-reference

```
# arch.blu
module domain      dev.example.domain.**      layer=0
module application dev.example.application.** layer=1
module adapter     dev.example.adapter.**     layer=2

allow application -> domain
allow adapter     -> application
allow adapter     -> domain
```

Layer 0 = innermost (never imports outer layers). Layer N+1 may import layer N.
Any import not covered by an `allow` rule is a violation.

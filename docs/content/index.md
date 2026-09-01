---
title: codeps
description: Code package dependency analyzer
pagination:
  enabled: false
---

# codeps

codeps is a **code dependency analyzer** for JVM projects (Java and Scala).
It works in two steps:

1. **`codeps export`** parses output your compiler already produced —
   [SemanticDB](/howtos/semdb.html) (`.semanticdb` files from Scala) or
   [jdeps](/howtos/jdeps.html) (the JDK's own analyzer) — and spits out a
   dependency graph as JSON: the [standard JSON export format](/reference/json-input.html).
2. **`codeps report-packages`** or **`codeps report-files`** takes that JSON (a file, or stdin
   via `-`) and spits out the [metrics report](/reference/report.html): a plain-text table by
   default, or JSON with `--format json` — handy for agents, other tools and CI.

It works on two levels:

- **`report-packages`** — the whole package graph: the level you use when
  **splitting modules**. Find the cycles that keep modules welded together,
  with optional budgeted cut analysis, plus per-package exposed surface and orphans.
- **`report-files`** — the file graph of the packages selected with `--include`: the
  granularity that matters when you **optimize compile times** (zinc and other
  incremental compilers recompile by file).

Cycles are bad in both: at package level they make modules inseparable; at file
level they degrade incremental compilation.

## Example

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb -o deps.json
codeps report-packages --input deps.json
```

Abbreviated table output of `codeps report-packages` on the repo's
[cyclic test fixture](https://github.com/sake92/codeps/blob/main/testFixtures/cyclic.json)
(`module1` ↔ `module2`):

```text
scope: packages    generatedAt: 2026-08-28T14:20:56Z

Summary
  nodes: 4    edges: 4    nodesInCycles: 2    orphans: 0    criticalPathLength: 2

Cycles (size desc, extFanIn desc)
id                               size  extFanIn  greedyCutEstimate  status
scc:com.example.modules.module1  2     1         —                  notRequested

  Cycle scc:com.example.modules.module1
    cut analysis: notRequested (pass --analyze-cuts)

Change propagators (score = (fanIn/avgFanIn + fanOut/avgFanOut)/2; score > 1, top 10)
node                         fanIn  fanOut  score
com.example.modules.module1  2      1       1.50
com.example.modules.module2  1      2       1.50
Surface (dependentsPerPublicPort asc; — = no fan-in)
node                         fanIn  fanOut  ports  mutPorts  exposure  dependentsPerPublicPort
com.example.modules.module2  1      2       4      0         4         0.25
org.thirdparty               1      0       4      0         4         0.25
com.example.modules.module1  2      1       4      0         4         0.50
com.example.app              0      1       4      0         4         —
Orphans
  (none)
```

## Features

- **Two input formats** — [SemanticDB](/howtos/semdb.html) (detailed, from Scala compiler output) and [jdeps](/howtos/jdeps.html) (JDK-only, no extra tooling)
- **Cycles with explicit cut analysis** — every multi-member strongly connected component is reported as a closed cycle path with `extFanIn`; default reports remain fast with `cutAnalysis.status=notRequested`, while `--analyze-cuts` adds budgeted greedy estimates and up to 3 complete ways to break the cycle
- **Change propagators** — the nodes whose changes propagate most: a normalized `(fanIn/avgFanIn + fanOut/avgFanOut)/2` score, top 10
- **Encapsulation and structural-use metrics** — per-node weighted `ports` / `mutPorts`, raw declaration visibility counters, `encapsulationRatio`, `publicMutableRatio`, and the `dependentsPerPublicPort` structural proxy (sealed/given/var rules resolved by the SemanticDB exporter)
- **Orphans** — dead-code-removal candidates
- **Filtering** — keep only nodes matching `--include` patterns, drop noise with `--exclude` (e.g. `java.*`, `scala.*`); skip tests with `--skip-tests`
- **Collapsing** — merge whole subtrees with `--collapse` rules (`com.example.**`, `org.lib.*`)
- **Two output formats** — `table` (default, plain aligned text) and `json` (machine-readable)

No build-system integration needed: your local build tools already produce the inputs,
codeps just reads them.

## Site Map
- [Tutorials](/tutorials) — step-by-step guides to get things working
  {% for tut in site.data.project.tutorials %}- [{{ tut.label }}]({{ tut.url}})
  {% endfor %}
- [How Tos](/howtos) — goal-oriented recipes for specific tasks
  {% for tut in site.data.project.howtos %}- [{{ tut.label }}]({{ tut.url}})
  {% endfor %}
- [Reference](/reference) — complete descriptions of commands, config, and internals
  {% for tut in site.data.project.references %}- [{{ tut.label }}]({{ tut.url}})
  {% endfor %}

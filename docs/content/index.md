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
2. **`codeps report`** takes that JSON (a file, or stdin via `-`) and spits out the
   [metrics report](/reference/report.html): a plain-text table by default, or JSON
   with `--format json` — handy for agents, other tools and CI.

It works on two levels:

- **`--scope packages`** — the whole package graph: the level you use when
  **splitting modules**. Find the cycles that keep modules welded together,
  with simulated cut candidates, plus per-package exposed surface and orphans.
- **`--scope files`** — the file graph of the packages selected with `-i`: the
  granularity that matters when you **optimize compile times** (zinc and other
  incremental compilers recompile by file).

Cycles are bad in both: at package level they make modules inseparable; at file
level they degrade incremental compilation.

## Example

```shell
codeps export --from semanticdb classes/META-INF/semanticdb -o deps.json
codeps report --scope packages deps.json
```

Real output of `codeps report --scope packages` on the repo's
[cyclic test fixture](https://github.com/sake92/codeps/blob/main/testFixtures/cyclic.json)
(`module1` ↔ `module2`):

```text
scope: packages    generatedAt: 2026-08-27T18:36:47Z

Summary
  nodes: 4    edges: 4    nodesInCycles: 2    orphans: 0    criticalPathLength: 2

Cycles (size desc, extFanIn desc)
id                               size  extFanIn  minCutsEstimate  cut candidates
scc:com.example.modules.module1  2     1         1                com.example.modules.module1 -> com.example.modules.module2 (w=1), com.example.modules.module2 -> com.example.modules.module1 (w=1)
Surface (utilization asc; — = no fan-in)
node                         fanIn  fanOut  ports  mutPorts  exposure  utilization
com.example.modules.module2  1      2       4      0         4         0.25
org.thirdparty               1      0       4      0         4         0.25
com.example.modules.module1  2      1       4      0         4         0.50
com.example.app              0      1       4      0         4         —
Orphans
  (none)
```

## Features

- **Two input formats** — [SemanticDB](/howtos/semdb.html) (detailed, from Scala compiler output) and [jdeps](/howtos/jdeps.html) (JDK-only, no extra tooling)
- **Cycles with simulated cuts** — every multi-member strongly connected component is reported as a closed cycle path with `extFanIn`, the internal edges whose removal resolves it, and a greedy `minCutsEstimate`
- **Exposed-surface metrics** — per-node `ports` / `mutPorts` / `exposure` / `utilization` (sealed/given/var rules resolved by the SemanticDB exporter)
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

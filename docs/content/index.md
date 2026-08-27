---
title: codeps
description: Code package dependency analyzer
pagination:
  enabled: false
---

# codeps

codeps is a **code dependency analyzer** for JVM projects (Java and Scala).
It works in two steps: `codeps export` parses existing compiler output —
[SemanticDB](https://scalameta.org/docs/semanticdb/specification.html) or `jdeps` —
into a [common JSON graph format](/reference/json-input.html); then `codeps report`
emits a flat [metrics report](/reference/report.html) — cycles with simulated
cut candidates, per-node exposed-surface metrics and orphans —
over the **packages** of the whole graph, or over the **files** of the packages you select.

No build-system integration needed: your local build tools already produce the inputs —
`.semanticdb` files from a Scala compiler with SemanticDB enabled, and `.class` files for the JDK's `jdeps` —
codeps just reads them.

## Features

- **Two input formats:**
  - [SemanticDB](/howtos/semdb.html) — detailed, from Scala compiler output (`.semanticdb` files): package/file/type/member nodes
  - [jdeps](/howtos/jdeps.html) — from the JDK's own `jdeps -verbose:class` output, no extra tooling needed
- **Two scopes** — `--scope packages` for the whole package graph, `--scope files` for the file graph of the packages you select with `-i`
- **Cycles with simulated cuts** — every multi-member strongly connected component is reported as a closed cycle path with `ext_fan_in`, the internal edges whose removal resolves it (top 6 by weight), and a greedy `min_cuts_estimate`
- **Exposed-surface metrics** — per-node `ports` / `mut_ports` / `exposure` / `utilization` (sealed/given/var rules resolved by the SemanticDB exporter)
- **Orphans** — dead-code-removal candidates
- **Filtering** — keep only nodes matching `--include` patterns, drop noise with `--exclude` (e.g. `java.*`, `scala.*`)
- **Collapsing** — merge whole subtrees with `--collapse` rules (`com.example.**`, `org.lib.*`)
- **Two output formats** — `table` (default, plain aligned text) and `json` (machine-readable)

## Quick example

```shell
codeps export --from semanticdb classes/META-INF/semanticdb | codeps report --scope packages -
```

```json
{
  "scope": "packages",
  "generated_at": "2026-08-27T10:00:00Z",
  "summary": {
    "nodes": 5,
    "edges": 4,
    "nodes_in_cycles": 2,
    "orphans": 1,
    "critical_path_length": 2
  },
  "cycles": [
    {
      "id": "scc:com.example.modules.module1",
      "members": ["com.example.modules.module1", "com.example.modules.module2", "com.example.modules.module1"],
      "size": 2,
      "ext_fan_in": 1,
      "min_cuts_estimate": 1,
      "cut_candidates": [
        { "edge": ["com.example.modules.module2", "com.example.modules.module1"], "weight": 2 }
      ]
    }
  ],
  "surface": [
    { "node": "com.example.app", "fan_in": 0, "fan_out": 1, "ports": 3, "mut_ports": 0, "exposure": 3, "utilization": null }
  ],
  "orphans": ["org.thirdparty"]
}
```

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

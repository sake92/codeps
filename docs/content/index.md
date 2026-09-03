---
title: codeps
description: Code deps health tracker
pagination:
  enabled: false
---

# codeps

codeps is a **code deps health tracker** for JVM projects (Java and Scala).
Start by recording one compact overall dependency-health
snapshot per meaningful change, then use detailed reports when you want to
refactor a cycle, reduce coupling, or improve encapsulation.

The normal workflow is:

1. **`codeps export`** parses output your compiler already produced —
   [SemanticDB](/howtos/semdb.html) (`.semanticdb` files from Scala) or
   [jdeps](/howtos/jdeps.html) (the JDK's own analyzer) — and spits out a
   dependency graph as JSON: the [codeps export format](/reference/json-input.html).
2. **`codeps health-snapshot`** derives an explainable 1–10 health score plus
   compact `structure`, `cycles`, `surface`, and `findings` sections and appends a point to
   `.codeps/health.ndjson` only when health changes significantly (or the
   periodic checkpoint is due).
3. **`codeps report-packages`** or **`codeps report-files`** takes that JSON (a file, or stdin
   via `-`) and provides the detailed [metrics report](/reference/report.html): a table with
   `--format table`, GitHub-Flavored Markdown with `--format markdown`, or JSON with `--format json` —
   handy for agents, other tools and CI.

For CI, keep artifact paths explicit:

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb --out .codeps/temp/export.json
codeps health-snapshot --input .codeps/temp/export.json --history .codeps/health.ndjson
```

The history file is ordinary NDJSON and committing it to the measured
repository is recommended when you want trends in Git history or on GitHub
Pages. The CLI does not commit it for you.

It works on two levels:

- **`report-packages`** — the whole package graph: the level you use when
  **splitting modules**. Find the cycles that keep modules welded together,
  with optional budgeted cut analysis, plus per-package exposed surface and orphans.
- **`report-files`** — the file graph of the packages selected with `--include`: the
  granularity that matters when you **optimize compile times** (zinc and other
  incremental compilers recompile by file).

Cycles are bad in both: at package level they make modules inseparable; at file
level they degrade incremental compilation.

## Example health history

`health-snapshot` stores compact NDJSON records. Request Markdown when you want
a review-friendly summary:

```shell
codeps health-snapshot --format markdown
```

```markdown
# Overall dependency health

**Health:** 4/10 (unhealthy)<br>
**Commit:** `abc123`<br>
**Recorded:** 2026-09-02 12:00 UTC

| Section | Metric | Current | Change |
|---|---|---:|---:|
| Structure | Nodes | 351 | +2.0% |
| Structure | Edges | 2,408 | +1.4% |
| Cycles | Nodes in cycles | 336 | +12.0% |
| Cycles | Largest SCC | 336 | +12.0% |
| Surface | Public surface | 12,345 | +0.8% |
| Surface | Encapsulation ratio | 18.1% | −0.4% |
| Findings | Critical | 1 | +1 |
| Findings | High | 8 | −2 |

_Snapshot recorded: cycle size changed by more than the 1% threshold._
```

The underlying `.codeps/health.ndjson` is JSON on every line, which makes it
particularly useful in CI: jobs can inspect the latest status, compare metrics,
or feed the history into a dashboard without parsing human-oriented report
text. The Markdown view is only the convenient presentation layer.

For the full dependency graph, cycle members, cut candidates, propagators, and
per-node surface details, continue to the [metrics report reference](/reference/report.html)
and the [CLI reference](/reference/cli.html).

## Features

- **Two input formats** — [SemanticDB](/howtos/semdb.html) (detailed, from Scala compiler output) and [jdeps](/howtos/jdeps.html) (JDK-only, no extra tooling)
- **Cycles with explicit cut analysis** — every multi-member strongly connected component is reported as a closed cycle path with `extFanIn`; reports without `--analyze-cuts` have `cutAnalysis.status=notRequested`, while the flag adds budgeted greedy estimates and up to 3 complete ways to break the cycle
- **Change propagators** — the nodes whose changes propagate most: a normalized `(fanIn/avgFanIn + fanOut/avgFanOut)/2` score, top 10
- **Encapsulation and structural-use metrics** — per-node weighted `ports` / `mutPorts`, raw declaration visibility counters, `encapsulationRatio`, `publicMutableRatio`, and the `dependentsPerPublicPort` structural proxy (sealed/given/var rules resolved by the SemanticDB exporter)
- **Compact surface tables** — `node,in,out,ports,mut,encap%,use` headings, with repeatable `--columns visibility`, `--columns mutability`, `--columns coupling`, or `--columns all` groups for wider reviews; JSON field names remain camelCase
- **Orphans** — dead-code-removal candidates
- **Filtering** — keep only nodes matching `--include` patterns, drop noise with `--exclude` (e.g. `java.*`, `scala.*`); skip tests with `--skip-tests`
- **Collapsing** — merge whole subtrees with `--collapse` rules (`com.example.**`, `org.lib.*`)
- **Three output formats** — `table` (with optional ANSI styling), `markdown` (deterministic
  GFM for reviews), and `json` (machine-readable)
- **Report controls** — table/Markdown views show the top 10 rows per section; `--all`
  expands them, `--color auto|always|never` controls ANSI table styling, and `--analyze-cuts`
  enables bounded SCC cut analysis. JSON is schema v2; its `findings` array caps at 10,000 rows
  and reports omissions in `truncation`.

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

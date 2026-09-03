---
layout: tutorial.html
title: Find package pain points
description: Turn a health signal into a focused package investigation
---

# Find package pain points

Start broad. A package report tells you which dependency knot, change hub, or
over-exposed package deserves attention—not how to rewrite the whole system.

## Try the bundled example (optional)

If you want to explore the report before exporting your own project, clone the
[codeps repository](https://github.com/sake92/codeps) and run the CLI against
its small checked-in graph:

```shell
codeps report-packages --input testFixtures/cyclic.json
```

The rest of this tutorial works exactly the same way with your own
`.codeps/temp/export.json`.

## 1. Scope the report to your code

Use the graph created by `export`. An include pattern covers a package and its
children; excludes win. This keeps platform and third-party noise out of the
ranking.

```shell
codeps report-packages --include com.example -e java -e scala
```

For review output, write deterministic Markdown instead of copying terminal text:

```shell
codeps report-packages --include com.example --format markdown -o codeps-report.md
```

## 2. Choose one finding

Read the sections in this order:

1. **Cycles** — a package SCC blocks clean module boundaries. Start with the
   largest SCC; `extFanIn` shows how much code feeds into it.
2. **Change propagators** — high `fanIn` and `fanOut` identify packages whose
   changes spread widely. They are useful candidates for a narrower API or a
   split.
3. **Surface risks** — low `use` (`dependentsPerPublicPort`) can indicate a
   package exposing more API than its consumers need. Check `ports`, `mut`, and
   `encap%` together; no single score is a verdict.
4. **Orphans** — isolated nodes are dead-code-removal candidates. Confirm they
   are not entry points or reflection targets before deleting anything.

## 3. Investigate the package, not every metric

Ask codeps for a cached report detail using an id printed in the report:

```shell
codeps inspect-cycle --id scc:com.example.orders
codeps inspect-node --id com.example.orders
```

If a cycle is the problem, ask for bounded cut analysis only then:

```shell
codeps report-packages --include com.example --analyze-cuts
codeps inspect-cycle --id scc:com.example.orders
```

The suggested cuts are investigation leads. Validate the dependency direction
and domain ownership before changing an API. For field meanings and cut-analysis
guarantees, see [Metrics report](/reference/report.html).

## 4. Keep large graphs readable

Collapse an uninteresting subtree to one node, then rerun the same question:

```shell
codeps report-packages --include com.example -c com.example.generated.**
```

When a package is clearly the problem, move to
[file-level drill-down](/tutorials/file-triage.html) rather than trying to infer
the responsible files from package totals.

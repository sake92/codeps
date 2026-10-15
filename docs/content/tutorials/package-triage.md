---
layout: tutorial.html
title: Find package pain points
description: Turn a health signal into a focused package investigation
---

# Find package pain points

Start broad. A package report tells you which dependency knot, change hub, or
over-exposed package deserves attention—not how to rewrite the whole system.

## Try the bundled example (optional)

If you want to explore a report before configuring your own project, clone the
[codeps repository](https://github.com/sake92/codeps) or the
[example projects](https://github.com/sake92/codeps-examples), then point a project at its small
checked-in graph:

```yaml
projects:
  fixture:
    root: .
    source: export
    inputs: [testFixtures/cyclic.json]
```

```shell
codeps status --project fixture
```

The rest of this tutorial works exactly the same way with your own SemanticDB or jdeps
project.

## 1. Scope the report to your code

An `include` pattern covers a package and its children; `exclude` wins over `include`. This
keeps platform and third-party noise out of the ranking. Add both to the project in
`.codeps/config.yaml`:

```yaml
projects:
  app:
    root: .
    source: semanticdb
    inputs: [classes/META-INF/semanticdb]
    include: [com.example]
    exclude: [com.example.internal]
```

```shell
codeps status --project app
```

## 2. Choose one finding

Read a bounded selection from the cached report:

```shell
codeps findings --project app --limit 10 --format json
```

Check `total`, `omitted`, and `sourceFindingsOmitted`. The selection is exhaustive
only when both omission counts are zero. Narrow it with `--kind cycle` or
`--severity high` if needed. Prioritize the evidence in this order:

1. **Cycles** — a package SCC blocks clean module boundaries. Start with the
   largest SCC; `extFanIn` shows how much code feeds into it.
2. **Change propagators** — high `fanIn` and `fanOut` identify packages whose
   changes spread widely. They are useful candidates for a narrower API or a
   split.
3. **Surface risks** — low `dependentsPerPublicPort` can indicate a
   package exposing more API than its consumers need. Inspect `ports`, `mutPorts`,
   and declaration-surface metrics together; no single score is a verdict.

Orphans are outside the findings index. If investigating removal candidates, read
the cached report's `packages.orphans` field, confirm each source still exists,
and check entry points and reflection before proposing deletion.

## 3. Investigate the package, not every metric

Ask codeps for cached detail using a finding's `subject` value as the id. For example:

```shell
codeps inspect-cycle --project app --id scc:com.example.orders
codeps inspect-node --project app --id com.example.orders
```

Both commands read the most recent `.codeps/out/app/report.json`, so run `codeps status --project app`
first. Cut analysis is not currently exposed by the CLI: `cutAnalysis.status` in the report is
always `notRequested`, so a suggested cut list will not be populated. Use `extFanIn`, `members`,
and `witnessCycle` to reason about the cycle by hand.

For field meanings, see [Metrics report](/reference/report.html).

The information a cycle or propagator finding surfaces is an investigation lead. Validate the
dependency direction and domain ownership before changing an API.

## 4. Keep large graphs readable

Collapse an uninteresting subtree to one node with the `collapse` config field, then rerun
`codeps status`:

```yaml
    collapse: [com.example.generated.*]
```

When a package is clearly the problem, move to
[file-level drill-down](/tutorials/file-triage.html) rather than trying to infer
the responsible files from package totals.

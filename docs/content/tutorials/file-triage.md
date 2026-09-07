---
layout: tutorial.html
title: Drill into files
description: Find the files behind a package-level dependency problem
---

# Drill into files

Use file analysis after package triage has named a package worth changing. This
is the level that helps with incremental-compilation pain: a file cycle or hub
can force changes through more of the build than intended.

## 1. Run status

A file-level report needs SemanticDB input (jdeps carries no file information). One project
emits both package and file metrics from the same inputs:

```yaml
projects:
  app:
    root: .
    source: semanticdb
    inputs: [classes/META-INF/semanticdb]
    include: [com.example.orders]
```

```shell
java -jar codeps.jar status --project app
```

The report uses the same sections as the package report, but ids are source-file
paths. Start with cycles, then change propagators, then surface risks.

## 2. Inspect the concrete file

```shell
codeps inspect-node --project app --scope files --id src/com/example/orders/OrderService.scala
```

For a file-level cycle, inspect the SCC directly:

```shell
codeps inspect-cycle --project app --scope files --id scc:src/com/example/orders/OrderService.scala
```

Cut analysis is not currently exposed by the CLI, so `cutAnalysis.status` is always
`notRequested`; use `members` and `witnessCycle` to follow the actual dependency path in the
source. A cycle finding is not an instruction to delete an import; common fixes are moving an
abstraction to the owning package, inverting a dependency, or narrowing a file's
public surface.

## 3. Re-measure the change

After the refactor, rebuild compiler output (so the SemanticDB files are current) and run
`codeps status` again for the file-scoped project. Compare the targeted cycle, fan-in/out, and
exposed surface — not just the overall health status.

```shell
java -jar codeps.jar status --project app
```

jdeps-sourced projects have no file graph, so use [package triage](/tutorials/package-triage.html)
instead when your project's `source` is `jdeps`.

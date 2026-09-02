---
layout: tutorial.html
title: Drill into files
description: Find the files behind a package-level dependency problem
---

# Drill into files

Use file analysis after package triage has named a package worth changing. This
is the level that helps with incremental-compilation pain: a file cycle or hub
can force changes through more of the build than intended.

## 1. Select exactly one package area

`report-files` requires `--include`; it analyzes the files belonging to the
selected package and its children.

```shell
codeps report-files --include com.example.orders
```

The output uses the same sections as the package report, but ids are source-file
paths. Start with cycles, then change propagators, then surface risks.

## 2. Inspect the concrete file

Save or use the cached report, then inspect an id from the output:

```shell
codeps inspect-node --scope files --id src/com/example/orders/OrderService.scala
```

For a file-level cycle, enable bounded cut analysis and inspect the SCC:

```shell
codeps report-files --include com.example.orders --analyze-cuts
codeps inspect-cycle --scope files --id scc:src/com/example/orders/OrderService.scala
```

Use the witness cycle to follow the actual dependency path in the source. A cut
candidate is not an instruction to delete an import; common fixes are moving an
abstraction to the owning package, inverting a dependency, or narrowing a file's
public surface.

## 3. Re-measure the change

After the refactor, rebuild compiler output and run the same scoped report.
Compare the targeted cycle, fan-in/out, and exposed surface—not just the overall
health status.

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb
codeps report-files --include com.example.orders
codeps health-snapshot --format markdown
```

File reports need SemanticDB input. jdeps exports package information only, so
use [package triage](/tutorials/package-triage.html) for a jdeps project.

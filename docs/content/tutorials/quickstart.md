---
layout: tutorial.html
title: First health snapshot
description: Record your first codeps health baseline
---

# First health snapshot

Use this once your compiler output is available. It produces a dependency graph,
records the first baseline, and shows the next thing worth investigating. The
examples use SemanticDB; the [Scala setup guide](/howtos/semdb.html) and
[jdeps setup guide](/howtos/jdeps.html) explain where that input comes from.

## Prerequisites

- JDK 11+

## Get the CLI

```shell
curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar
```

(`codeps` below is shorthand for `java -jar codeps.jar`.)

## Export, then record health

codeps reads what your compiler already produced — no build integration required.

**Scala** — compile with SemanticDB enabled (here with scala-cli):

```shell
scala-cli compile --server=false --semanticdb -d classes src/
# -> classes/META-INF/semanticdb/**/*.semanticdb
```

Then export the graph and record an overall baseline:

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb --out .codeps/temp/export.json
codeps health-snapshot --input .codeps/temp/export.json --history .codeps/health.ndjson
```

If your build tool already enables SemanticDB (sbt/Maven with the `semanticdb` compiler plugin),
the `.semanticdb` files are already on disk — just point `export --input` at the directory that contains them.

For Java or mixed projects, produce jdeps input instead:

```shell
jdeps -verbose:class -filter:none -cp classes classes > jdeps.txt
codeps export --from jdeps --input jdeps.txt --out .codeps/temp/export.json
```

See [Scala/SemanticDB projects](/howtos/semdb.html) and [Java/JVM projects with jdeps](/howtos/jdeps.html) for details.

The commands above write the graph to `.codeps/temp/export.json` and append a compact
record to `.codeps/health.ndjson`. Commit that history if you want to review
health changes in Git history.

## Read the result

Print a review-friendly snapshot:

```shell
codeps health-snapshot --input .codeps/temp/export.json --history .codeps/health.ndjson --format markdown
```

The snapshot is deliberately small: overall structure, cycles, exposed surface,
and finding counts. Treat it as a signal, not a refactoring plan. When it shows
cycles or high findings, continue with [package triage](/tutorials/package-triage.html).

To see the full package report now:

```shell
codeps report-packages --input .codeps/temp/export.json
```

## What's next?

- [Track health in CI](/tutorials/health-in-ci.html)
- [Find package pain points](/tutorials/package-triage.html)
- [CLI reference](/reference/cli.html) for every option

---
layout: tutorial.html
title: Quickstart
description: codeps Quickstart
---

# Quickstart

## Prerequisites

- JDK 11+

## Get the CLI

```shell
curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar
```

(`codeps` below is shorthand for `java -jar codeps.jar`.)

## Export the graph

codeps reads what your compiler already produced — no build integration required.

**Scala** — compile with SemanticDB enabled (here with scala-cli):

```shell
scala-cli compile --server=false --semanticdb -d classes src/
# -> classes/META-INF/semanticdb/**/*.semanticdb
```

Then export the graph to the codeps export format:

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb
```

If your build tool already enables SemanticDB (sbt/Maven with the `semanticdb` compiler plugin),
the `.semanticdb` files are already on disk — just point `export --input` at the directory that contains them.

**Java or mixed** — use the JDK's own analyzer:

```shell
jdeps -verbose:class -filter:none -cp classes classes > jdeps.txt
codeps export --from jdeps --input jdeps.txt
```

See [Scala/SemanticDB projects](/howtos/semdb.html) and [Java/JVM projects with jdeps](/howtos/jdeps.html) for details.

## Analyze

Emit the metrics report over the whole package graph:

```shell
codeps report-packages --input deps.json
```

or pipe export straight into `report-packages`:

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb -o - | codeps report-packages --input -
```

For a file-level view of one package, use `report-files` with an include pattern:

```shell
codeps report-files --include com.example.modules.module1 --input deps.json
```

The report contains SCC facts (and optional budgeted cut analysis), exposed-surface metrics
(`ports`/`mutPorts`/`exposure`/`dependentsPerPublicPort` plus declaration visibility
and encapsulation ratios) and orphans — see the
[Metrics report](/reference/report.html) for the full field reference. It renders
as a table by default. `--format markdown` emits deterministic GitHub-Flavored Markdown;
`--format json` emits the schema-v2 report with canonical ids and cut evidence. Table and
Markdown sections show at most 10 rows by default; add `--all` for every human-view row. The
the `findings` array is capped at 10,000 rows in JSON, with omitted counts recorded in
`truncation`.
Use `--color auto|always|never` for table ANSI styling and add `--analyze-cuts` (with optional
`--cut-time-limit` / `--cut-candidate-limit`) when you want bounded cut analysis.

## What's next?

- Filter and collapse packages: [CLI reference](/reference/cli.html)
- Understand the metrics: [Metrics report](/reference/report.html)
- The export JSON schema: [Codeps export format](/reference/json-input.html)

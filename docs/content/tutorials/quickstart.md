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

Then export the graph to the standard JSON export format:

```shell
codeps export --from semanticdb classes/META-INF/semanticdb -o deps.json
```

If your build tool already enables SemanticDB (sbt/Maven with the `semanticdb` compiler plugin),
the `.semanticdb` files are already on disk — just point `export` at the directory that contains them.

**Java or mixed** — use the JDK's own analyzer:

```shell
jdeps -verbose:class -filter:none -cp classes classes > jdeps.txt
codeps export --from jdeps jdeps.txt -o deps.json
```

See [Scala/SemanticDB projects](/howtos/semdb.html) and [Java/JVM projects with jdeps](/howtos/jdeps.html) for details.

## Analyze

Emit the metrics report over the whole package graph:

```shell
codeps report --scope packages deps.json
```

or pipe export straight into report:

```shell
codeps export --from semanticdb classes/META-INF/semanticdb | codeps report --scope packages -
```

For a file-level view of one package, use `--scope files` with an include pattern:

```shell
codeps report --scope files -i com.example.modules.module1 deps.json
```

The report contains cycles with simulated cut candidates, exposed-surface metrics
(`ports`/`mutPorts`/`exposure`/`utilization`) and orphans — see the
[Metrics report](/reference/report.html) for the full field reference. It renders
as plain aligned text by default; `--format json` emits machine-readable JSON.

## What's next?

- Filter and collapse packages: [CLI reference](/reference/cli.html)
- Understand the metrics: [Metrics report](/reference/report.html)
- The export JSON schema: [Standard JSON export format](/reference/json-input.html)

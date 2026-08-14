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

Then export the graph to the common JSON format:

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

Render the graph at package granularity as Mermaid:

```shell
codeps analyze -g package -f mermaid deps.json
```

or pipe export straight into analyze:

```shell
codeps export --from semanticdb classes/META-INF/semanticdb | codeps analyze -g package -f mermaid -
```

For deeper views use `-g type` or `-g file` (and `-g member` on Scala data).
DOT is equally easy — just swap `-f mermaid` for `-f dot`.

## What's next?

- Filter and collapse packages: [CLI reference](/reference/cli.html)
- Explore a graph interactively: drop `deps.json` onto the [demo](/demo/cytoscape-graph.html)
- Understand the internals: [Architecture](/reference/architecture.html)

---
layout: howto.html
title: Export & analyze Java/JVM projects with jdeps
description: Analyzing Java/JVM dependencies with jdeps
---

# Export & analyze Java/JVM projects with jdeps

[jdeps](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jdeps.html) is the JDK's own dependency analyzer.
It ships with every JDK, so this workflow needs **no extra tooling** — great for Java projects
or when you can't (or don't want to) enable SemanticDB.
Your build already produced the `.class` files; the only extra step is piping `jdeps` output to a text file.

jdeps data is class-level: it has no file information, so `export` collapses it to package
nodes and edges, and `codeps report-files` errors on it (`no file nodes found in
the input`) — use `report-packages` instead.
It also carries no access information, so all nodes have `ports`/`mutPorts` 0 and
`dependentsPerPublicPort` `null` — a known gap, not silently meaningful.

## Generating the input

```shell
jdeps -verbose:class -filter:none -cp classes classes > jdeps.txt
```

- `-verbose:class` is required — codeps reads the indented per-class detail lines
- `-filter:none` keeps all edges (JDK-internal noise like `java.*` can be filtered later with `-e`)

## Exporting the graph

`codeps export` parses the text file and emits the
[codeps export format](/reference/json-input.html) (a package graph and an empty file graph):

```shell
codeps export --from jdeps --input jdeps.txt
```

Only the project's own classes appear — the exporter drops edges to external classes
that are not themselves defined in the input.

## Analyzing

`codeps report-packages` reads the JSON graph and emits the flat metrics report over the
package graph:

```shell
codeps report-packages --input deps.json
```

or in one pipe:

```shell
codeps export --from jdeps --input jdeps.txt -o - | codeps report-packages --input -
```

Cycles can include optional budgeted cut analysis, and the surface lists fanIn/fanOut
and orphans — see the [Metrics report](/reference/report.html). Use `--format table`,
`--format markdown` for deterministic GitHub-Flavored Markdown, or `--format json`
for the schema-v2 report. Table and Markdown sections show at most 10 rows; pass
`--all` for every human-view row. `--analyze-cuts` enables bounded cut investigation, with
`--cut-time-limit` and `--cut-candidate-limit` available as per-SCC controls. jdeps exports package
nodes and an empty file graph.

## Filtering JDK noise

The raw `jdeps -verbose:class` output includes edges to `java.*`, `scala.*` and other
platform classes. Exclude them to keep the graph focused on your code:

```shell
codeps report-packages --include com.example -e java.** -e scala.** --input deps.json
```

> Note: excludes are package patterns matched against each node's root package —
> `java.**` excludes everything under `java` (see [Include / exclude patterns](/reference/cli.html#include--exclude-patterns)).

## Collapsing

Just like with SemanticDB input, `--collapse`/`-c` rules apply:

```shell
codeps report-packages --include com.example -c com.example.modules.** --input deps.json
```

See [CLI reference](/reference/cli.html) for the full option list.

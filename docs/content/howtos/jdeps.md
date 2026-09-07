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

jdeps data is class-level: it has no file information, so codeps emits only package metrics
and omits the Files tab. It also carries no access information, so all nodes have `ports`/`mutPorts` 0 and
`dependentsPerPublicPort` `null` — a known gap, not silently meaningful.

## Generating the input

```shell
jdeps -verbose:class -filter:none -cp classes classes > jdeps.txt
```

- `-verbose:class` is required — codeps reads the indented per-class detail lines
- `-filter:none` keeps all edges (JDK-internal noise like `java.*` can be filtered later with `exclude`)

## Configuring and analyzing

Point a project at the jdeps text file in `.codeps/config.yaml`; `codeps status` parses it and
computes metrics in one step:

```yaml
projects:
  app:
    root: .
    source: jdeps
    inputs: [jdeps.txt]
```

```shell
codeps status
```

Only the project's own classes appear — the parser drops edges to external classes that are
not themselves defined in the input.

Cycles, change propagators (fanIn/fanOut), surface risk, and orphans are all computed the same
way as for SemanticDB input — see the [Metrics report](/reference/report.html). Note that cut
analysis is not currently exposed by the CLI, so `cutAnalysis.status` is always `notRequested`.

## Filtering JDK noise

The raw `jdeps -verbose:class` output includes edges to `java.*`, `scala.*` and other
platform classes. Exclude them in the config to keep the graph focused on your code:

```yaml
projects:
  app:
    root: .
    source: jdeps
    inputs: [jdeps.txt]
    include: [com.example]
    exclude: [java.**, scala.**]
```

> Note: excludes are package patterns matched against each node's root package —
> `java.**` excludes everything under `java` (see [Include / exclude patterns](/reference/cli.html#include--exclude-patterns)).

## Collapsing

Just like with SemanticDB input, the `collapse` config field applies:

```yaml
    collapse: [com.example.modules.**]
```

See [CLI reference](/reference/cli.html) for the full configuration field list.

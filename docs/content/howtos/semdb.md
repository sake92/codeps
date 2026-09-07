---
layout: howto.html
title: Export & analyze Scala/SemanticDB projects
description: Analyzing Scala dependencies with SemanticDB
---

# Export & analyze Scala/SemanticDB projects

SemanticDB is a data format describing the semantic information of Scala (and Java) programs,
produced by the Scala compiler (`-Xsemanticdb` flag) or tools like scala-cli.

Scala data is the richest codeps input: it carries package/file/type/member symbols, so a
project can compute metrics at both granularities (ports/mutPorts resolved during parsing). It also carries
per-symbol access/kind information, which the parser turns into the
[exposed-surface metrics](/reference/report.html#exposed-surface)
(`ports`/`mutPorts`: sealed hierarchies, givens, vars and mutable collections are all
resolved at export time). Only the project's **own symbols** are exported — references to
external libraries and the JDK are dropped by the exporter.

## Generating SemanticDB files

If your build already emits SemanticDB (scala-cli, sbt or Maven with the `semanticdb` plugin,
plain scalac with `-Xsemanticdb`), the `.semanticdb` files are already on your disk —
the example below is just one way to get them:

With scala-cli:

```shell
scala-cli compile --server=false --semanticdb -d classes src/
```

This writes one `.semanticdb` file per source file under `classes/META-INF/semanticdb/`.

Other ways to get SemanticDB output:

- scalac directly: add `-Xsemanticdb` (and optionally `-P:semanticdb:sourceroot:...`) to your compile flags
- Maven / sbt: enable the `semanticdb` compiler plugin and check the generated files in the target dir

## Configuring and analyzing

Point a project at the SemanticDB directory in `.codeps/config.yaml`; `codeps status` walks the
directory, reads every `*.semanticdb` file, and computes metrics in one step — there is no
separate export/report pipeline to run:

```yaml
projects:
  app:
    root: .
    source: semanticdb
    inputs: [classes/META-INF/semanticdb]
```

```shell
java -jar codeps.jar status
```

- `inputs` takes one or more **directories** — the whole tree is walked for `*.semanticdb` files
- codeps computes cycles, change propagators, and exposed-surface/encapsulation metrics
  (`ports`/`mutPorts`/`exposure`/`dependentsPerPublicPort` plus declaration visibility counters)
  over both packages and source files. The dashboard has a tab for each; use `--scope files`
  with inspection commands for file detail.
- Source file ids are relative to the project's `root`.

See the [CLI reference](/reference/cli.html) for the full set of configuration fields, and the
[Metrics report](/reference/report.html) for the full field reference, including `inspect-cycle`
and `inspect-node` report-only detail views.

## Filtering and collapsing

`include`/`exclude` config fields take package patterns: a pattern `com.example` matches the
package itself and everything below it; excludes win over includes.

```yaml
projects:
  app:
    root: .
    source: semanticdb
    inputs: [classes/META-INF/semanticdb]
    include: [com.example]
    exclude: [com.example.internal]
```

Collapse rules merge whole subtrees into a single node, which keeps big graphs readable:

```yaml
    collapse: [com.example.modules.**]
```

When multiple rules match, the longest prefix wins; loops created by collapsing are dropped.

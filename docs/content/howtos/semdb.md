---
layout: howto.html
title: Export & analyze Scala/SemanticDB projects
description: Analyzing Scala dependencies with SemanticDB
---

# Export & analyze Scala/SemanticDB projects

SemanticDB is a data format describing the semantic information of Scala (and Java) programs,
produced by the Scala compiler (`-Xsemanticdb` flag) or tools like scala-cli.

Scala data is the richest codeps input: it carries package/file/type/member symbols, and
`export` collapses them into `package` and `file` nodes with file-level edges
(ports/mutPorts resolved at export time), so the analyzer can produce metrics at both
scopes — the package graph and the file graph of a selected package. It also carries
per-symbol access/kind information, which the exporter turns into the
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

## Exporting the graph

`codeps export` walks the directory, reads every `*.semanticdb` file and emits the
[standard JSON export format](/reference/json-input.html) (`nodes` + `edges`):

```shell
codeps export --from semanticdb classes/META-INF/semanticdb -o deps.json
```

- `--from semanticdb` selects the SemanticDB producer (required)
- the input is a **directory** — the whole tree is walked for `*.semanticdb` files
- `-o deps.json` writes the graph to a file; without `-o` it goes to stdout

Source file ids are made relative to the current working directory; pass `--root <dir>`
to make them relative to `<dir>` instead (e.g. the project root):

```shell
codeps export --from semanticdb classes/META-INF/semanticdb --root . -o deps.json
```

## Analyzing

`codeps report` reads the JSON graph and emits the flat metrics report:

```shell
codeps export --from semanticdb classes/META-INF/semanticdb -o deps.json
codeps report --scope packages deps.json
```

- `--scope packages` — metrics over the whole package graph: cycles with simulated
  cut candidates, per-package exposed-surface (`ports`/`mutPorts`/`exposure`/`utilization`),
  and orphans
- `--scope files` — the same metrics over the **file graph** of the packages selected with
  `-i`; e.g. `-i com.example` descends into `com.example` and everything below it
- `-i`/`-e`/`-c` filter and collapse, e.g. `-i com.example` keeps only your packages
- `--format json` emits machine-readable JSON (the default `table` renders the same data as plain aligned text)

No intermediate file needed — pipe `export` straight into `report` (the `-` tells
`report` to read the JSON from stdin):

```shell
codeps export --from semanticdb classes/META-INF/semanticdb | codeps report --scope packages -
```

See the [Metrics report](/reference/report.html) for the full field reference.

## Filtering and collapsing

`--include`/`--exclude` take package patterns: a pattern `com.example` matches the package
itself and everything below it; excludes win over includes.

```shell
# only com.example packages, no third-party or JDK noise
codeps report --scope packages -i com.example deps.json

# com.example.* minus internal helpers
codeps report --scope packages -i com.example -e com.example.internal deps.json
```

Collapse rules merge whole subtrees into a single node, which keeps big graphs readable:

```shell
codeps report --scope packages -i com.example -c com.example.modules.** deps.json
```

When multiple rules match, the longest prefix wins; loops created by collapsing are dropped.
See [CLI reference](/reference/cli.html) for the full option list.

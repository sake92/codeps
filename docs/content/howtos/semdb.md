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
[codeps export format](/reference/json-input.html) (`nodes` + `edges`):

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb -o deps.json
```

- `--from semanticdb` selects the SemanticDB producer (required)
- `--input` takes a **directory** — the whole tree is walked for `*.semanticdb` files (repeatable)
- `-o deps.json` writes the graph to a file; without `-o` it goes to stdout

Source file ids are made relative to the current working directory; pass `--root <dir>`
to make them relative to `<dir>` instead (e.g. the project root):

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb --root . -o deps.json
```

## Analyzing

`codeps report-packages` reads the JSON graph and emits the flat metrics report over the
package graph:

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb -o deps.json
codeps report-packages --input deps.json
```

- `report-packages` — metrics over the whole package graph: cycles with optional budgeted cut
  analysis, per-package exposed-surface and encapsulation (`ports`/`mutPorts`/`exposure`/
  `dependentsPerPublicPort` plus declaration visibility counters),
  and orphans
- `report-files` — the same metrics over the **file graph** of the packages selected with
  `--include`; e.g. `--include com.example` descends into `com.example` and everything below it
- `--input` selects the JSON graph (a file, or `-` for stdin); `-i` works too
- `--include`/`--exclude`/`--collapse` filter and collapse (see below)
- `--format json` emits machine-readable JSON with complete report data; the default `table` is
  a compact plain-text presentation that may abbreviate cut lists; `--format markdown` emits the
  same bounded triage view as deterministic GitHub-Flavored Markdown, always without ANSI styling

No intermediate file needed — pipe `export` straight into `report-packages` (the `-` tells
it to read the JSON from stdin):

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb | codeps report-packages --input -
```

See the [Metrics report](/reference/report.html) for the full field reference.

## Filtering and collapsing

`--include`/`--exclude` take package patterns: a pattern `com.example` matches the package
itself and everything below it; excludes win over includes.

```shell
# only com.example packages, no third-party or JDK noise
codeps report-packages --include com.example --input deps.json

# com.example.* minus internal helpers
codeps report-packages --include com.example -e com.example.internal --input deps.json
```

Collapse rules merge whole subtrees into a single node, which keeps big graphs readable:

```shell
codeps report-packages --include com.example -c com.example.modules.** --input deps.json
```

When multiple rules match, the longest prefix wins; loops created by collapsing are dropped.
See [CLI reference](/reference/cli.html) for the full option list.

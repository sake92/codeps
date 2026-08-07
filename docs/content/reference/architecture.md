---
layout: reference.html
title: Architecture
description: codeps module architecture
---

# Architecture

codeps is a Scala 3 project built with [deder](https://sake92.github.io/deder) (during development; end users run the prebuilt jar).
It is organized as small modules with a clear data flow:

```diagram:mermaid
flowchart LR
  A[parser-semanticdb] --> C[core]
  B[parser-jdeps] --> C
  C --> D[export]
  C --> E[cli]
  D --> E
```

```text
compiler output ──► parser ──► graph (core) ──► exporter ──► stdout / file
   .semanticdb       semdb        filter            dot
   jdeps.txt         jdeps        collapse          json
                                                     mermaid
```

## Modules

### core

The graph model and processing pipeline:

- `model/PackageEdge` — a directed `(source, target)` package dependency
- `model/PkgStats` — per-package `fileCount` / `classCount` (aggregatable)
- `model/CollapseRule` — collapse rules: `Wild` (`prefix.**`) and `SingleLevel` (`prefix.*`)
- `graph/Filter` — applies include/exclude patterns; universe = matching own packages,
  edges kept only when both endpoints are in the universe
- `graph/Collapser` — maps nodes/edges through collapse rules (longest prefix wins),
  drops loops, merges stats
- `graph/GraphBuilder` — builds a `jgrapht` `DefaultDirectedGraph[String, DefaultEdge]`,
  keeping isolated vertices

Graph storage uses [jgrapht](https://jgrapht.org/).

### parser-semanticdb

`SemanticDbParser` reads `.semanticdb` files ([protobuf `TextDocuments`](https://scalameta.org/docs/semanticdb/specification.html))
via `semanticdb-shared`:

- **own packages** — from each document's defined symbols (fallback: the source URI path)
- **edges** — from every symbol occurrence referencing a symbol in another package
  (package = everything before the last `/`, `/` → `.`)
- **stats** — per package: number of documents (files) and class-like symbols (class/object/trait)

### parser-jdeps

`JdepsParser` parses `jdeps -verbose:package` text output:
indented detail lines (`   pkg.a -> pkg.b   archive`) become edges;
non-indented summary lines are skipped. No stats.

### export

- `DotExporter` — Graphviz `digraph`
- `JsonExporter` — `{nodes, edges, nodeInfo}` (stats when available)
- `MermaidExporter` — `flowchart LR` with aliased node ids

See [Export formats](/reference/export-formats.html) for details.

### cli

`Main` (mainargs-based) with `semdb` and `jdeps` subcommands: resolves inputs,
runs the pipeline (parse → filter → collapse → build graph → export) and writes
stdout or `-o` file. See the [CLI reference](/reference/cli.html).

### test-utils

`FixtureCompiler` compiles the checked-in `testFixtures/example1` sources once into
`tmp/examples/example1` (using scala-cli with `--semanticdb` and `jdeps`), thread-safe
and cached across test runs.

## Testing

All modules have `munit` suites (`MainSpec` runs the real CLI as a subprocess and asserts
on exit codes and output files).

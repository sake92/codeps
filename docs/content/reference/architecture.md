---
layout: reference.html
title: Architecture
description: codeps module architecture
---

# Architecture

codeps is a Scala 3 project built with [deder](https://sake92.github.io/deder) (during development; end users run the prebuilt jar).
It is organized as small modules with a clear, two-step data flow — *producers* emit a
[common JSON graph format](/reference/json-input.html), the *analyzer* consumes it:

```diagram:mermaid
flowchart LR
  A[parser-semanticdb] --> C[core]
  B[parser-jdeps] --> C
  C --> D[export]
  C --> E[cli]
  D --> E
  A --> E
  B --> E
```

```text
┌── producer ─────────────────────┐   ┌── analyzer ──────────────────────────────┐
│ codeps export --from semanticdb │   │ codeps analyze -g <level> -f dot|mermaid │
│ codeps export --from jdeps      │   │                                          │
│                                │   │                                          │
│ parse ──► common JSON graph ────┼──►│ filter ─► aggregate -g ─► collapse ─►    │
│ (DepsGraph, core)               │   │ graph ─► cycles ─► dot / mermaid         │
└─────────────────────────────────┘   └──────────────────────────────────────────┘
```

## Modules

### core

The graph model and processing pipeline:

- `model/NodeKind` — node kinds: `package`, `file`, `type`, `member` (lowercase enum)
- `model/Node` — `id`, `kind`, optional `parentId` (nearest enclosing node) and `file`
  (the source file node's id, on `type`/`member` nodes); walks `parentId` chains to
  find a node's root package
- `model/Edge` — a directed `(source, target)` edge between node ids
- `model/DepsGraph` — the parser contract and the common JSON format:
  `{nodes: Set[Node], edges: Set[Edge]}`; `merge` combines graphs, `withoutDanglingEdges`
  drops edges whose endpoints are not both in the graph's nodes (derives `JsonRW` via tupson)
- `model/CollapseRule` — collapse rules: `Wild` (`prefix.**`) and `SingleLevel` (`prefix.*`)
- `graph/Filter` — applies include/exclude patterns against each node's root package;
  universe = matching nodes, edges kept only when both endpoints are in the universe
- `graph/Aggregator` — maps a `DepsGraph` to a granularity level
  (`member`/`type`/`file`/`package`), lifting nodes to their nearest ancestor at that
  level and lifting edges through the same mapping; nodes coarser than the level
  (packages at `type`/`file`, files at `type`/`package`) are dropped, unless a finer
  node falls back to them (package-parented members at `type` level, file-less nodes
  at `file` level)
- `graph/Collapser` — maps nodes/edges through collapse rules (longest prefix wins),
  drops loops
- `graph/GraphBuilder` — builds a `jgrapht` `DefaultDirectedGraph[String, DefaultEdge]`,
  keeping isolated vertices
- `graph/CycleDetector` — finds cycles via jgrapht's
  `KosarajuStrongConnectivityInspector`; extracts one representative elementary
  cycle per strongly connected component of size ≥ 2 (self-loops are dropped
  earlier), in true dependency order and deterministic (rotated to start from
  the smallest member)

Graph storage uses [jgrapht](https://jgrapht.org/).

### parser-semanticdb

`SemanticDbParser` reads `.semanticdb` files ([protobuf `TextDocuments`](https://scalameta.org/docs/semanticdb/specification.html))
via `semanticdb-shared` and emits a `DepsGraph`:

- nodes — one `file` node per document (id = source URI relative to `--root`), and
  `type`/`member`/`package` nodes for the document's defined symbols (constructors
  and local symbols are skipped)
- edges — for each symbol occurrence, the innermost defined symbol whose range
  contains the occurrence → the referenced symbol; self-edges skipped
- external references are dropped: only own symbols are materialized (dangling-edge
  prune after merge via `withoutDanglingEdges`)

### parser-jdeps

`JdepsParser` parses `jdeps -verbose:class` text output and emits a `DepsGraph`:
indented detail lines (`   com.example.Foo -> java.lang.String   java.base`) become
type-level nodes and edges; non-indented summary lines are skipped. Own classes are
the sources of detail lines, and edges are kept only when the target is an own class
too. No file/member nodes (jdeps has no such info).

### export

- `DotExporter` — Graphviz `digraph` (+ `// cycles:` comment when the graph has cycles)
- `MermaidExporter` — `flowchart LR` with aliased node ids (+ `%% cycles:` comment when the graph has cycles)

The `OutputFormat` enum is `dot`/`mermaid` only — JSON is not an output format of the
analyzer; the JSON graph is the *input*, produced by `export`.

See [Export formats](/reference/export-formats.html) and
[Common JSON format](/reference/json-input.html) for details.

### cli

`Main` (mainargs-based) with two subcommands:

- `export` — resolves inputs, runs the producers (`semanticdb`/`jdeps`), merges
  the resulting `DepsGraph`s and writes the common JSON to stdout or `-o`
- `analyze` — reads the common JSON (file or stdin), runs the pipeline
  (filter → aggregate to `-g` → collapse → build graph → detect cycles → export)
  and writes dot/mermaid to stdout or `-o`

See the [CLI reference](/reference/cli.html).

### test-utils

`FixtureCompiler` compiles the checked-in `testFixtures/example1` sources once into
`tmp/examples/example1` (using scala-cli with `--semanticdb` and `jdeps -verbose:class`),
thread-safe and cached across test runs.

## Testing

All modules have `munit` suites (`MainSpec` runs the real CLI as a subprocess and asserts
on exit codes and output files).

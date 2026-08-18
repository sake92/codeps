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
│ codeps export --from semanticdb │   │ codeps draw -g <level> -f dot|mermaid     │
│ codeps export --from jdeps      │   │ codeps report [-i] [-e] [-c]              │
│                                │   │                                          │
│ parse ──► common JSON graph ────┼──►│ filter ─► skip-tests? ─► aggregate -g ─► │
│ (DepsGraph, core)               │   │ collapse ─► graph ─► cycles ─►           │
│                                 │   │ dot / mermaid / report                   │
└─────────────────────────────────┘   └──────────────────────────────────────────┘
```

## Modules

### core

The graph model and processing pipeline:

- `model/NodeKind` — node kinds: `package`, `file`, `type`, `member` (lowercase enum)
- `model/Node` — `id`, `kind`, optional `parentId` (nearest enclosing node) and `file`
  (the source file node's id, on `type`/`member` nodes); walks `parentId` chains to
  find a node's root package
- `model/Edge` — a directed `(source, target)` edge between node ids, carrying a
  `weight`: the number of finer-grained references it represents (1 from the
  parsers, summed by aggregation/collapse)
- `model/DepsGraph` — the parser contract and the common JSON format:
  `{nodes: Set[Node], edges: Set[Edge]}`; `merge` combines graphs, `withoutDanglingEdges`
  drops edges whose endpoints are not both in the graph's nodes (derives `JsonRW` via tupson)
- `model/CollapseRule` — collapse rules: `Wild` (`prefix.**`) and `SingleLevel` (`prefix.*`)
- `graph/Filter` — applies include/exclude patterns against each node's root package;
  universe = matching nodes, edges kept only when both endpoints are in the universe
- `graph/TestFilter` — `skipTests(graph, patterns)` drops nodes defined in test
  files: `file` nodes matched by id, `type`/`member` nodes matched by their `file`
  attribute (package and file-less nodes never match — jdeps is a no-op); carries
  the built-in `defaultPatterns` (`**/test/**`, `**/*.test.scala`, `*Spec`/`*Test`/
  `*Tests`/`*Suite` name conventions for `.scala` and `.java`); `--test-pattern`
  replaces them
- `graph/Prune` — drops `package` nodes left childless after filtering; runs at the
  end of both `Filter` and `TestFilter`
- `graph/Glob` — minimal `**`/`*`/`?` glob matcher used by `TestFilter`
- `graph/Aggregator` — maps a `DepsGraph` to a granularity level
  (`member`/`type`/`file`/`package`), lifting nodes to their nearest ancestor at that
  level and lifting edges through the same mapping; nodes coarser than the level
  (packages at `type`/`file`, files at `type`/`package`) are dropped, unless a finer
  node falls back to them (package-parented members at `type` level, file-less nodes
  at `file` level); edges that lift onto the same pair are merged with summed weights
- `graph/Collapser` — maps nodes/edges through collapse rules (longest prefix wins),
  drops loops, merges edges landing on the same pair with summed weights
- `graph/GraphBuilder` — builds a `jgrapht` `DefaultDirectedGraph[String, DefaultEdge]`,
  keeping isolated vertices
- `graph/CycleDetector` — finds cycles via jgrapht's
  `KosarajuStrongConnectivityInspector`; extracts one representative elementary
  cycle per strongly connected component of size ≥ 2 (self-loops are dropped
  earlier), in true dependency order and deterministic (rotated to start from
  the smallest member)
- `report/Reporter` — runs the pipeline (filter → aggregate → collapse → build
  graph → cycles) at every granularity in one pass and grades each cycle:
  package = `bad`, file = `meh`, type/member cycles within one file = `fine`
  (cross-file or file-less members = `meh`). Computes per-level metrics
  (in/out/hub), `breakEdges` (cycle edge cut list), and package-level
  `hardestKnots`/`easyWins` suggestions. Emits the self-contained
  `AnalysisReport` JSON ([schema](/reference/report.html)).

Graph storage uses [jgrapht](https://jgrapht.org/).

### parser-semanticdb

`SemanticDbParser` reads `.semanticdb` files ([protobuf `TextDocuments`](https://scalameta.org/docs/semanticdb/specification.html))
via `semanticdb-shared` and emits a `DepsGraph`:

- nodes — one `file` node per document (id = source URI relative to `--root`), and
  `type`/`member`/`package` nodes for the document's defined symbols (constructors
  and local symbols are skipped). Class-scoped private symbols (`private`,
  `private[this]`) are skipped too: they can never create cross-package dependencies,
  and their references are collapsed into the nearest non-private ancestor (enclosing
  type, else file/package), so package- and file-level results are unaffected.
  `private[pkg]` and `protected` symbols are kept.
- edges — for each symbol occurrence, the innermost defined symbol whose range
  contains the occurrence → the referenced symbol; self-edges skipped; references
  to/from class-private symbols are collapsed up or dropped
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

`Main` (mainargs-based) with three subcommands:

- `export` — resolves inputs, runs the producers (`semanticdb`/`jdeps`), merges
  the resulting `DepsGraph`s and writes the common JSON to stdout or `-o`
- `draw` — reads the common JSON (file or stdin), runs the pipeline
  (filter → aggregate to `-g` → collapse → build graph → detect cycles → export)
  and writes dot/mermaid to stdout or `-o`; `--skip-tests`/`--test-pattern`
  exclude test code from the analysis (via `TestFilter`)
- `report` — reads the common JSON (file or stdin), runs the pipeline at all four
  granularities via `Reporter` and writes the analysis report JSON to stdout or `-o`;
  `--skip-tests`/`--test-pattern` exclude test code from the analysis (via `TestFilter`)

See the [CLI reference](/reference/cli.html) and the
[Cycle analysis report](/reference/report.html).

### test-utils

`FixtureCompiler` compiles the checked-in `testFixtures/example1` sources once into
`tmp/examples/example1` (using scala-cli with `--semanticdb` and `jdeps -verbose:class`),
thread-safe and cached across test runs.

## Testing

All modules have `munit` suites (`MainSpec` runs the real CLI as a subprocess and asserts
on exit codes and output files).

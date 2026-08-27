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
  C --> E[cli]
  A --> E
  B --> E
```

```text
┌── producer ─────────────────────┐   ┌── analyzer ────────────────────────────────┐
│ codeps export --from semanticdb │   │ codeps report --scope packages|files       │
│ codeps export --from jdeps      │   │                                            │
│                                │   │ filter ─► skip-tests? ─► scope aggregation  │
│ parse ──► common JSON graph ────┼──►│ ─► collapse ─► metrics ─► json / table     │
│ (DepsGraph, core)               │   │                                            │
└─────────────────────────────────┘   └────────────────────────────────────────────┘
```

## Modules

### core

The graph model and processing pipeline:

- `model/NodeKind` — node kinds: `package`, `file`, `type`, `member` (lowercase enum)
- `model/Node` — `id`, `kind`, optional `parentId` (nearest enclosing node) and `file`
  (the source file node's id, on `type`/`member` nodes), plus the exposure fields
  `isExposed`/`ports`/`mutPorts` resolved by the extraction backend; walks `parentId`
  chains to find a node's root package
- `model/Edge` — a directed `(source, target)` edge between node ids, carrying a
  `weight`: the number of finer-grained references it represents (1 from the
  parsers, summed by aggregation/collapse)
- `model/DepsGraph` — the parser contract and the common JSON format:
  `{nodes: Set[Node], edges: Set[Edge]}`; `merge` combines graphs, `withoutDanglingEdges`
  drops edges whose endpoints are not both in the graph's nodes (derives `JsonRW` via tupson)
- `model/CollapseRule` — collapse rules: `Wild` (`prefix.**`) and `SingleLevel` (`prefix.*`);
  `Wild` matches both dotted and slash-separated ids, so `src.**` collapses file ids too
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
- `graph/Collapser` — maps nodes/edges through collapse rules (longest prefix wins),
  drops loops, merges edges landing on the same pair with summed weights
- `graph/TarjanScc` — Tarjan's strongly connected components over a plain node/edge
  list; rebuilt fresh on every call (nothing cached), deterministic (sorted adjacency,
  components sorted by min member id); `cycles` = multi-member components only
- `graph/Aggregator` — collapses granular graphs into package+file nodes: type/member
  symbols land in their file (file-less jdeps types in their root package), `ports`/
  `mutPorts` summed per file, file-level edges with summed weights, `parentId` = root
  package; applied by `export` at write time; the granular graph stays the internal
  parser contract
- `report/MetricsCalculator` — the language-agnostic metrics layer: maps the graph to
  a scope (`packages` — root packages; `files` — file ids, strict: file-less nodes
  are dropped), sums port contributions per scope node, applies collapse, then
  derives every metric fresh from the node/edge list: fan_in/fan_out, orphans,
  cycles with `ext_fan_in`, a closed cycle path through the smallest member, and
  simulated cut candidates (internal edges whose removal resolves the cycle, top 6
  by weight, each removed from a copy of the edge list and re-simulated), the greedy
  `min_cuts_estimate`, and the condensation-graph `critical_path_length`
- `report/MetricsReport` — the flat report model with a write-only `JsonRW` emitting
  the exact snake_case JSON shape ([schema](/reference/report.html))
- `report/ReportTable` — plain aligned-text rendering of the same data (`--format table`)

The metrics layer never knows Scala syntax: it consumes per-node `isExposed`/`ports`/
`mutPorts` already resolved by the extraction backend, so a future TS/Python extractor
only has to emit the same generic node/edge shape.

### parser-semanticdb

`SemanticDbParser` reads `.semanticdb` files ([protobuf `TextDocuments`](https://scalameta.org/docs/semanticdb/specification.html))
via `semanticdb-shared` and emits a `DepsGraph`:

- nodes — one `file` node per document (id = source URI relative to `--root`), and
  `type`/`member`/`package` nodes for the document's defined symbols (constructors
  and local symbols are skipped). Class-scoped private symbols (`private`,
  `private[this]`) are skipped too: they can never create cross-package dependencies,
  and their references are collapsed into the nearest non-private ancestor (enclosing
  type, else file/package), so package- and file-level results are unaffected.
  `private[pkg]` and `protected` symbols are kept but marked `isExposed = false`.
- exposure — the Scala adapter's weight rules live here: types 3, defs/vals 1,
  sealed-hierarchy members 0.5 (self-sealed, owner chain, or sealed class parent),
  givens/implicits a flat +1; `mut_ports` for `var`s and mutable-collection-typed
  vals/defs (`scala.collection.mutable.*`, `scala.Array`); `var` setters (`x_=`) are
  accessors and never count as surface. See the [Metrics report](/reference/report.html).
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
too. No file/member nodes (jdeps has no such info); all nodes are `isExposed: true`
with `ports`/`mutPorts` 0, so `--scope files` errors on jdeps data and `utilization`
is always `null` — a known gap, not silently meaningful.

### cli

`Main` (mainargs-based) with two subcommands:

- `export` — resolves inputs, runs the producers (`semanticdb`/`jdeps`), merges
  the resulting `DepsGraph`s, collapses them to package/file level via
  `Aggregator.fileLevel` and writes the common JSON to stdout or `-o`
- `report` — reads the common JSON (file or stdin), runs the pipeline
  (filter → skip-tests? → scope aggregation → collapse → metrics) via
  `MetricsCalculator` and writes the flat report to stdout or `-o`, as
  `table` (default) or `json`

See the [CLI reference](/reference/cli.html) and the
[Metrics report](/reference/report.html).

### test-utils

`FixtureCompiler` compiles the checked-in `testFixtures/example1` sources once into
`tmp/examples/example1` (using scala-cli with `--semanticdb` and `jdeps -verbose:class`),
thread-safe and cached across test runs. The fixture includes an `Exposure.scala`
exercising the adapter's exposure rules (sealed hierarchy, given, implicit, var,
mutable collections, private/protected members).

## Testing

All modules have `munit` suites (`MainSpec` runs the real CLI as a subprocess and asserts
on exit codes and output files).

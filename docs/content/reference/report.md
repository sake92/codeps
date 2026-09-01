---
layout: reference.html
title: Metrics report
description: the codeps report JSON format — findings, cycles, surface, orphans
---

# Metrics report

`codeps report-packages` or `codeps report-files` consumes the [codeps export format](/reference/json-input.html) (a file, or stdin via `-`)
and emits a single flat JSON document: per-scope metrics over the graph's **packages**, or over the
**files** of the packages selected with `--include`.

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb | codeps report-packages --format json --input - > report.json
```

Core metrics are computed fresh from the graph's node/edge list on every run. The default report
does no cut search and is a pure function of its input; optional budgeted cut analysis is explicit
and can stop at a wall-clock deadline. Set the `SOURCE_DATE_EPOCH` env var (epoch seconds) to pin
`generatedAt` for deterministic CI diffs.

## Structure

```json
{
  "schemaVersion": 2,
  "scope": "packages",
  "generatedAt": "<ISO8601 UTC, second precision, e.g. 2026-08-27T10:00:00Z>",
  "summary": {
    "nodes": 100,
    "edges": 214,
    "nodesInCycles": 34,
    "orphans": 3,
    "criticalPathLength": 7
  },
  "cycles": [
    {
      "id": "scc:cache",
      "members": ["cache", "scheduler"],
      "witnessCycle": ["cache", "scheduler", "cache"],
      "size": 2,
      "extFanIn": 5,
      "internalEdges": 2,
      "incomingEdges": 5,
      "outgoingEdges": 3,
      "cutAnalysis": {
        "status": "notRequested",
        "greedyCutEstimate": null,
        "solutions": [],
        "examinedCandidates": 0
      }
    }
  ],
  "propagators": [
    { "node": "cache", "fanIn": 3, "fanOut": 2, "score": 2.5 }
  ],
  "surface": [
    {
      "node": "cache", "fanIn": 3, "fanOut": 2, "ports": 9, "mutPorts": 5, "exposure": 24,
      "dependentsPerPublicPort": 0.33, "cycleId": "scc:cache",
      "publicSurface": 3, "protectedSurface": 2, "packageSurface": 1, "privateSurface": 4,
      "publicMutableSurface": 1, "protectedMutableSurface": 0, "packageMutableSurface": 0,
      "privateMutableSurface": 1, "totalDeclaredSurface": 10,
      "encapsulationRatio": 0.3, "publicMutableRatio": 0.33
    }
  ],
  "publicSymbols": [
    { "symbol": "com.example.cache.Cache", "consumerCount": 2, "referenceCount": 3, "usageConfidence": "semanticdbComplete" }
  ],
  "orphans": ["DeadUtil.scala"],
  "findings": [
    {
      "id": "cycle:scc:cache",
      "kind": "cycle",
      "severity": "high",
      "subject": "scc:cache",
      "evidence": "size=2, extFanIn=5, greedyCutEstimate=none",
      "confidence": "high",
      "nextAction": "inspect-cycle scc:cache"
    }
  ]
}
```

## Summary

- `schemaVersion` — integer `2`. This is a schema break; v1 is not an accepted output compatibility target.

- `nodes` / `edges` — size of the scope graph.
- `nodesInCycles` — total members of all cycles (multi-member strongly connected components).
- `orphans` — count of nodes with zero fan-in and zero fan-out (dead-code-removal candidates).
- `criticalPathLength` — the longest path, in edges, through the condensation DAG (each SCC
  collapsed to one node). The structural lower bound on best-case parallel build time, whether
  or not cycles exist. A value of `0` for a graph that is one SCC means the condensation graph
  has one node (and therefore no edges); it does **not** mean the underlying code is healthy or
  acyclic.

## Findings

`findings` is a deterministic index of actionable diagnostics. Each finding has a stable
`id`, a `kind` (`cycle`, `propagator`, `mutableSurface`, `structuralUse`, or `unusedPublicSymbol`), `severity`, and
`subject`, plus human-readable `evidence`, `confidence`, and a `nextAction`. Findings are ranked by
severity, then metric score, then id. They cover every reported SCC, every above-average propagator,
every node with exposed mutable ports, and every node whose structural-use proxy is below `1.0`.
The structural-use kind is only a graph proxy, not proof that a public symbol is unused. An
`unusedPublicSymbol` finding is emitted only when the optional `publicSymbols` index is present.
JSON serialization bounds both `findings` and `publicSymbols` at 10,000 rows each so large
projects remain agent-usable. When rows are omitted, `truncation` is present with
`findingsOmitted` and `publicSymbolsOmitted` counts; the in-memory report and `--all` table view
retain the complete inventories.

## Cycles

A cycle is a strongly connected component with more than 1 member (Tarjan's algorithm). Singleton
components are just acyclic nodes and are never reported.

- `id` — `scc:` + the lexicographically smallest member id. Stable across recomputation after cuts
  (a counter-based id would renumber unpredictably).
- `members` — the exhaustive, sorted list of every node in the SCC. It is never merely a witness
  path, including when the SCC contains several interlocking cycles.
- `witnessCycle` — one deterministic closed cycle path through the smallest member; the first node
  is repeated at the end. This is a representative cycle, not a membership list.
- `size` — full member count of the SCC (may exceed the path length, see above).
- `extFanIn` — edges whose target is in the SCC and whose source is outside: how much outside
  stuff is exposed to the cycle's blast radius. Cycles are ranked by `size` first, `extFanIn`
  as tiebreaker.
- `internalEdges` — distinct edges whose source and target are both in the SCC.
- `incomingEdges` — distinct edges entering the SCC from outside. This is the same count as
  `extFanIn`, retained as an explicit directional edge count for inspection consumers.
- `outgoingEdges` — distinct edges leaving the SCC to outside nodes.
- `cutAnalysis` — the explicit result of optional cut investigation. Its `status` is
  `notRequested` for the default fast report, `completedExact` when bounded enumeration finished
  its complete candidate space, `completedHeuristic` when only the greedy pass was available
  (including large SCCs), or `budgetExceeded` when its time or candidate limit was reached.
  `budgetExceeded` is still a successful report result. Every serialized solution is validated
  against the original SCC; a greedy plan is never emitted if it leaves any cyclic component.
  - `greedyCutEstimate` — nullable greedy estimate of the total cuts needed to dissolve the
    cycle. It is present only when the greedy pass completed before the budget was exhausted; it
    is a heuristic, not a guaranteed-minimum feedback-edge set.
  - `solutions` — up to 3 **complete** ways to break the cycle, simplest first. Each solution is
    a list of `cuts` (`{edge, weight}`); removing ALL of them together dissolves the cycle (no
    multi-member component remains among its members). A solution can be 1 edge or several.
    Results are ranked by fewest cuts, cheapest total weight, then lexicographic order. Sets
    containing a smaller working solution are skipped. Partial candidates are never serialized.
  - `examinedCandidates` — number of candidate simulations started before the budget check
    stopped the search.
- Cut analysis is opt-in with `--analyze-cuts`; `--cut-time-limit` and
  `--cut-candidate-limit` bound each SCC's investigation. The default report never invokes
  feedback-edge search, so its cycle `cutAnalysis` has no estimate or solutions.
- Table output is intentionally shorter than this JSON schema: it displays at most 8 cuts from
  each complete solution and may replace a dense knot's cut list with structural guidance.
  `--format json` retains every complete solution, canonical id, status, and budget count.

## Change propagators

The JSON index contains every node above the normalized propagation threshold. The default table shows
the top 10 and reports its shown/total count; pass `--all` to either report command for the complete
table inventory.

- `score` — `(fanIn / avgFanIn + fanOut / avgFanOut) / 2`, where `avgFanIn`/`avgFanOut`
  are the graph-wide means (`edges / nodes`). An exactly average node scores `1.0`; a hub with
  twice the average fan-in and no fan-out scores `2.0`. Only nodes above `1.0` are listed,
  sorted by score descending. A graph with no edges has no propagators.
- `fanIn`/`fanOut` — the raw counts (same values as in `surface`), shown for context.

## Surface

One row per scope node is retained in JSON. The default table shows the top 10 rows as
`Surface risks (top 10 of N)`; `--all` shows every row.

The table keeps its default surface view compact with the headings `node`, `in`, `out`,
`ports`, `mut`, `encap%`, and `use`. For a wider table, repeat `--columns` with one or more
groups: `visibility` adds `pub`, `prot`, `pkg`, and `priv`; `mutability` adds `pubMut`,
`protMut`, `pkgMut`, and `privMut`; and `coupling` adds `exp`, `total`, and `mut%`.
`--columns all` selects the complete accounting view. Groups are rendered in canonical order,
duplicate columns are removed, and these short aliases apply only to table headings; JSON
continues to use its camelCase field names. With no `--columns`, the `core` group is used.

- `fanIn` / `fanOut` — count of distinct edges in/out. Always derived from the edge list, never
  stored separately.
- `ports` — weighted count of exposed members (see [Exposed surface](#exposed-surface)).
- `mutPorts` — count of exposed **mutable** members: `var`s, or vals/defs typed as a mutable
  collection. A coupling channel that never shows up as a graph edge.
- `exposure` — `ports + mutPorts * 3`; mutable ports weighted 3× because they are a hidden
  channel on top of being exposed at all. Always look at the `ports`/`mutPorts` breakdown, never
  `exposure` alone.
- `dependentsPerPublicPort` — `fanIn / ports` when `fanIn > 0` and `ports > 0`, else `null`.
  This is a structural proxy: file/package edges do not prove that a particular public symbol
  is used. A `null` value is meaningful (no consumers or no weighted public ports), not a 0.
- `cycleId` — the nullable stable SCC id (`scc:` plus the lexicographically smallest member) for
  nodes in a reported cycle; `null` for nodes outside all cycles.

Rows are sorted by `dependentsPerPublicPort` ascending (nulls last): the most exposed-for-its-use
nodes first. Raw declaration counters are retained so consumers can triage public surface,
public mutability, and encapsulation independently.

### Declaration surface fields

Each row also carries raw declaration counts, aggregated from the adapter-neutral
`declarationSurface` counters in the export graph:

- `publicSurface`, `protectedSurface`, `packageSurface`, `privateSurface` — declaration counts by
  visibility (`packageSurface` corresponds to `private[pkg]`).
- `publicMutableSurface`, `protectedMutableSurface`, `packageMutableSurface`,
  `privateMutableSurface` — mutable declaration counts in the same buckets.
- `totalDeclaredSurface` — sum of the four visibility counts.
- `encapsulationRatio` — `publicSurface / totalDeclaredSurface` when the denominator is non-zero;
  otherwise `null`. Lower means more of the declaration surface is encapsulated.
- `publicMutableRatio` — `publicMutableSurface / publicSurface` when public declarations exist;
  otherwise `null`.

### Public-symbol use

`publicSymbols` is omitted when the input graph has no complete symbol-reference index. When
present, each row identifies a declared public symbol and reports `consumerCount` (distinct
source files), `referenceCount` (reference occurrences), and `usageConfidence`. SemanticDB
exports use `semanticdbComplete`; only these complete rows can produce an `unusedPublicSymbol`
finding. Include/exclude and test filters apply to both declaration targets and source-file
references, so consumers outside the selected report surface are not counted. If any SemanticDB
document fails to parse during `export`, the optional indexes are omitted because the remaining
records are partial. An `unusedPublicSymbol` finding's `nextAction` is `inspect-node <scope-id>`,
where `<scope-id>` is the declaring package or file and is valid for the report's detail workflow;
the symbol id itself is not a surface node. Absence of the field never implies that public API is
unused. Compiler-generated SemanticDB symbols without a source definition (such as case-class
`copy`/`apply` methods and Product `_1` accessors), as well as symbols marked `SYNTHETIC`, are
excluded; a source-defined identifier with the same spelling remains eligible.

## Exposed surface

`isExposed`, `ports` and `mutPorts` are resolved **per node by the extraction backend**
(SemanticDB export), using Scala-specific weight rules that never leak into the metrics layer:

- `3` per exposed type/trait/class/object
- `1` per exposed function/method/def/val
- `0.5` per exposed member that belongs to a `sealed` hierarchy (external code cannot add new
  subtypes, so the effective surface is smaller)
- `+1` flat, once per `given`/`implicit` instance (ambiently public via implicit search)
- `1` `mutPort` per exposed `var`, or exposed val/def typed `scala.collection.mutable.*` or
  `scala.Array`

`private[pkg]` and `protected` members are not exposed; class-private members are omitted from
dependency nodes but retained in file declaration counters; `var` setters are accessors and never
count as surface. jdeps data carries no access info, so all its nodes have `ports`/`mutPorts` 0 and
`dependentsPerPublicPort` `null` — a known gap, not silently meaningful.

## Orphans

- `orphans` — node ids with `fanIn == 0 AND fanOut == 0`, sorted. Step 1 of the improvement
  loop: dead-code-removal candidates. JSON retains the complete list; the table bounds it to the
  top 10 unless `--all` is supplied.

The table format is a bounded triage view: findings, cycles, propagators, surface risks, and orphans
each include a shown/total label and display at most 10 rows by default. `--all` is accepted by
`report-packages` and `report-files` only and requests every table row. JSON is always complete.

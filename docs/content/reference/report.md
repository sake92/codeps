---
layout: reference.html
title: Metrics report
description: the codeps report JSON format — findings, cycles, surface, orphans
---

# Metrics report

`codeps report-packages` or `codeps report-files` consumes the [standard JSON export format](/reference/json-input.html) (a file, or stdin via `-`)
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
    { "node": "cache", "fanIn": 3, "fanOut": 2, "ports": 9, "mutPorts": 5, "exposure": 24, "utilization": 0.33, "cycleId": "scc:cache" }
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

`findings` is a complete, deterministic index of actionable diagnostics. Each finding has a stable
`id`, a `kind` (`cycle`, `propagator`, `mutableSurface`, or `structuralUse`), `severity`, and
`subject`, plus human-readable `evidence`, `confidence`, and a `nextAction`. Findings are ranked by
severity, then metric score, then id. They cover every reported SCC, every above-average propagator,
every node with exposed mutable ports, and every node whose structural utilization proxy is below
`1.0`. The last kind is only a graph proxy, not proof that a public symbol is unused.

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
  `notRequested` for the default fast report, `completed` when the configured search finished,
  or `budgetExceeded` when its time or candidate limit was reached. `budgetExceeded` is still a
  successful report result.
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

- `fanIn` / `fanOut` — count of distinct edges in/out. Always derived from the edge list, never
  stored separately.
- `ports` — weighted count of exposed members (see [Exposed surface](#exposed-surface)).
- `mutPorts` — count of exposed **mutable** members: `var`s, or vals/defs typed as a mutable
  collection. A coupling channel that never shows up as a graph edge.
- `exposure` — `ports + mutPorts * 3`; mutable ports weighted 3× because they are a hidden
  channel on top of being exposed at all. Always look at the `ports`/`mutPorts` breakdown, never
  `exposure` alone.
- `utilization` — `fanIn / ports` when `fanIn > 0` and `ports > 0`, else `null`. A `null`
  utilization is meaningful (no consumers), not a 0.
- `cycleId` — the nullable stable SCC id (`scc:` plus the lexicographically smallest member) for
  nodes in a reported cycle; `null` for nodes outside all cycles.

Rows are sorted by `utilization` ascending (nulls last): the most exposed-for-its-use nodes first.

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

`private[pkg]` and `protected` members are not exposed; class-private members are dropped by the
exporter entirely; `var` setters are accessors and never count as surface. jdeps data carries no
access info, so all its nodes have `ports`/`mutPorts` 0 and `utilization` `null` — a known gap,
not silently meaningful.

## Orphans

- `orphans` — node ids with `fanIn == 0 AND fanOut == 0`, sorted. Step 1 of the improvement
  loop: dead-code-removal candidates. JSON retains the complete list; the table bounds it to the
  top 10 unless `--all` is supplied.

The table format is a bounded triage view: findings, cycles, propagators, surface risks, and orphans
each include a shown/total label and display at most 10 rows by default. `--all` is accepted by
`report-packages` and `report-files` only and requests every table row. JSON is always complete.

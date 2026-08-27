---
layout: reference.html
title: Metrics report
description: the codeps report JSON format — cycles, surface, orphans
---

# Metrics report

`codeps report` consumes the [standard JSON export format](/reference/json-input.html) (a file, or stdin via `-`)
and emits a single flat JSON document: per-scope metrics over the graph's **packages**, or over the
**files** of the packages selected with `-i`.

```shell
codeps export --from semanticdb classes/META-INF/semanticdb | codeps report --scope packages --format json - > report.json
```

Every value is computed fresh from the graph's node/edge list on every run — the report is a pure
function of the input, which is what makes diffing reports over time meaningful.

## Structure

```json
{
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
      "members": ["cache", "scheduler", "cache"],
      "size": 2,
      "extFanIn": 5,
      "minCutsEstimate": 1,
      "cutCandidates": [
        { "edge": ["scheduler", "cache"], "weight": 4 }
      ]
    }
  ],
  "surface": [
    { "node": "cache", "fanIn": 3, "fanOut": 2, "ports": 9, "mutPorts": 5, "exposure": 24, "utilization": 0.33 }
  ],
  "orphans": ["DeadUtil.scala"]
}
```

## Summary

- `nodes` / `edges` — size of the scope graph.
- `nodesInCycles` — total members of all cycles (multi-member strongly connected components).
- `orphans` — count of nodes with zero fan-in and zero fan-out (dead-code-removal candidates).
- `criticalPathLength` — the longest path, in edges, through the condensation DAG (each SCC
  collapsed to one node). The structural lower bound on best-case parallel build time, whether
  or not cycles exist.

## Cycles

A cycle is a strongly connected component with more than 1 member (Tarjan's algorithm). Singleton
components are just acyclic nodes and are never reported.

- `id` — `scc:` + the lexicographically smallest member id. Stable across recomputation after cuts
  (a counter-based id would renumber unpredictably).
- `members` — a **closed cycle path** through the smallest member: the first node repeated at the
  end. When the SCC contains several interlocking cycles, `size` may exceed the path length — the
  path shows one of the cycles; `size` counts the full SCC membership.
- `size` — full member count of the SCC (may exceed the path length, see above).
- `extFanIn` — edges whose target is in the SCC and whose source is outside: how much outside
  stuff is exposed to the cycle's blast radius. Cycles are ranked by `size` first, `extFanIn`
  as tiebreaker.
- `cutCandidates` — the cycle's **internal** edges (both endpoints inside the cycle) whose removal
  **resolves the cycle for their endpoints** (the endpoints end up in no multi-member component; a
  leftover cycle elsewhere in the SCC does not count). Each is **simulated**: the edge is removed
  from a copy of the edge list and Tarjan reruns; only edges with effect `resolved` are listed,
  sorted by `weight` ascending, up to 6. Edges that merely shrink the cycle (`partial`) or leave
  it unchanged (`none`) are deliberately not listed — they don't dissolve the cycle.
- `minCutsEstimate` — a greedy estimate of the total cuts needed to dissolve the cycle: repeatedly
  apply the best `resolved`-or-largest-reduction candidate, recompute against the largest remaining
  multi-member component of the cycle, until no such component remains. A heuristic, not a
  guaranteed-minimum feedback-edge set.

## Surface

One row per scope node:

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
  loop: dead-code-removal candidates.

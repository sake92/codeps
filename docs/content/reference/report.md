---
layout: reference.html
title: Metrics report
description: the codeps report JSON format — cycles, surface, orphans
---

# Metrics report

`codeps report` consumes the [common JSON graph](/reference/json-input.html) (a file, or stdin via `-`)
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
  "generated_at": "<ISO8601 UTC, second precision, e.g. 2026-08-27T10:00:00Z>",
  "summary": {
    "nodes": 100,
    "edges": 214,
    "nodes_in_cycles": 34,
    "orphans": 3,
    "critical_path_length": 7
  },
  "cycles": [
    {
      "id": "scc:cache",
      "members": ["cache", "scheduler", "cache"],
      "size": 2,
      "ext_fan_in": 5,
      "min_cuts_estimate": 1,
      "cut_candidates": [
        { "edge": ["scheduler", "cache"], "weight": 4 }
      ]
    }
  ],
  "surface": [
    { "node": "cache", "fan_in": 3, "fan_out": 2, "ports": 9, "mut_ports": 5, "exposure": 24, "utilization": 0.33 }
  ],
  "orphans": ["DeadUtil.scala"]
}
```

## Summary

- `nodes` / `edges` — size of the scope graph.
- `nodes_in_cycles` — total members of all cycles (multi-member strongly connected components).
- `orphans` — count of nodes with zero fan-in and zero fan-out (dead-code-removal candidates).
- `critical_path_length` — the longest path, in edges, through the condensation DAG (each SCC
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
- `ext_fan_in` — edges whose target is in the SCC and whose source is outside: how much outside
  stuff is exposed to the cycle's blast radius. Cycles are ranked by `size` first, `ext_fan_in`
  as tiebreaker.
- `cut_candidates` — the cycle's **internal** edges (both endpoints inside the cycle) whose removal
  **resolves** the whole cycle. Each is **simulated**: the edge is removed from a copy of the edge
  list and Tarjan reruns; only edges whose endpoints end up in no multi-member component
  (`resolved`) are listed, sorted by `weight` ascending, up to 6. Edges that merely shrink the
  cycle (`partial`) or leave it unchanged (`none`) are deliberately not listed — they don't
  dissolve the cycle.
- `min_cuts_estimate` — a greedy estimate of the total cuts needed to dissolve the cycle: repeatedly
  apply the best `resolved`-or-largest-reduction candidate, recompute against the shrunk component,
  until it reaches size 1 or nothing improves. A heuristic, not a guaranteed-minimum feedback-edge
  set.

## Surface

One row per scope node:

- `fan_in` / `fan_out` — count of distinct edges in/out. Always derived from the edge list, never
  stored separately.
- `ports` — weighted count of exposed members (see [Exposed surface](#exposed-surface)).
- `mut_ports` — count of exposed **mutable** members: `var`s, or vals/defs typed as a mutable
  collection. A coupling channel that never shows up as a graph edge.
- `exposure` — `ports + mut_ports * 3`; mutable ports weighted 3× because they are a hidden
  channel on top of being exposed at all. Always look at the `ports`/`mut_ports` breakdown, never
  `exposure` alone.
- `utilization` — `fan_in / ports` when `fan_in > 0` and `ports > 0`, else `null`. A `null`
  utilization is meaningful (no consumers), not a 0.

Rows are sorted by `utilization` ascending (nulls last): the most exposed-for-its-use nodes first.

## Exposed surface

`is_exposed`, `ports` and `mut_ports` are resolved **per node by the extraction backend**
(SemanticDB export), using Scala-specific weight rules that never leak into the metrics layer:

- `3` per exposed type/trait/class/object
- `1` per exposed function/method/def/val
- `0.5` per exposed member that belongs to a `sealed` hierarchy (external code cannot add new
  subtypes, so the effective surface is smaller)
- `+1` flat, once per `given`/`implicit` instance (ambiently public via implicit search)
- `1` `mut_port` per exposed `var`, or exposed val/def typed `scala.collection.mutable.*` or
  `scala.Array`

`private[pkg]` and `protected` members are not exposed; class-private members are dropped by the
exporter entirely; `var` setters are accessors and never count as surface. jdeps data carries no
access info, so all its nodes have `ports`/`mut_ports` 0 and `utilization` `null` — a known gap,
not silently meaningful.

## Orphans

- `orphans` — node ids with `fan_in == 0 AND fan_out == 0`, sorted. Step 1 of the improvement
  loop: dead-code-removal candidates.

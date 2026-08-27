---
layout: reference.html
title: Metrics report
description: the codeps report JSON format — knots, surface, orphans, articulation points
---

# Metrics report

`codeps report` consumes the [common JSON graph](/reference/json-input.html) (a file, or stdin via `-`)
and emits a single flat JSON document: per-scope metrics over the graph's **packages**, or over the
**files** of the packages selected with `-i`.

```shell
codeps export --from semanticdb classes/META-INF/semanticdb | codeps report --scope packages - > report.json
```

Every value is computed fresh from the graph's node/edge list on every run — the report is a pure
function of the input, which is what makes diffing reports over time meaningful.

## Structure

```json
{
  "scope": "packages",
  "generated_at": "<ISO8601>",
  "summary": {
    "nodes": 100,
    "edges": 214,
    "nodes_in_cycles": 34,
    "orphans": 3,
    "critical_path_length": 7
  },
  "knots": [
    {
      "id": "scc:cache",
      "members": ["cache", "scheduler"],
      "size": 2,
      "ext_fan_in": 5,
      "min_cuts_estimate": 1,
      "cut_candidates": [
        { "edge": ["scheduler", "cache"], "weight": 4, "effect": "resolved", "new_size": 1 }
      ]
    }
  ],
  "surface": [
    { "node": "cache", "fan_in": 3, "fan_out": 2, "ports": 9, "mut_ports": 5, "exposure": 24, "utilization": 0.33 }
  ],
  "orphans": ["DeadUtil.scala"],
  "articulation_points": ["config", "core"]
}
```

## Summary

- `nodes` / `edges` — size of the scope graph.
- `nodes_in_cycles` — total members of all knots (multi-member strongly connected components).
- `orphans` — count of nodes with zero fan-in and zero fan-out (dead-code-removal candidates).
- `critical_path_length` — the longest path, in edges, through the condensation DAG (each SCC
  collapsed to one node). The structural lower bound on best-case parallel build time, whether
  or not cycles exist.

## Knots (cycles)

A knot is a strongly connected component with more than 1 member (Tarjan's algorithm). Singleton
components are just acyclic nodes and are never reported.

- `id` — `scc:` + the lexicographically smallest member id. Stable across recomputation after cuts
  (a counter-based id would renumber unpredictably).
- `size` — member count.
- `ext_fan_in` — edges whose target is in the SCC and whose source is outside: how much outside
  stuff is exposed to the cycle's blast radius. Knots are ranked by `size` first, `ext_fan_in`
  as tiebreaker.
- `cut_candidates` — the knot's **internal** edges (both endpoints inside the cycle) with the
  lowest `weight` (call-site count from SemanticDB), up to 6. Each candidate is **simulated**:
  the edge is removed from a copy of the edge list, Tarjan reruns, and the effect on the
  component containing the edge's endpoints is classified:
  - `resolved` — the endpoints are no longer in any multi-member component;
  - `partial` — a smaller multi-member component remains (`new_size`);
  - `none` — the component is unchanged: the edge is redundant with the rest of the cycle
    (e.g. a chord running the ring's direction). Reported as-is — a naive
    "lowest weight = best cut" heuristic gets this wrong.
- `min_cuts_estimate` — a greedy estimate: repeatedly apply the best `resolved`-or-largest-reduction
  candidate, recompute against the shrunk component, until it reaches size 1 or nothing improves.
  A heuristic, not a guaranteed-minimum feedback-edge set.

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

## Orphans and articulation points

- `orphans` — node ids with `fan_in == 0 AND fan_out == 0`, sorted. Step 1 of the improvement
  loop: dead-code-removal candidates.
- `articulation_points` — nodes whose removal increases the number of connected components of the
  **undirected** view of the graph (standard low-link DFS). Candidate "pinch points": the narrowest
  waists in the dependency graph, useful as seam locations whether or not they are in a cycle.

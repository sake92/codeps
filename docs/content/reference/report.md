---
layout: reference.html
title: Cycle analysis report
description: the codeps report JSON format, cycle grades and suggestions
---

# Cycle analysis report

`codeps report` runs the analysis pipeline at every granularity in one pass and emits a
single, self-contained JSON document: per-level graphs, cycle grades, metrics and
suggestions. It is the input for the [interactive demo](/demo/cytoscape-graph.html)'s
report mode (switch to its Matrix tab for the DSM view), and is shaped for agents to
consume directly — no further computation needed.

```shell
codeps export --from semanticdb classes/META-INF/semanticdb | codeps report - > report.json
```

## Structure

```json
{
  "levels": {
    "package": { "graph": {...}, "metrics": {...}, "cycles": [...], "suggestions": {...} },
    "file":    { "graph": {...}, "metrics": {...}, "cycles": [...], "suggestions": {...} },
    "type":    { "graph": {...}, "metrics": {...}, "cycles": [...], "suggestions": {...} },
    "member":  { "graph": null,  "metrics": null,  "cycles": [...], "suggestions": {...} }
  }
}
```

- **`graph`** — the nodes/edges of the level (same shape as the common JSON format),
  embedded for package/file/type. The member-level graph is too large to embed usefully
  (`null`); its cycles are still reported. Edges carry a `weight`: the number of
  finer-grained references merged into the edge (see the [common JSON format](/reference/json-input.html)).
- **`metrics`** — per-node `{in, out, hub}` counts (`hub = in × out`), package/file/type only.
- **`cycles`** — see below.
- **`suggestions`** — see below.

## Cycles and grades

Each cycle:

```json
{
  "members": ["com.example.a", "com.example.b", "com.example.a"],
  "edges":   [{"source": "com.example.a", "target": "com.example.b", "weight": 1},
              {"source": "com.example.b", "target": "com.example.a", "weight": 1}],
  "severity": "bad",
  "breakCandidate": "com.example.a"
}
```

- `members` — the cycle in true dependency order (last = first).
- `edges` — the cycle's edges, consecutive pairs of `members`.
- `severity` — one of:
  | Grade | Meaning | Applies to |
  |---|---|---|
  | `bad` | should be broken | package cycles |
  | `meh` | worth a look | file cycles; type/member cycles that cross files or whose file is unknown (jdeps input) |
  | `fine` | normal, leave it | type/member cycles entirely within one file |
- `breakCandidate` — the cycle member with the lowest degree in the graph: the least
  costly one to detach if you want to break the cycle.

Cycles are detected as strongly connected components of size ≥ 2; one representative
elementary cycle is reported per component (direct self-recursion is not a cycle).

## Suggestions

```json
{
  "breakEdges":  [{"edge": {"source": "com.example.a", "target": "com.example.b", "weight": 1}, "breaks": 1}],
  "hardestKnots": ["com.example.modules.module2"],
  "easyWins":     ["com.example.modules.module2", "com.example.app"]
}
```

- `breakEdges` — every edge that participates in a reported cycle, ranked by how many
  cycles it breaks (with one representative cycle per component this is usually `1`) —
  the concrete cut list.
- `hardestKnots` — packages that are both heavily used and heavily using
  (highest `in × out`), top 5. Package level only.
- `easyWins` — packages with the most outgoing dependencies, fewest dependents as
  tiebreak, top 5. Package level only.

## Private symbols

`codeps export --from semanticdb` skips class-scoped private symbols (`private`,
`private[this]`): they can never create cross-package dependencies, so they only add
noise at the type/member levels. References inside them are attributed to the nearest
non-private ancestor (enclosing type, else file/package), so package- and file-level
results are unaffected. Package-private (`private[pkg]`) and `protected` symbols are kept.

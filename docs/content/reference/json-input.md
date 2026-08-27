---
layout: reference.html
title: Standard JSON export format
description: the standard JSON graph format produced by codeps export and consumed by codeps report
---

# Standard JSON export format

The standard JSON export format is the contract between the two codeps steps: `codeps export`
*produces* it, `codeps report` *consumes* it. It is a self-contained dependency
graph produced by `codeps export` as `package` and `file` nodes only — the exporters
collapse type/member symbols into their file (or root package for file-less jdeps
types), with `ports`/`mutPorts` summed and edges aggregated at file level with
summed weights. `type`/`member` kinds are still *accepted* on input for backward
compatibility with old graphs (the analyzer aggregates them), but exporters never
emit them.

```json
{
  "nodes": [
    {"id": "com.example.a", "kind": "package"},
    {"id": "src/com/example/a/Foo.scala", "kind": "file", "parentId": "com.example.a", "ports": 3, "mutPorts": 1}
  ],
  "edges": [
    {"source": "src/com/example/a/Foo.scala", "target": "src/com/example/b/Bar.scala", "weight": 5}
  ]
}
```

## Schema

| Field | Type | Meaning |
|---|---|---|
| `nodes` | `[{id, kind, parentId?, file?}]` | the graph's nodes; a set, so each `id` appears once |
| `edges` | `[{source, target, weight}]` | directed dependency edges between node ids; a set (deduplicated); `weight` = number of finer-grained references merged into the edge |

Node fields:

| Field | Type | Meaning |
|---|---|---|
| `id` | string | hierarchical id, unique within the graph |
| `kind` | string | `package` or `file`; `type`/`member` (deprecated — accepted on input for backward compatibility, never emitted by `export`) |
| `parentId` | string (optional) | nearest enclosing node; on `file` nodes: their root package; `package` nodes are standalone (no `parentId`) |
| `file` | string (optional) | on `type`/`member` nodes: the id of their source `file` node |
| `isExposed` | boolean (optional) | part of the externally visible surface; resolved by the extraction backend (SemanticDB export), default `true` for graphs without exposure info |
| `ports` | number (optional) | the node's own weighted exposure contribution (types 3, defs/vals 1, sealed-hierarchy members 0.5, givens/implicits +1); the report sums these per scope node, default `0` |
| `mutPorts` | number (optional) | the node's own mutable-state exposure contribution (`var`s, mutable-collection-typed vals/defs), default `0` |

The exposure weight rules are Scala-specific and live in the SemanticDB exporter — see the
[Metrics report](/reference/report.html) for the definitions. A jdeps graph carries no access
info, so its nodes are all `isExposed: true` with `ports`/`mutPorts` 0.

Edge fields:

| Field | Type | Meaning |
|---|---|---|
| `source` | string | node id the dependency comes from |
| `target` | string | node id the dependency goes to |
| `weight` | number (optional) | number of finer-grained references this edge represents; 1 when absent (producers that don't emit it are accepted) |

### Id rules

- **package** — dotted name: `com.example.a`. Standalone (no `parentId`).
- **file** — workspace-relative path: `src/com/example/a/Foo.scala`. `parentId` is the
  root package (lexicographically smallest when a file hosts several packages).
- **type** (deprecated on input) — `com.example.a.Foo`; nested types use `#`: `com.example.a.Outer#Inner`.
  `parentId` is the enclosing type or the package.
- **member** (deprecated on input) — `com.example.a.Foo#doWork` (a type member; `parentId` = the type) or
  `com.example.a.topLevelHelper` (a package member; `parentId` = the package, no `#`).

`export` collapses type/member symbols into their file node (or root package for
file-less jdeps types), sums their `ports`/`mutPorts`, and aggregates edges at
file level with summed weights — so the graphs it emits only ever contain
package and file nodes. The granular package → type → member hierarchy remains
the *internal* parser contract; the analyzer aggregates such old graphs the same
way `export` does.

On granular (pre-aggregation) input, the hierarchy is only package → type → member,
plus member → package directly; edges can go between nodes of any kinds, e.g. a
member of one type referencing a member of another. On `export` output (package +
file nodes), edges always go between files or packages.

Only the project's own symbols appear: references to external symbols are dropped
by the producers. The format has no `own` array, no `stats` and no version field;
unknown node kinds or fields are rejected with a helpful parse error, e.g.:

```text
error: failed to parse json: ...
```

## Producing it

The easiest way to see the exact shape is to run the exporter:

```shell
codeps export --from semanticdb classes/META-INF/semanticdb -o deps.json
```

External tools can emit nodes+edges JSON directly (matching the schema above)
and pipe it into `codeps report --scope packages ... -`. The examples below are sketches;
adapt them to your tool's output. The key points: give every node a unique `id` and a
valid `kind`, and reference only existing ids in `edges`.

JavaScript/TypeScript (file-level deps from [madge](https://github.com/pahen/madge)
mapped to file/package nodes, e.g. by tsconfig `rootDir`):

```shell
madge --json src | jq '...' | codeps report --scope files -
```

Python (module-level deps from [pydeps](https://github.com/thebjorn/pydeps)):

```shell
pydeps --json mypackage | jq '...' | codeps report --scope packages -
```

Go (`go list` already emits package-level import deps):

```shell
go list -json ./... | jq '...' | codeps report --scope packages -
```

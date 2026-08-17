---
layout: reference.html
title: Common JSON format
description: the common JSON graph format produced by codeps export and consumed by codeps draw
---

# Common JSON format

The common JSON format is the contract between the two codeps steps: `codeps export`
*produces* it, `codeps draw` *consumes* it. It is a self-contained dependency
graph with `package`/`file`/`type`/`member` nodes and directed edges between node ids.

```json
{
  "nodes": [
    {"id": "com.example.a", "kind": "package"},
    {"id": "src/com/example/a/Foo.scala", "kind": "file"},
    {"id": "com.example.a.Foo", "kind": "type", "parentId": "com.example.a", "file": "src/com/example/a/Foo.scala"},
    {"id": "com.example.a.Foo#doWork", "kind": "member", "parentId": "com.example.a.Foo", "file": "src/com/example/a/Foo.scala"},
    {"id": "com.example.a.topLevelHelper", "kind": "member", "parentId": "com.example.a"}
  ],
  "edges": [
    {"source": "com.example.a.Foo#doWork", "target": "com.example.a.topLevelHelper", "weight": 1}
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
| `kind` | string | `package`, `file`, `type` or `member` (lowercase) |
| `parentId` | string (optional) | nearest enclosing node; `package` and `file` nodes are standalone (no `parentId`) |
| `file` | string (optional) | on `type`/`member` nodes: the id of their source `file` node |

Edge fields:

| Field | Type | Meaning |
|---|---|---|
| `source` | string | node id the dependency comes from |
| `target` | string | node id the dependency goes to |
| `weight` | number (optional) | number of finer-grained references this edge represents; 1 when absent (producers that don't emit it are accepted) |

### Id rules

- **package** — dotted name: `com.example.a`. Standalone (no `parentId`).
- **file** — workspace-relative path: `src/com/example/a/Foo.scala`. Standalone (no `parentId`).
- **type** — `com.example.a.Foo`; nested types use `#`: `com.example.a.Outer#Inner`.
  `parentId` is the enclosing type or the package.
- **member** — `com.example.a.Foo#doWork` (a type member; `parentId` = the type) or
  `com.example.a.topLevelHelper` (a package member; `parentId` = the package, no `#`).

The hierarchy is only package → type → member, plus member → package directly.
Edges can go between nodes of any kinds, e.g. a member of one type referencing a
member of another.

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
and pipe it into `codeps draw ... -`. The examples below are sketches; adapt
them to your tool's output. The key points: give every node a unique `id` and a
valid `kind`, and reference only existing ids in `edges`.

JavaScript/TypeScript (file-level deps from [madge](https://github.com/pahen/madge)
mapped to file/package nodes, e.g. by tsconfig `rootDir`):

```shell
madge --json src | jq '...' | codeps draw -g file -f mermaid -
```

Python (module-level deps from [pydeps](https://github.com/thebjorn/pydeps)):

```shell
pydeps --json mypackage | jq '...' | codeps draw -g package -f dot -
```

Go (`go list` already emits package-level import deps):

```shell
go list -json ./... | jq '...' | codeps draw -g package -f dot -
```

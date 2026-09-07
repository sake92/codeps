---
layout: reference.html
title: Codeps export format
description: "the codeps export format, produced internally by the semanticdb/jdeps parsers and consumable directly via source: export"
---

# Codeps export format

The codeps export format is the data contract behind every `source: semanticdb` or
`source: jdeps` parse, and is also directly consumable via `source: export`. It has two
independent, materialized graphs: `packages` and `files`. codeps analyzes both when file data is
available. Each graph contains only the ids, summary values, and
edges needed for that scope. It never includes declarations, members, types, or symbol
references.

```json
{
  "packages": {
    "nodes": [
      {"id": "com.example.a", "ports": 3, "mutPorts": 1,
       "declarationSurface": {"public": 1, "privateMembers": 2}}
    ],
    "edges": [{"source": "com.example.a", "target": "com.example.b", "weight": 5}]
  },
  "files": {
    "nodes": [
      {"id": "src/com/example/a/Foo.scala", "packageId": "com.example.a", "ports": 3, "mutPorts": 1,
       "declarationSurface": {"public": 1, "privateMembers": 2}}
    ],
    "edges": [{"source": "src/com/example/a/Foo.scala", "target": "src/com/example/b/Bar.scala", "weight": 5}]
  }
}
```

## Schema

| Field | Type | Meaning |
|---|---|---|
| `packages` | `{nodes, edges}` | package-level graph |
| `files` | `{nodes, edges}` | file-level graph |
| `packages.nodes` | `[{id, ports, mutPorts, declarationSurface}]` | one summary per package |
| `files.nodes` | `[{id, packageId?, ports, mutPorts, declarationSurface}]` | one summary per source file; `packageId` supports package filtering |
| `*.edges` | `[{source, target, weight}]` | directed edges whose endpoints belong to the same graph |

`ports` and `mutPorts` are numbers. `declarationSurface` is an object with optional
integer fields: `public`, `protected`, `packageRestricted`, `privateMembers`,
`publicMutable`, `protectedMutable`, `packageRestrictedMutable`, and `privateMutable`.
Missing declaration fields are zero. An omitted `weight` is `1`.

The parser derives both graphs from the same input projection. Package node summaries
include the summaries of their files; package edges aggregate inter-package file edges and
drop intra-package self-edges. The file graph retains file-to-file edges. jdeps has no
source-file data, so its `files` graph is empty and its package summaries have zero surface
values.

Ids are dotted package names (`com.example.a`) and workspace-relative source paths
(`src/com/example/a/Foo.scala`). Only project-owned dependencies appear; external symbols
are dropped by the parsers.

This is a breaking schema: `source: export` accepts this split shape only.

## Producing it

Use this format directly by pointing a project at a pre-built export JSON file:

```yaml
projects:
  app:
    root: .
    source: export
    inputs: [deps.json]
```

```shell
java -jar codeps.jar status
```

External tools should emit both scopes. A package-only tool such as `go list` or `pydeps`
can emit package nodes and edges with an empty file graph; a file-level tool can emit both
when it can map files to packages.

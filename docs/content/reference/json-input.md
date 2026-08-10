---
layout: reference.html
title: JSON input format
description: the common JSON input format consumed by the codeps json subcommand
---

# JSON input format

The `json` subcommand reads package-level dependency info from a JSON document.
It is the only input format codeps consumes that you produce yourself — with any
tool you like, in any language. Codeps applies its usual pipeline
(filter, collapse, export) on top.

The format is also what `-f raw` emits from the `semdb`/`jdeps` subcommands, so
analyses can be saved and re-run.

## Schema

```json
{
  "own": ["com.example.a", "com.example.b"],
  "edges": [
    {"source": "com.example.a", "target": "com.example.b"}
  ],
  "stats": {
    "com.example.a": {"fileCount": 3, "classCount": 5}
  }
}
```

| Field | Type | Required | Meaning |
|---|---|---|---|
| `own` | `string[]` | no* | the project's own packages; the basis for include/exclude filtering |
| `edges` | `[{source, target}]` | no* | package-level dependency edges; endpoints may be outside `own` |
| `stats` | `{pkg: {fileCount, classCount}}` | no | per-package metadata, shown as `nodeInfo` in JSON export; omit when your tool has no stats |

\* `own` and `edges` default to empty when missing. `fileCount` and `classCount`
are required integers in every `stats` entry.

Unknown keys are ignored. Type errors and missing required fields fail with a
helpful path, e.g.:

```text
error: failed to parse json: Key '$.edges' expected array, but got string
```

## Producing it

Codeps does not ship converters — the point is that *you* produce the JSON with
the dependency tool of your ecosystem. The examples below are sketches; adapt
them to your tool's output.

JavaScript/TypeScript (file-level deps from [madge](https://github.com/pahen/madge)
mapped to packages, e.g. by tsconfig `rootDir`):

```shell
madge --json src | jq '
  to_entries
  | { own: [.[].key | split("/")[0:2] | join(".")] }
  ...
' | java -jar codeps.jar json - -i src -f mermaid
```

Python (module-level deps from [pydeps](https://github.com/thebjorn/pydeps)):

```shell
pydeps --json mypackage | jq '...' | java -jar codeps.jar json - -f dot
```

Go (`go list` already emits package-level import deps):

```shell
go list -json ./... | jq '...' | java -jar codeps.jar json - -f json
```

The easiest way to see the exact shape is to round-trip an existing analysis:

```shell
java -jar codeps.jar semdb classes/META-INF/semanticdb -i com.example -f raw
```

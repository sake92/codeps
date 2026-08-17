---
layout: reference.html
title: CLI
description: codeps CLI reference
---

# CLI

`codeps` is a single binary/entry point (`ba.sake.codeps.cli.Main`) with three subcommands
that form a two-step pipeline:

1. [`export`](#export) — the *producer*: parses raw input (`semanticdb` or `jdeps`) and
   emits the [common JSON graph format](/reference/json-input.html). No analysis.
2. [`draw`](#draw) — the *renderer*: consumes that JSON (a file or stdin) and renders
   dot or mermaid at any granularity.
3. [`report`](#report) — the *analyzer*: consumes that JSON and emits a multi-level
   [cycle analysis report](/reference/report.html).

Download the prebuilt jar (requires a JDK, 11+) and run it with `java -jar`:

```shell
curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar
java -jar codeps.jar <subcommand> [options] <inputs...>
```

The examples below use `codeps` as shorthand for `java -jar codeps.jar`.

The steps pipe together — `export` writes the graph to stdout, `draw`/`report` read it from stdin (`-`):

```shell
codeps export --from jdeps jdeps.txt | codeps draw -g type -f dot -
```

## export

```shell
codeps export --from semanticdb|jdeps [--root <dir>] [-o out] <inputs...>
```

Pure producer: parses the raw input and emits the common JSON graph
(`{"nodes": [...], "edges": [...]}`) to stdout, or to the `-o` file.
There are no include/exclude/collapse flags here — filtering and aggregation are
the analyzer's job.

| Option | Description |
|---|---|
| `--from` (`-f`) | Input format: `semanticdb` or `jdeps`. Required. |
| `--root` | semanticdb only. Makes source URIs relative to this directory (default: the current working directory). |
| `-o` / `--out` | Write the JSON to this file instead of stdout. |

Inputs:

- `semanticdb` — **directories**, walked recursively for `*.semanticdb` files.
- `jdeps` — **files** containing `jdeps -verbose:class` text output.

```shell
codeps export --from semanticdb classes/META-INF/semanticdb -o deps.json
codeps export --from jdeps jdeps.txt
```

Errors (exit 1): `at least one input is required`, `input path does not exist: <path>`,
`not a directory: <path>` (semanticdb), `not a file: <path>` (jdeps),
`no .semanticdb files found`. Per-file parse failures are warnings on stderr and
the run continues:

```text
warning: failed to parse semanticdb: ...
```

## draw

```shell
codeps draw -g <package|file|type|member> -f <dot|mermaid> [-i inc] [-e exc] [-c collapse] [-o out] <file|->
```

Pure renderer: reads the common JSON graph (a file, or stdin via `-`) and renders it
in the requested format at the requested granularity. Pipeline:
parse → filter → aggregate to `-g` → collapse → build graph → cycle detection → render.

`-g` is required — there is no default granularity.

| Option | Description |
|---|---|
| `-g` / `--granularity` | Aggregation level: `package`, `file`, `type` or `member`. Required. |
| `-f` / `--format` | Output format: `dot` or `mermaid`. Required. |
| `-i` / `--include` | Package pattern; keep only nodes whose root package matches it. Repeatable. A pattern `ba.sake` matches `ba.sake` and everything below it. |
| `-e` / `--exclude` | Package pattern; drop nodes whose root package matches it. Excludes win over includes. Repeatable. |
| `-c` / `--collapse` | Collapse rule, e.g. `com.example.**`. Repeatable. |
| `-o` / `--out` | Write output to this file instead of stdout. |

Exactly one input is required — a JSON file, or `-` for stdin, so `export` output
can be piped straight into `draw`:

```shell
codeps draw -g package -f dot deps.json
codeps export --from semanticdb classes/META-INF/semanticdb | codeps draw -g type -f mermaid -
codeps export --from jdeps jdeps.txt | codeps draw -g type -f dot -
```

### Granularity

The `-g` level says what the graph's nodes are after aggregation. Finer-grained
nodes are lifted to their nearest ancestor at that level; when the data has no
nodes at the requested level (or above it), the level falls back:

| `-g` | semanticdb data | jdeps data |
|---|---|---|
| `member` | identity (types, members, packages, files) | identity (types only) |
| `type` | members → their type (package members → the package); files and package nodes dropped | types only (package nodes dropped) |
| `file` | types/members → their `file` attribute; fallback: root package; package nodes dropped | root package (jdeps has no files) |
| `package` | everything → root package; files dropped | everything → root package |

Nodes coarser than the requested level are dropped: file nodes at `type`/`package`,
package nodes at `type`/`file`. A package can still appear at a finer level as a
fallback — members whose parent is a package map to that package at `-g type`, and
file-less nodes map to their root package at `-g file` (jdeps data has no file or
member nodes, so on jdeps data `-g member` is the identity, `-g type` keeps the
types, and `-g file` behaves like `-g package`).

Errors (exit 1): `exactly one input is required (a json file, or '-' for stdin)`,
`expected exactly one input (a json file, or '-' for stdin)`,
`input path does not exist: <path>`, `no nodes remain after filtering`, invalid
collapse rules, unknown granularity/format values — and malformed or type-invalid
JSON is a hard error, not a warning (see [Exit codes](#exit-codes-and-errors)).

## Include / exclude patterns

A pattern matches a package if the package equals the pattern or starts with
`pattern + "."`. Include/exclude applies to each node's **root package** — the
topmost package ancestor found by walking `parentId` chains (for a package node,
that is the package itself). A node is kept when it has no include patterns or
its root package matches one, and its root package matches no exclude pattern
(excludes win). An edge is kept only when **both** its endpoints are in the
resulting universe (self-edges are dropped). With no `-i`, all nodes are kept.

```shell
# keep only com.example and subpackages, minus internal helpers
codeps draw -g package -f dot -i com.example -e com.example.internal deps.json
```

## Collapse rules

Collapse rules merge nodes into a single node, making large graphs readable.
Only trailing wildcards are supported:

| Rule | Effect |
|---|---|
| `com.example.**` | everything equal to or below `com.example` collapses into `com.example` |
| `org.lib.*` | nodes directly below `org.lib` collapse into `org.lib.<level>` (one level only) |

Rules match node ids by prefix, kind-agnostically — they apply to whatever ids
exist after aggregation (packages, files, types or members). When multiple rules
match a node, the **longest prefix wins** (ties: first rule in the sequence).
Loops created by collapsing are dropped; edges are deduplicated.

```shell
codeps draw -g package -f dot -i com.example -c com.example.modules.** deps.json
```

## report

```shell
codeps report [-i inc] [-e exc] [-c collapse] [-o out] <file|->
```

Pure analyzer: reads the common JSON graph (a file, or stdin via `-`) and runs the
pipeline (filter → aggregate → collapse → build graph → cycle detection) at **all four
granularities in one pass**, grading every cycle and computing metrics and suggestions.
Emits one self-contained JSON document (see [Cycle analysis report](/reference/report.html)),
always exits `0` on success.

| Option | Description |
|---|---|
| `-i` / `--include` | Same as `draw` — applied at every granularity. |
| `-e` / `--exclude` | Same as `draw`. |
| `-c` / `--collapse` | Same as `draw`. |
| `-o` / `--out` | Write the report JSON to this file instead of stdout. |

There is no `-g`: the report covers package, file, type and member levels at once.

```shell
codeps report deps.json -o report.json
codeps export --from semanticdb classes/META-INF/semanticdb | codeps report - > report.json
```

The report JSON is the input for the [interactive demo](/demo/cytoscape-graph.html)'s
report mode (cycle highlighting, severity grades, suggestions) and is shaped for agents
to consume directly.

## Exit codes and errors

- `0` — success
- `1` — usage errors, input errors (missing/nonexistent paths, empty input) or JSON parse errors

For `export`, unparseable input files produce a warning on stderr and are skipped,
so a partial run still succeeds:

```text
warning: failed to parse semanticdb: ...
```

For `draw`, malformed or type-invalid JSON input is a hard error (exit 1):

```text
error: failed to parse json: ...
```

## Building from source

For developing codeps itself, the repo is built with [deder](https://sake92.github.io/deder/) —
see the [README](https://github.com/sake92/codeps#development):

```shell
deder exec -t run -m cli export --from semanticdb <dir> -o deps.json
deder exec -t run -m cli draw -g package -f dot deps.json
```

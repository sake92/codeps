---
layout: reference.html
title: CLI
description: codeps CLI reference
---

# CLI

`codeps` is a single binary/entry point (`ba.sake.codeps.cli.Main`) with two subcommands
that form a two-step pipeline:

1. [`export`](#export) — the *producer*: parses raw input (`semanticdb` or `jdeps`) and
   emits the [common JSON graph format](/reference/json-input.html). No analysis.
2. [`report`](#report) — the *analyzer*: consumes that JSON (a file or stdin) and emits the
   flat [metrics report](/reference/report.html): knots with simulated cut candidates,
   per-node exposed-surface metrics, orphans and articulation points.

Download the prebuilt jar (requires a JDK, 11+) and run it with `java -jar`:

```shell
curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar
java -jar codeps.jar <subcommand> [options] <inputs...>
```

The examples below use `codeps` as shorthand for `java -jar codeps.jar`.

The steps pipe together — `export` writes the graph to stdout, `report` reads it from stdin (`-`):

```shell
codeps export --from semanticdb classes/META-INF/semanticdb | codeps report --scope packages -
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

## report

```shell
codeps report --scope <packages|files> [--format <json|table>] [-i inc] [-e exc] [-c collapse] [--skip-tests] [-o out] <file|->
```

Pure analyzer: reads the common JSON graph (a file, or stdin via `-`) and runs the pipeline
(filter → skip-tests? → aggregate to the scope → collapse → metrics) in one pass. Emits the
flat [Metrics report](/reference/report.html), always exits `0` on success.

`--scope` is required: `packages` analyzes the whole package graph; `files` analyzes the file
graph of the packages selected with `-i` (jdeps data has no file-level info and errors here).

| Option | Description |
|---|---|
| `-s` / `--scope` | `packages` or `files`. Required. |
| `-f` / `--format` | `json` (default) or `table` — the table renders the exact same data as plain aligned text, nothing is recomputed. |
| `-i` / `--include` | Package pattern; keep only nodes whose root package matches it. Repeatable. A pattern `ba.sake` matches `ba.sake` and everything below it. |
| `-e` / `--exclude` | Package pattern; drop nodes whose root package matches it. Excludes win over includes. Repeatable. |
| `-c` / `--collapse` | Collapse rule, e.g. `com.example.**`. Repeatable. |
| `--skip-tests` | Exclude nodes defined in test files (see [Skip tests](#skip-tests)). |
| `--test-pattern` | Glob matching test files; repeatable. Requires `--skip-tests`; replaces the built-in patterns. |
| `-o` / `--out` | Write the report to this file instead of stdout. |

Exactly one input is required — a JSON file, or `-` for stdin, so `export` output
can be piped straight into `report`:

```shell
codeps report --scope packages deps.json -o report.json
codeps export --from semanticdb classes/META-INF/semanticdb | codeps report --scope files -i com.example - > files.json
codeps export --from jdeps jdeps.txt | codeps report --scope packages --format table -
```

The `table` format:

```text
scope: packages    generated_at: 2026-08-27T10:00:00+02:00

Summary
  nodes: 100    edges: 214    nodes_in_cycles: 34    orphans: 3    critical_path_length: 7

Knots (size desc, ext_fan_in desc)
  id           size  ext_fan_in  min_cuts_estimate  cut candidates
  scc:cache    2     5           1                  scheduler -> cache (w=4, resolved -> 1)

Surface (utilization asc; — = no fan-in)
  node     fan_in  fan_out  ports  mut_ports  exposure  utilization
  cache    3       2        9      5          24        0.33
  ...

Orphans
  DeadUtil.scala

Articulation points
  config, core
```

Errors (exit 1): `exactly one input is required (a json file, or '-' for stdin)`,
`expected exactly one input (a json file, or '-' for stdin)`,
`input path does not exist: <path>`, `no nodes remain after filtering`,
`no file nodes found in the input (jdeps data has no file-level info)`, invalid
collapse rules, unknown scope/format values — and malformed or type-invalid
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
# only com.example packages, minus internal helpers
codeps report --scope packages -i com.example -e com.example.internal deps.json

# file scope: descend into one package
codeps report --scope files -i com.example.modules.module1 deps.json
```

## Skip tests

`--skip-tests` (on `report`) excludes nodes defined in test files: `file`
nodes whose id matches a pattern, and `type`/`member` nodes whose `file` attribute
matches. Package nodes and file-less nodes (all of jdeps data) never match, so on
jdeps data the flag is a no-op. Edges with an excluded endpoint are dropped, and
packages left without children are pruned.

Built-in patterns:

| Pattern | Catches |
|---|---|
| `**/test/**` | sbt/maven/mill/deder layouts: `src/test/…`, `modules/x/test/src/…` |
| `**/*.test.scala` | scala-cli test scope |
| `**/*Spec.scala`, `**/*Test.scala`, `**/*Tests.scala`, `**/*Suite.scala` | Scala naming conventions |
| `**/*Spec.java`, `**/*Test.java`, `**/*Tests.java`, `**/*Suite.java` | Java naming conventions |

Glob syntax: `**/` matches zero or more whole path segments, `*` does not cross `/`,
`?` matches one non-`/` character.

`--test-pattern <glob>` (repeatable) **replaces** the built-in patterns — use it to
escape false positives (e.g. a main `*Spec.scala` DSL file) or to cover exotic
layouts. Passing it without `--skip-tests` is an error.

```shell
codeps report --scope packages --skip-tests deps.json
codeps report --scope files --skip-tests --test-pattern '**/specs/**' deps.json
```

## Collapse rules

Collapse rules merge nodes into a single node, keeping big graphs readable.
Only trailing wildcards are supported:

| Rule | Effect |
|---|---|
| `com.example.**` | everything equal to or below `com.example` collapses into `com.example` |
| `org.lib.*` | nodes directly below `org.lib` collapse into `org.lib.<level>` (one level only) |

Rules match node ids by prefix, kind-agnostically — they apply to whatever ids
exist after aggregation (packages, or files — `src.**` collapses `src/one/A.scala`
into `src`). When multiple rules match a node, the **longest prefix wins** (ties:
first rule in the sequence). Loops created by collapsing are dropped; edges landing
on the same pair are merged with summed weights.

```shell
codeps report --scope packages -c com.example.modules.** deps.json
```

## Exit codes and errors

- `0` — success
- `1` — usage errors, input errors (missing/nonexistent paths, empty input) or JSON parse errors

For `export`, unparseable input files produce a warning on stderr and are skipped,
so a partial run still succeeds:

```text
warning: failed to parse semanticdb: ...
```

For `report`, malformed or type-invalid JSON input is a hard error (exit 1):

```text
error: failed to parse json: ...
```

## Building from source

For developing codeps itself, the repo is built with [deder](https://sake92.github.io/deder/) —
see the [README](https://github.com/sake92/codeps#development):

```shell
deder exec -t run -m cli export --from semanticdb <dir> -o deps.json
deder exec -t run -m cli report --scope packages deps.json
```

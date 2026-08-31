---
layout: reference.html
title: CLI
description: codeps CLI reference
---

# CLI

`codeps` is a single binary/entry point (`ba.sake.codeps.cli.Main`) with two subcommands
that form a two-step pipeline:

1. [`export`](#export) — the *producer*: parses raw input (`semanticdb` or `jdeps`) and
   emits the [standard JSON export format](/reference/json-input.html). No analysis.
2. [`report`](#report) — the *analyzer*: consumes that JSON (a file or stdin) and emits the
   flat [metrics report](/reference/report.html): cycles with cut solutions, change
   propagators, per-node exposed-surface metrics and orphans.

Download the prebuilt jar (requires a JDK, 11+) and run it with `java -jar`:

```shell
curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar
java -jar codeps.jar <subcommand> [options]
```

The CLI is strict: inputs are passed as `--input` flags, there are no positional
arguments, and unknown flags/values are rejected by the parser itself with a clean
error and exit 1. `java -jar codeps.jar --version` prints the version (jar manifest
`Implementation-Version`; `dev` when run from the classpath); `--help` prints usage
for all subcommands.

The examples below use `codeps` as shorthand for `java -jar codeps.jar`.

The steps pipe together — `export` writes the graph to stdout, `report` reads it from stdin (`-`):

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb | codeps report --scope packages --input -
```

## export

```shell
codeps export --from <semanticdb|jdeps> [--root <dir>] [-o out] --input <path>...
```

Pure producer: parses the raw input and emits the standard JSON export graph
(`{"nodes": [...], "edges": [...]}`) to stdout, or to the `-o` file.
There are no include/exclude/collapse flags here — filtering and aggregation are
the analyzer's job.

| Option | Description |
|---|---|
| `--from` (`-f`) | Input format: `semanticdb` or `jdeps`. Required. |
| `--root` | semanticdb only. Makes source URIs relative to this directory (default: the current working directory). |
| `-o` / `--out` | Write the JSON to this file instead of stdout. |
| `-i` / `--input` | Input path, repeatable. Required (at least one). |

`--input` values:

- `semanticdb` — **directories**, walked recursively for `*.semanticdb` files.
- `jdeps` — **files** containing `jdeps -verbose:class` text output.

For a mill build, point SemanticDB export at the compiled output for the module you
want to inspect, for example
`out/<module>/compiledClassesAndSemanticDbFiles.dest/META-INF/semanticdb`, not the
whole `out/` tree. The latter also walks mill's build-definition output and can add
a phantom `build_` package to the report.

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb -o deps.json
codeps export --from jdeps --input jdeps.txt
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
codeps report --scope <packages|files> [--format <json|table>] [--include inc] [-e exc] [-c collapse] [--skip-tests] [-o out] -i <file|->
```

Pure analyzer: reads the standard JSON export graph (a file, or stdin via `-`) and runs the pipeline
(filter → skip-tests? → aggregate to the scope → collapse → metrics) in one pass. Emits the
flat [Metrics report](/reference/report.html), always exits `0` on success.

`--scope` is required: `packages` analyzes the whole package graph; `files` analyzes the file
graph of the packages selected with `--include` (jdeps data has no file-level info and errors here).

| Option | Description |
|---|---|
| `-s` / `--scope` | `packages` or `files`. Required. |
| `-f` / `--format` | `table` (default) or `json`. The table is a compact presentation; JSON preserves canonical ids and every cut. |
| `--include` | Package pattern; keep only nodes whose root package matches it. Repeatable. A pattern `ba.sake` matches `ba.sake` and everything below it. |
| `-e` / `--exclude` | Package pattern; drop nodes whose root package matches it. Excludes win over includes. Repeatable. |
| `-c` / `--collapse` | Collapse rule, e.g. `com.example.**`. Repeatable. |
| `--skip-tests` | Exclude nodes defined in test files (see [Skip tests](#skip-tests)). |
| `--test-pattern` | Glob matching test files; repeatable. Requires `--skip-tests`; replaces the built-in patterns. |
| `-o` / `--out` | Write the report to this file instead of stdout. |
| `-i` / `--input` | The JSON graph to analyze — a file, or `-` for stdin, so `export` output can be piped straight in. Required. |

```shell
codeps report --scope packages --input deps.json -o report.json
codeps export --from semanticdb --input classes/META-INF/semanticdb | codeps report --scope files --include com.example --input - > files.json
codeps export --from jdeps --input jdeps.txt | codeps report --scope packages --format table --input -
```

The `table` format:

```text
scope: packages    generatedAt: 2026-08-27T10:00:00Z

Summary
  nodes: 100    edges: 214    nodesInCycles: 34    orphans: 3    criticalPathLength: 7

Cycles (size desc, extFanIn desc)
common prefix stripped: com.example. (full ids via --format json)
id                 size  extFanIn  minCutsEstimate
scc:modules.cache  10    5         9

  Cycle scc:modules.cache
    solution 1: modules.cache.A -> modules.scheduler.B (w=1), modules.cache.B -> modules.scheduler.C (w=1), modules.cache.C -> modules.scheduler.D (w=1), modules.cache.D -> modules.scheduler.E (w=1), modules.cache.E -> modules.scheduler.F (w=1), modules.cache.F -> modules.scheduler.G (w=1), modules.cache.G -> modules.scheduler.H (w=1), modules.cache.H -> modules.scheduler.I (w=1), … 1 more (full list in JSON)

Change propagators (score = (fanIn/avgFanIn + fanOut/avgFanOut)/2; score > 1, top 10)
  node     fanIn  fanOut  score
  cache    3      2       2.50

Surface (utilization asc; — = no fan-in)
  node     fanIn  fanOut  ports  mutPorts  exposure  utilization
  cache    3       2        9      5          24        0.33
  ...

Orphans
  DeadUtil.scala
```

The cycle table is deliberately bounded: its rows contain only identity and count
columns, then each cycle gets separate numbered solution blocks. A table solution
shows at most 8 cuts; if more exist, its exact omission count points to JSON. Use
`--format json` when another tool or a review needs every canonical node id and
every cut. Dense knots print `dense knot: inspect propagators; full cut list via
--format json` instead of an inline cut wall, because hundreds or thousands of
cuts are not actionable there.

When the rendered ids share a prefix, table output announces it once. Package
reports remove only complete dot-separated segments; file reports remove only
complete slash-separated path segments. JSON always keeps the full canonical ids.
Mixed SemanticDB source roots can leave file ids without a useful common
slash-prefix; in that case no prefix is stripped.

Errors (exit 1): parser errors from mainargs — `Missing argument: --input ...`,
`Unknown argument(s): ...`, `Duplicate arguments for ...: ...` — and input errors:
`input path does not exist: <path>`, `not a file: <path>`,
`no nodes remain after filtering`,
`no file nodes found in the input (jdeps data has no file-level info)`, invalid
collapse rules, unknown scope/format values, `invalid SOURCE_DATE_EPOCH: <value>`
— and malformed or type-invalid
JSON is a hard error, not a warning (see [Exit codes](#exit-codes-and-errors)).

## Reproducible output

`generatedAt` is the real clock by default. Set `SOURCE_DATE_EPOCH` (the
[reproducible-builds.org](https://reproducible-builds.org/specs/source-date-epoch/) standard, epoch
seconds) to pin it for deterministic CI diffs:

```shell
SOURCE_DATE_EPOCH=1700000000 codeps report --scope packages --input deps.json
```

## Include / exclude patterns

A pattern matches a package if the package equals the pattern or starts with
`pattern + "."`. Include/exclude applies to each node's **root package** — the
topmost package ancestor found by walking `parentId` chains (for a package node,
that is the package itself). A node is kept when it has no include patterns or
its root package matches one, and its root package matches no exclude pattern
(excludes win). An edge is kept only when **both** its endpoints are in the
resulting universe (self-edges are dropped). With no `--include`, all nodes are kept.
A nonexistent `--include` leaves no nodes and is a hard exit-1 error
(`no nodes remain after filtering`), so make pipeline users handle it rather than
treating an empty stdout as a successful report.

```shell
# only com.example packages, minus internal helpers
codeps report --scope packages --include com.example -e com.example.internal --input deps.json

# file scope: descend into one package
codeps report --scope files --include com.example.modules.module1 --input deps.json
```

## Skip tests

`--skip-tests` (on `report`) excludes nodes defined in test files: `file`
nodes whose id matches a pattern, and `type`/`member` nodes whose `file` attribute
matches. Package nodes and file-less nodes (all of jdeps data) never match, so on
jdeps data the flag is a no-op. Edges with an excluded endpoint are dropped, and
packages left without children are pruned.

In package scope, `--skip-tests` can leave the reported node count unchanged: main
and test sources often aggregate into the same package nodes. Inspect edges and
the file-level view when you need to see the removed test sources directly.

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
codeps report --scope packages --skip-tests --input deps.json
codeps report --scope files --skip-tests --test-pattern '**/specs/**' --input deps.json
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
codeps report --scope packages -c com.example.modules.** --input deps.json
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
deder exec -t run -m cli export --from semanticdb -i <dir> -o deps.json
deder exec -t run -m cli report --scope packages -i deps.json
```

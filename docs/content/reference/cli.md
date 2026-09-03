---
layout: reference.html
title: CLI
description: codeps CLI reference
---

# CLI

`codeps` is a single binary/entry point (`ba.sake.codeps.cli.Main`). Start with
health, then progress from a graph to a broad report and only then to detail:

1. [`health-snapshot`](#health-snapshot) — records compact, overall repository-health history.
2. [`export`](#export) — parses raw SemanticDB or jdeps input into the
   [codeps export format](/reference/json-input.html).
3. [`report-packages`](#report-packages) and [`report-files`](#report-packages-and-report-files) — analyze that graph.
4. [`inspect-cycle`](#inspect-cycle) and [`inspect-node`](#inspect-node) — open one item from a saved report.

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

Use explicit repository-local paths in automation:

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb --out .codeps/temp/export.json
codeps health-snapshot --input .codeps/temp/export.json --history .codeps/health.ndjson
```

For guided examples, see [Tutorials](/tutorials). The sections below are the
complete option reference, ordered from the simplest workflow to detailed analysis.

## health-snapshot

```shell
codeps health-snapshot [--format <table|markdown|json>] [--color <auto|always|never>] [--input <file>] [--history <file>] [--significance <decimal>] [--max-snapshot-age <duration|off>] [--include inc] [-e exc] [-c collapse] [--skip-tests] [--test-pattern glob] [--commit sha] [--generatedAt instant] [-o out]
```

Records a compact overall repository-health record as NDJSON. First use
`export`, then provide the graph and history paths; see [Track health in CI](/tutorials/health-in-ci.html)
for the recommended workflow.

A new record is appended when a tracked metric changes by more than
`--significance`, crosses zero, or reaches the checkpoint age
(`--max-snapshot-age`; `off` disables checkpoints). It does not
store full graphs or make Git commits.

| Option | Description |
|---|---|
| `-i` / `--input` | Export graph. |
| `--history` | NDJSON history path. |
| `-f` / `--format` | Printed snapshot: `table`, `markdown`, or `json`. |
| `--color` | Table color mode: `auto`, `always`, or `never`. |
| `-o` / `--out` | Write printed output to a file. |
| `--significance` | Non-negative relative-change threshold. |
| `--max-snapshot-age` | Checkpoint interval such as `7d`, or `off`. |
| `--include`, `--exclude`, `--collapse`, `--skip-tests`, `--test-pattern` | The same selection rules as package reports. |
| `--commit` | Commit value. |
| `--generatedAt` | UTC ISO-8601 instant. |

Committing `.codeps/health.ndjson` is recommended when you want a history in
Git or GitHub Pages, but codeps never commits it for you.

## export

```shell
codeps export [--from <semanticdb|jdeps>] [--root <dir>] [-o out] --input <path>...
```

Pure producer: parses the raw input and writes the codeps export graph
(`{"packages": {"nodes": [...], "edges": [...]}, "files": {"nodes": [...], "edges": [...]}}`).
Pass `-o -` to write to stdout, or provide an `-o` path for a file.
There are no include/exclude/collapse flags here — filtering and aggregation are
the analyzer's job.

| Option | Description |
|---|---|
| `--from` (`-f`) | Input format: `semanticdb` or `jdeps`. |
| `--root` | semanticdb only. Makes source URIs relative to this directory. |
| `-o` / `--out` | Output path; use `-` for stdout. |
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
codeps export --from semanticdb --input classes/META-INF/semanticdb
codeps export --from jdeps --input jdeps.txt
```

Errors (exit 1): `at least one input is required`, `input path does not exist: <path>`,
`not a directory: <path>` (semanticdb), `not a file: <path>` (jdeps),
`no .semanticdb files found`. Per-file parse failures are warnings on stderr and
the run continues:

```text
warning: failed to parse semanticdb: ...
```

## report-packages and report-files

```shell
codeps report-packages [--format <table|json|markdown>] [--color <auto|always|never>] [--include inc] [-e exc] [-c collapse] [--skip-tests] [--all] [--columns <group>] [--analyze-cuts] [--cut-time-limit duration] [--cut-candidate-limit positive-int] [-o out] [-i <file|->]
codeps report-files [--format <table|json|markdown>] [--color <auto|always|never>] [--include inc] [-e exc] [-c collapse] [--skip-tests] [--all] [--columns <group>] [--analyze-cuts] [--cut-time-limit duration] [--cut-candidate-limit positive-int] [-o out] [-i <file|->]
```

Pure analyzer: reads the codeps export graph (a file, or stdin via `-`) and runs the pipeline
(filter → skip-tests? → aggregate to the scope → collapse → metrics) in one pass. Emits the
flat [Metrics report](/reference/report.html), always exits `0` on success.

`report-packages` analyzes the whole package graph; `report-files` analyzes the file graph of the
packages selected with `--include` (jdeps data has no file-level info and errors here).

| Option | Description |
|---|---|
| `-f` / `--format` | `table`, `markdown`, or `json`. Table and Markdown are bounded human-triage views; Markdown is deterministic GFM with headings and tables. JSON emits the schema-v2 report, preserves canonical ids and cut-analysis evidence, and caps `findings` at 10,000 rows (with `truncation` counts when needed). |
| `--color` | Table styling mode: `auto` styles only interactive stdout and leaves file output unstyled, `always` forces ANSI styling including file output, and `never` keeps the table plain. JSON and Markdown never contain ANSI styling. |
| `--include` | Package pattern; keep only nodes whose root package matches it. Repeatable. A pattern `ba.sake` matches `ba.sake` and everything below it. |
| `-e` / `--exclude` | Package pattern; drop nodes whose root package matches it. Excludes win over includes. Repeatable. |
| `-c` / `--collapse` | Collapse rule, e.g. `com.example.**`, interpreted against IDs in the selected report. Repeatable. |
| `--skip-tests` | Exclude nodes defined in test files (see [Skip tests](#skip-tests)). |
| `--test-pattern` | Glob matching test files; repeatable. Requires `--skip-tests`; replaces the built-in patterns. |
| `--all` | In table or Markdown format, show every finding, cycle, propagator, surface, and orphan row instead of the top 10 per section. JSON is unaffected: graph-derived inventories are complete, while `findings` retains the 10,000-row serialization cap and `truncation` metadata. |
| `--columns` | Repeatable table/Markdown surface-column group: `core`, `visibility`, `mutability`, `coupling`, or `all`. With no flag, `core` is used. `visibility` covers declaration visibility; `mutability` covers mutable ports and declarations; `coupling` covers in/out flow, exposure, and structural use. Groups compose in canonical order and duplicate columns are shown once; `all` exposes the complete accounting view. JSON is unaffected. |
| `--analyze-cuts` | Opt in to bounded greedy cut estimation and complete-solution search for each SCC. `cutAnalysis.status` distinguishes `completedExact` (the bounded candidate space was exhausted), `completedHeuristic` (greedy-only, including large SCCs), and `budgetExceeded`; without this flag it is `notRequested` and no candidates are simulated. |
| `--cut-time-limit` | Maximum time per SCC's cut analysis, using a positive duration with `ms`, `s`, `m`, or `d` units (for example `250ms`, `1s`, or `0.5m`). |
| `--cut-candidate-limit` | Maximum candidate simulations per SCC's cut analysis. Must be positive. |
| `-o` / `--out` | Write the report to this file instead of stdout. |
| `-i` / `--input` | The JSON graph to analyze; use `-` for stdin. |

```shell
codeps report-packages --input deps.json -o report.json
# Optional cut investigation (bounded; status is explicit in JSON/table output)
codeps report-packages --analyze-cuts --cut-time-limit 1s --cut-candidate-limit 10000 --input deps.json -o report-with-cuts.json
codeps export --from semanticdb --input classes/META-INF/semanticdb -o - | codeps report-files --include com.example --input - > files.json
codeps export --from jdeps --input jdeps.txt -o - | codeps report-packages --format table --input -
codeps report-packages --format markdown --input deps.json -o report.md
```

The `table` format (the sample below uses `--analyze-cuts`):

```text
scope: packages    generatedAt: 2026-08-27T10:00:00Z

Summary
  nodes: 100    edges: 214    nodesInCycles: 34    orphans: 3    criticalPathLength: 7

--------------------------------------------------------------------------------
Findings (top 10 of 1)
kind   severity  subject  evidence  confidence  nextAction
cycle  high      cache    size=2... high        inspect-cycle scc:modules.cache

--------------------------------------------------------------------------------
Cycles (top 10 of 1)
(size desc, extFanIn desc)
common prefix stripped: com.example. (full ids via --format json)
id                 size  extFanIn  greedyCutEstimate  status
scc:modules.cache  10    5         9                  completedHeuristic

  Cycle scc:modules.cache
    solution 1: modules.cache.A -> modules.scheduler.B (w=1), modules.cache.B -> modules.scheduler.C (w=1), modules.cache.C -> modules.scheduler.D (w=1), modules.cache.D -> modules.scheduler.E (w=1), modules.cache.E -> modules.scheduler.F (w=1), modules.cache.F -> modules.scheduler.G (w=1), modules.cache.G -> modules.scheduler.H (w=1), modules.cache.H -> modules.scheduler.I (w=1), … 1 more (full list in JSON)

--------------------------------------------------------------------------------
Change propagators (top 10 of 1) (score = (fanIn/avgFanIn + fanOut/avgFanOut)/2; score > 1)
  node     fanIn  fanOut  score
  cache    3      2       2.50

--------------------------------------------------------------------------------
Surface risks (top 10 of 1) (dependentsPerPublicPort asc; — = no fan-in)
  node     in  out  ports  mut  encap%  use
  cache    3   2    9      5    0.30    0.33
  ...

--------------------------------------------------------------------------------
Public surface (top 10 of 1)
node   pub
cache  3

--------------------------------------------------------------------------------
Public mutability (top 10 of 1)
node   pubMut
cache  1

--------------------------------------------------------------------------------
Public exposure ratio (top 10 of 1)
node   encap%
cache  0.30

--------------------------------------------------------------------------------
Orphans (top 10 of 1)
  DeadUtil.scala
```

The `markdown` format uses the same bounded human-triage content as `table`, rendered as
deterministic GitHub-Flavored Markdown with a health summary, findings, cycles, propagators,
surface-risk tables, and explicit omitted/truncation facts. It is always plain text, including
when `--color always` is supplied, and is suitable for saving as `.md` or publishing in a review.

Surface-risk tables use short headings: `node`, `in`, `out`, `ports`, `mut`, `encap%`, and `use`
make up the compact core view. Add `--columns visibility`, `--columns mutability`, or
`--columns coupling` (each flag may be repeated) to select the `pub`/`prot`/`pkg`/`priv`/`total`,
`mut`/`pubMut`/`protMut`/`pkgMut`/`privMut`/`mut%`, and `in`/`out`/`exp`/`use` groups
respectively. The semantic groups intentionally overlap the compact core where useful, so
each group is useful on its own; composed groups still render each heading once.
`--columns all` selects every surface column. Group order and headings are deterministic.
These aliases apply only to table and Markdown headings; the JSON report keeps its camelCase field names.

The cycle table is deliberately bounded: its rows contain identity, count, greedy
estimate, and cut-analysis status, then each analyzed cycle gets separate numbered
solution blocks. A table solution shows at most 8 cuts; if more exist, its exact
omission count points to JSON. Use `--format json` when another tool or a review
needs every canonical node id and every cut. Without `--analyze-cuts`, the table
explicitly reports `notRequested` and no solution blocks. With analysis enabled,
`budgetExceeded` is still a successful report and may contain only safe partial
evidence; complete solutions are never guessed or labeled complete after a budget
is exhausted. Dense knots print structural guidance instead of a cut wall.

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
collapse rules, unknown format values, `invalid SOURCE_DATE_EPOCH: <value>`
— and malformed or type-invalid
JSON is a hard error, not a warning (see [Exit codes](#exit-codes-and-errors)).
Unknown `--columns` groups are parser errors and must be one of `core`, `visibility`,
`mutability`, `coupling`, or `all`.
Cut controls also reject non-positive limits and reject `--cut-time-limit` or
`--cut-candidate-limit` unless `--analyze-cuts` is present.

## inspect-cycle

```shell
codeps inspect-cycle --id <scc-id> [--scope <packages|files>] [--report <v2-report.json>] [--format <table|json|markdown>]
```

Reads one schema-version-2 metrics report and prints the selected cycle without
recomputing the graph or cut analysis. JSON includes the exhaustive sorted `members`,
the closed `witnessCycle`, `size`, `internalEdges`, `incomingEdges`, `outgoingEdges`,
`extFanIn`, the complete `cutAnalysis` record, and matching findings. Table output
prints the same fields in a compact readable form. Choose `table`, `json`, or
`markdown` with `--format`;
`markdown` is accepted by the shared parser and currently renders the same compact
text as `table` for detail commands.

Successful `report-packages` and `report-files` runs cache their JSON reports at
`.codeps/temp/report-packages.json` and `.codeps/temp/report-files.json`. Pass `--scope packages`
or `--scope files` to select a cache, or `--report` to provide a report path.
The report path may be `-` to read JSON from stdin. Errors (exit 1) include
`report path does not exist`, `report path is not a file`, malformed report JSON,
`incompatible schema version`, and `unknown cycle id`.

## inspect-node

```shell
codeps inspect-node --id <node-id> [--scope <packages|files>] [--report <v2-report.json>] [--format <table|json|markdown>]
```

Reads one schema-version-2 metrics report and prints the selected node's complete
surface row, nullable `cycleId`, and matching findings. JSON and table output are
report-only views; no graph or cut analysis is recomputed. `markdown` is accepted
by the shared parser and currently renders the same
compact text as `table` for detail commands. `--report -` reads from stdin. Unknown
node IDs and incompatible report schema versions exit 1.

## Reproducible output

Set `SOURCE_DATE_EPOCH` (the
[reproducible-builds.org](https://reproducible-builds.org/specs/source-date-epoch/) standard, epoch
seconds) to pin it for deterministic CI diffs:

```shell
SOURCE_DATE_EPOCH=1700000000 codeps report-packages --input deps.json
```

## Include / exclude patterns

A pattern matches a package if the package equals the pattern or starts with
`pattern + "."`. Include/exclude applies to each package node's id and each file
node's `packageId`. A node is kept when it has no include patterns or
its package matches one, and its package matches no exclude pattern
(excludes win). An edge is kept only when **both** its endpoints are in the
resulting universe (self-edges are dropped). With no `--include`, all nodes are kept.
A nonexistent `--include` leaves no nodes and is a hard exit-1 error
(`no nodes remain after filtering`), so make pipeline users handle it rather than
treating an empty stdout as a successful report.

```shell
# only com.example packages, minus internal helpers
codeps report-packages --include com.example -e com.example.internal --input deps.json

# file scope: descend into one package
codeps report-files --include com.example.modules.module1 --input deps.json
```

## Skip tests

`--skip-tests` (on either report command) excludes file nodes whose id matches a pattern.
For package reports with source-file data, codeps derives that report from the file graph
when this flag is set so the test-file surface is excluded before package summaries are made.
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
codeps report-packages --skip-tests --input deps.json
codeps report-files --skip-tests --test-pattern '**/specs/**' --input deps.json
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
codeps report-packages -c com.example.modules.** --input deps.json
```

## Exit codes and errors

- `0` — success
- `1` — usage errors, input errors (missing/nonexistent paths, empty input) or JSON parse errors

For `export`, unparseable input files produce a warning on stderr and are skipped,
so a partial run still succeeds:

```text
warning: failed to parse semanticdb: ...
```

For either report command, malformed or type-invalid JSON input is a hard error (exit 1):

```text
error: failed to parse json: ...
```

## Building from source

For developing codeps itself, the repo is built with [deder](https://sake92.github.io/deder/) —
see the [README](https://github.com/sake92/codeps#development):

```shell
deder exec -t run -m cli export --from semanticdb -i <dir> -o deps.json
deder exec -t run -m cli report-packages -i deps.json
```

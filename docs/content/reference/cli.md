---
layout: reference.html
title: CLI
description: codeps CLI reference
---

# CLI

`codeps` is a single binary/entry point (`ba.sake.codeps.cli.Main`) with three subcommands:
[`semdb`](#semdb) for SemanticDB input, [`jdeps`](#jdeps) for jdeps input and
[`json`](#json) for the [common JSON input format](/reference/json-input.html).
Download the prebuilt jar (requires a JDK, 11+) and run it with `java -jar`:

```shell
curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar
java -jar codeps.jar <subcommand> [options] <inputs...>
```

## Common options

All subcommands share these options:

| Short | Long | Description |
|---|---|---|
| `-i` | `--include` | Package pattern; keep only packages matching it. Repeatable. A pattern `ba.sake` matches `ba.sake` and everything below it. |
| `-e` | `--exclude` | Package pattern; drop matching packages. Excludes win over includes. Repeatable. |
| `-c` | `--collapse` | Collapse rule, e.g. `com.example.**`. Repeatable. |
| `-f` | `--format` | Output format: `dot`, `json`, `mermaid` or `raw`. Required. |
| `-o` | `--out` | Write output to this file instead of stdout. |

Positional inputs (directories for `semdb`, files for `jdeps`, one file or `-` for `json`) are required.

## semdb

```shell
java -jar codeps.jar semdb [-i include] [-e exclude] [-c collapse] -f format [-o out] <dir...>
```

Walks each given directory (recursively) for `*.semanticdb` files, parses them,
and builds the package graph. Requires at least one directory; errors out if a path
does not exist or no `.semanticdb` files are found.

Also extracts per-package stats (file and class counts) — these appear as `nodeInfo` in JSON output.

```shell
java -jar codeps.jar semdb classes/META-INF/semanticdb -i com.example -f json
```

## jdeps

```shell
java -jar codeps.jar jdeps [-i include] [-e exclude] [-c collapse] -f format [-o out] <file...>
```

Parses `jdeps -verbose:package` text output (one or more files). Requires at least one file.
Indented detail lines (`pkg.a -> pkg.b archive`) become edges; non-indented summary lines are ignored.

No stats are available for jdeps input, so JSON `nodeInfo` is omitted.

```shell
java -jar codeps.jar jdeps jdeps.txt -i com.example -e java.** -f dot
```

Both `semdb` and `jdeps` also support `-f raw`, which emits the parsed dependency
info (own packages, edges, stats) in the [common JSON input format](/reference/json-input.html)
— after filtering, before collapsing. This is how you round-trip an analysis into
the `json` subcommand or save an intermediate result:

```shell
java -jar codeps.jar semdb classes/META-INF/semanticdb -i com.example -f raw -o deps.json
java -jar codeps.jar json deps.json -i com.example -f mermaid
```

## json

```shell
java -jar codeps.jar json [-i include] [-e exclude] [-c collapse] -f format [-o out] <file|->
```

Reads the [common JSON input format](/reference/json-input.html): a JSON document
describing own packages, package-level edges and optional stats. This is how codeps
consumes dependency info produced by any other tool (madge, pydeps, `go list`, ...) —
codeps itself never parses source code for these inputs.

Pass `-` to read from stdin, so output of other tools can be piped straight in:

```shell
madge --json src | jq '...' | java -jar codeps.jar json - -i src -f mermaid
```

Exactly one input is required. Malformed or type-invalid JSON is a hard error (exit 1),
not a warning:

```text
error: failed to parse json: Key '$.own' is missing
```

## Include / exclude patterns

A pattern matches a package if the package equals the pattern or starts with `pattern + "."`.
Include/exclude applies to the **own packages** found in the input; an edge is kept only
when **both** its endpoints are in the resulting universe (self-edges are dropped).

```shell
# keep only com.example and subpackages, minus internal helpers
java -jar codeps.jar semdb classes/META-INF/semanticdb -i com.example -e com.example.internal -f dot
```

## Collapse rules

Collapse rules merge packages into a single node, making large graphs readable.
Only trailing wildcards are supported:

| Rule | Effect |
|---|---|
| `com.example.**` | everything equal to or below `com.example` collapses into `com.example` |
| `org.lib.*` | packages directly below `org.lib` collapse into `org.lib.<level>` (one level only) |

When multiple rules match a package, the **longest prefix wins** (ties: first rule in the sequence).
Loops created by collapsing are dropped; edges are deduplicated.

```shell
java -jar codeps.jar semdb classes/META-INF/semanticdb -i com.example -c com.example.modules.** -f dot
```

## Exit codes and errors

- `0` — success
- `1` — usage errors (missing input, unknown format/rule, no packages left after filtering) or parse warnings

Unparseable input files produce a warning on stderr and are skipped, so a partial run still succeeds:

```text
warning: failed to parse semanticdb: ...
```

## Building from source

For developing codeps itself, the repo is built with [deder](https://sake92.github.io/deder/) —
see the [README](https://github.com/sake92/codeps#development):

```shell
deder exec -t run -m cli semdb <dir> -i com.example -f dot
```

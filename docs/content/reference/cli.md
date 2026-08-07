---
layout: reference.html
title: CLI
description: codeps CLI reference
---

# CLI

`codeps` is a single binary/entry point (`ba.sake.codeps.cli.Main`) with two subcommands:
[`semdb`](#semdb) for SemanticDB input and [`jdeps`](#jdeps) for jdeps input.
It is built with deder, so run it as:

```shell
deder exec -t run -m cli <subcommand> [options] <inputs...>
```

## Common options

Both subcommands share these options:

| Short | Long | Description |
|---|---|---|
| `-i` | `--include` | Package pattern; keep only packages matching it. Repeatable. A pattern `ba.sake` matches `ba.sake` and everything below it. |
| `-e` | `--exclude` | Package pattern; drop matching packages. Excludes win over includes. Repeatable. |
| `-c` | `--collapse` | Collapse rule, e.g. `com.example.**`. Repeatable. |
| `-f` | `--format` | Output format: `dot`, `json` or `mermaid`. Required. |
| `-o` | `--out` | Write output to this file instead of stdout. |

Positional inputs (directories for `semdb`, files for `jdeps`) are required.

## semdb

```shell
deder exec -t run -m cli semdb [-i include] [-e exclude] [-c collapse] -f format [-o out] <dir...>
```

Walks each given directory (recursively) for `*.semanticdb` files, parses them,
and builds the package graph. Requires at least one directory; errors out if a path
does not exist or no `.semanticdb` files are found.

Also extracts per-package stats (file and class counts) — these appear as `nodeInfo` in JSON output.

```shell
deder exec -t run -m cli semdb tmp/examples/example1/classes/META-INF/semanticdb -i com.example -f json
```

## jdeps

```shell
deder exec -t run -m cli jdeps [-i include] [-e exclude] [-c collapse] -f format [-o out] <file...>
```

Parses `jdeps -verbose:package` text output (one or more files). Requires at least one file.
Indented detail lines (`pkg.a -> pkg.b archive`) become edges; non-indented summary lines are ignored.

No stats are available for jdeps input, so JSON `nodeInfo` is omitted.

```shell
deder exec -t run -m cli jdeps jdeps.txt -i com.example -e java.** -f dot
```

## Include / exclude patterns

A pattern matches a package if the package equals the pattern or starts with `pattern + "."`.
Include/exclude applies to the **own packages** found in the input; an edge is kept only
when **both** its endpoints are in the resulting universe (self-edges are dropped).

```shell
# keep only com.example and subpackages, minus internal helpers
deder exec -t run -m cli semdb classes/META-INF/semanticdb -i com.example -e com.example.internal -f dot
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
deder exec -t run -m cli semdb classes/META-INF/semanticdb -i com.example -c com.example.modules.** -f dot
```

## Exit codes and errors

- `0` — success
- `1` — usage errors (missing input, unknown format/rule, no packages left after filtering) or parse warnings

Unparseable input files produce a warning on stderr and are skipped, so a partial run still succeeds:

```text
warning: failed to parse semanticdb: ...
```

## Running without deder

`Main` is a plain JVM entry point; it can also be run directly if you have the classpath:

```shell
java -cp <codeps-classpath> ba.sake.codeps.cli.Main semdb <dir> -i com.example -f dot
```

---
layout: howto.html
title: SemanticDB analysis
description: Analyzing dependencies with SemanticDB
---

# SemanticDB analysis

SemanticDB is a data format describing the semantic information of Scala (and Java) programs,
produced by the Scala compiler (`-Xsemanticdb` flag) or tools like scala-cli.

The `semdb` subcommand walks a directory, reads every `*.semanticdb` file, and extracts:

- **own packages** — packages defined in the analyzed sources
- **package edges** — for every symbol reference, an edge `own package -> referenced package`
- **stats** — per package: number of source files and number of class-like types (classes, objects, traits)

## Generating SemanticDB files

With scala-cli:

```shell
scala-cli compile --server=false --semanticdb -d classes src/
```

This writes one `.semanticdb` file per source file under `classes/META-INF/semanticdb/`.

Other ways to get SemanticDB output:

- scalac directly: add `-Xsemanticdb` (and optionally `-P:semanticdb:sourceroot:...`) to your compile flags
- Maven / sbt: enable the `semanticdb` compiler plugin and check the generated files in the target dir
- The codeps repo itself ships a checked-in example: `testFixtures/example1` is compiled by tests into
  `tmp/examples/example1/classes/META-INF/semanticdb`

## Running the analysis

```shell
deder exec -t run -m cli semdb <dir-with-semanticdb> -i com.example -f dot
```

- `<dir-with-semanticdb>` is a positional argument — the whole directory tree is walked for `.semanticdb` files
- `-i/--include` keeps only your packages (see [filtering](#filtering))
- `-f/--format` selects the output format: `dot`, `json` or `mermaid`

Example, using the fixture in this repo:

```shell
deder exec -t run -m cli semdb tmp/examples/example1/classes/META-INF/semanticdb -i com.example -f dot
```

```dot
digraph deps {
  "com.example.app" -> "com.example.modules.module2";
  "com.example.modules.module1" -> "com.example.util";
  "com.example.modules.module2" -> "com.example.modules.module1";
}
```

The JSON format also includes per-package stats (`nodeInfo`), which the
[interactive demo](/demo/cytoscape-graph.html) can display on nodes (file/class counts):

```json
{
  "nodes": ["com.example.app", "com.example.modules.module1"],
  "edges": [["com.example.app", "com.example.modules.module2"]],
  "nodeInfo": {
    "com.example.app": {"files": 1, "classes": 1}
  }
}
```

## Filtering

`--include` and `--exclude` take package patterns: a pattern `com.example` matches the package itself
and everything below it. Excludes win over includes.

```shell
# only com.example packages, no third-party or JDK noise
deder exec -t run -m cli semdb classes/META-INF/semanticdb -i com.example -f json

# com.example.* minus internal helpers
deder exec -t run -m cli semdb classes/META-INF/semanticdb -i com.example -e com.example.internal -f json
```

## Collapsing

Collapse rules merge whole subtrees into a single node, which keeps big graphs readable:

| Pattern | Effect |
|---|---|
| `com.example.**` | everything below `com.example` collapses into `com.example` |
| `org.lib.*` | sub-packages collapse one level below the prefix (e.g. `org.lib.http`, `org.lib.json`) |

```shell
deder exec -t run -m cli semdb classes/META-INF/semanticdb -i com.example -c com.example.modules.** -f dot
```

When multiple rules match, the longest prefix wins; loops created by collapsing are dropped.

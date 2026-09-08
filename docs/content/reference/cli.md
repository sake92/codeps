---
layout: reference.html
title: CLI
description: codeps CLI reference
---

# CLI

`codeps` has one normal workflow: configure the projects in the repository, then run `codeps status`. It parses source data, analyzes both package and file granularity when available, records a compact history, writes an inspectable JSON report, and renders a static HTML dashboard in one command.

The only other public commands open detail from the most recent cached report:

```shell
codeps inspect-cycle --project backend --id scc:example.orders
codeps inspect-node --project backend --id example.orders
```

`--version` prints the build version and `--help` prints complete command usage.

## Configuration

Store one committed repository configuration at `.codeps/config.yaml`. On its first run, `codeps status` creates a starter configuration with one `root` project that scans `.` for SemanticDB; tweak that file rather than starting from scratch. Each key under `projects` names one independently tracked project. Paths are relative to that project's `root`, which is relative to the Git repository root.

```yaml
projects:
  backend:
    root: .
    source: semanticdb
    inputs:
      - .deder/out/app/compile/semanticdb
    skip-tests: true
    exclude: [com.example.internal]
    significance: 0.01
    max-snapshot-age: 7d

  legacy-java:
    root: services/legacy
    source: jdeps
    inputs: [target/jdeps.txt]
```

| Field | Default | Meaning |
|---|---|---|
| `root` | `.` | Project directory, relative to the repository root. |
| `source` | `semanticdb` | `semanticdb`, `jdeps`, or `export` for an existing [codeps export format](/reference/json-input.html) file. |
| `inputs` | required | SemanticDB directories, jdeps files, or exactly one export JSON file. |
| `include`, `exclude`, `collapse` | empty | Analysis patterns. |
| `skip-tests` | `false` | Remove test files before package analysis. |
| `test-pattern` | built-in patterns | Replacement test globs; requires `skip-tests: true`. |
| `significance` | `0.01` | Relative metric change required before a new snapshot is stored. |
| `max-snapshot-age` | `7d` | Periodic checkpoint age (`off` disables checkpoints). |

Keep this configuration stable for a history you intend to compare. codeps does not fingerprint settings or segment charts: when analysis settings change materially, start that project's history again deliberately.

### Include / exclude patterns

`include` and `exclude` values are package prefixes, not file globs or other wildcard
patterns. A pattern such as `com.example.internal` matches that package and every subpackage
below it; `exclude` wins when a node matches both an include and an exclude. For example, to
exclude the JDK package tree, use `java`, not `java.**`.

The trailing `*` and `**` syntax belongs to `collapse` rules: `com.example.generated.*`
collapses each immediate subpackage into its own node, while `com.example.generated.**`
collapses the entire subtree into `com.example.generated`.

## status

```shell
codeps status [--config path] [--project id]... [--commit sha] [--generated-at instant] [--out index.html]
```

With no `--project`, codeps runs every configured project. `--commit` and `--generated-at` are one-off overrides; `--out` is allowed only for exactly one selected project.

For a project named `backend`, default artifacts are:

| Path | Purpose |
|---|---|
| `.codeps/backend.ndjson` | Compact, commit-friendly health history. |
| `.codeps/out/backend/report.json` | Latest detailed package and file reports for inspection. |
| `.codeps/out/backend/index.html` | D3/Pico static status dashboard. |

The dashboard opens on Home: a one-decimal average of the available package and file health scores,
with both underlying scores shown beside it. Use the Packages and Files pills to inspect each
scope's trend and evidence. The combined score is directional, not a claim of perfectly precise
architectural measurement.

## inspect-cycle and inspect-node

```shell
codeps inspect-cycle --id <cycle-id> [--project id] [--config path] [--format <table|json>]
codeps inspect-node --id <node-id> [--project id] [--config path] [--format <table|json>]
```

Both read `.codeps/out/<project>/report.json`; pass `--scope files` for file-level detail (packages is the default). Run `codeps status` first. When the config has more than one project, `--project` is required.

## GitHub Pages CI

Commit `.codeps/config.yaml` and the desired `.codeps/<project>.ndjson` history. Generate the site in CI, then publish the per-project directory directly:

```shell
codeps status
cp -R .codeps/out/backend site
```

That produces `site/index.html` for the `backend` project. codeps never commits history or deploys Pages for you.

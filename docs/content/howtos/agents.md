---
layout: howto.html
title: Use codeps with an AI agent
description: Install the Codeps skill and investigate bounded dependency findings
---

# Use codeps with an AI agent

Use this workflow with a configured `.codeps/config.yaml` and a working dependency
producer: SemanticDB, jdeps, or codeps export JSON. For input setup, see the
[CLI reference](/reference/cli.html#configuration).

## Install the skill

Install the [Codeps skill](https://github.com/sake92/skills/tree/main/skills/codeps)
from the project directory:

```shell
npx skills add sake92/skills --skill codeps
```

Select your coding agent in the installer, or add `--agent <name>` to target one.
Add `-g` to install for your user across projects. See the
[skills installer documentation](https://github.com/vercel-labs/skills#readme)
for supported agents and installation options.

The skill targets Codeps 0.6.0. Check `codeps --version`; if your installed version
differs, use the skill's bundled `scripts/codeps-cli` runner from its installed
directory. The runner downloads and verifies the pinned release on first use.
Use that runner in place of `codeps` in the commands below.

## Refresh and select findings

Build the project with its existing dependency producer before running analysis.
Check that relevant reported source paths still exist; stale compiler output from
deleted or moved sources requires fresh build inputs and another analysis.

For a configured project named `app`:

```shell
codeps status --project app
codeps findings --project app --limit 10 --format json
```

Check `total`, `omitted`, and `sourceFindingsOmitted`. Claim an exhaustive selection
only when both omission counts are zero. Narrow the selection when needed:

```shell
codeps findings --project app --kind cycle --limit 10 --format json
codeps findings --project app --scope files --severity high --limit 5 --format json
```

Use file scope only when the input supplies file data; jdeps supplies packages only.

## Inspect selected subjects

Use the `subject` values returned by findings as inspection ids. For example:

```shell
codeps inspect-cycle --project app --id scc:com.example.orders --format json
codeps inspect-node --project app --scope files --id src/com/example/orders/OrderService.scala --format json
```

Preserve the finding's project and scope. Its `nextAction` is an action hint;
supply the `codeps` command, project, scope, any custom `--config` path, and shell
quoting for ids. Read the named source and its consumers before recommending a change.

Fetch only the [artifact schemas](/reference/report.html#json-schemas) needed to
parse or validate selected output. Read specific cached report fields when the
question needs evidence outside findings, such as summary counts or orphans.

## Verify a change

After an authorized refactoring, rebuild and repeat `status`, bounded findings,
and selected inspection for the same project and scope. Compare the affected
cycles and metrics with the baseline using stable analysis settings. Report build
failures or stale inputs as limits on verification.

Keep temporary exports and reports under `.codeps/temp/`. Codeps automatically
labels snapshots with clean HEAD, HEAD plus `-dirty`, or epoch milliseconds when
HEAD is unavailable. Avoid overriding changed worktree inputs with unchanged HEAD.
For timestamp rules and overrides, see [status](/reference/cli.html#status).

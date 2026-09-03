---
layout: tutorial.html
title: Track health in CI
description: Keep a compact dependency-health history in Git
---

# Track health in CI

Make codeps a small, non-blocking CI step first. Each successful build updates a
compact history only when dependency health materially changes (or a periodic
checkpoint is due). Review the history like any other generated project artifact.

## 1. Produce compiler output

Run your normal compile step with SemanticDB enabled, then export it. For a
Java-only project, replace the export line with the [jdeps workflow](/howtos/jdeps.html).

```shell
codeps export --from semanticdb --input classes/META-INF/semanticdb
codeps health-snapshot --format markdown
```

Use explicit paths in CI so the produced artifacts are obvious:

```shell
java -jar codeps.jar export --from semanticdb --input classes/META-INF/semanticdb --out .codeps/temp/export.json
java -jar codeps.jar health-snapshot --input .codeps/temp/export.json --history .codeps/health.ndjson --format markdown
```

Add the history file to version control; leave the export graph untracked unless
you have a reason to keep it.

## 2. Add the CI step

Place this after compilation in your existing GitHub Actions job. `codeps.jar`
can be cached or downloaded in the job; this version keeps the example explicit.

```yaml
- name: Download codeps
  run: curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar

- name: Record dependency health
  run: |
    java -jar codeps.jar export --from semanticdb --input classes/META-INF/semanticdb --out .codeps/temp/export.json
    java -jar codeps.jar health-snapshot --input .codeps/temp/export.json --history .codeps/health.ndjson --format markdown
```

Use the directory your build actually writes, not the source directory. See
[SemanticDB setup](/howtos/semdb.html) if you do not yet emit `.semanticdb` files.

## 3. Commit the history deliberately

The command updates the workspace; it does not create a Git commit. A common
setup is a scheduled or main-branch job that commits only the history when it
changes. Give that job write permission, then use your repository's existing
bot/commit policy to commit `.codeps/health.ndjson`.

Do not make a pull-request check commit on behalf of a contributor. Instead,
publish the Markdown output in job logs or a step summary, and let maintainers
decide whether a regression needs [package triage](/tutorials/package-triage.html).

## Tune the noise level

Choose a significance threshold and checkpoint interval for your repository.
Raise the threshold for a noisy, fast-changing graph; disable checkpoints when
you want records only for meaningful changes.

```shell
codeps health-snapshot --significance 0.03 --max-snapshot-age off
```

The [CLI reference](/reference/cli.html#health-snapshot) documents the exact
snapshot fields and options.

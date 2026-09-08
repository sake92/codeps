---
title: codeps
description: Codebase dependency status tracker
pagination:
  enabled: false
---

# codeps

codeps tracks broad dependency-health trends for JVM projects. It reads compiler
output, identifies cycles and coupling signals, keeps compact history, and
generates a static dashboard for each configured project.

Configure the repository once, then run one command in CI:

```shell
curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar
java -jar codeps.jar status
```

The examples below use `codeps` as the installed CLI command.

The command writes:

- `.codeps/<project>.ndjson` — compact package/file health history intended for Git.
- `.codeps/out/<project>/report.json` — latest detailed report.
- `.codeps/out/<project>/index.html` — interactive D3/Pico dashboard.

Use `codeps inspect-cycle` or `codeps inspect-node` to drill into a saved
report after the dashboard identifies something worth investigating; pass `--scope files` for
file detail. The report
is a trend signal, not a substitute for architectural judgment: its metrics are
intended to be good enough to reveal a direction of travel.

See the [CLI reference](/reference/cli.html) for the repository configuration
and GitHub Pages workflow, [Tutorials](/tutorials) for guided setup, and [How
Tos](/howtos) for SemanticDB and jdeps input. You can also use the
[codeps skill](https://github.com/sake92/skills/tree/main/skills/codeps) with
an AI coding agent.

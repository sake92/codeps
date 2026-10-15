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
brew install sake92/tap/codeps
codeps status
```

The examples below use `codeps` as the installed CLI command.

The command writes:

- `.codeps/<project>.ndjson` — compact package/file health history intended for Git.
- `.codeps/out/<project>/report.json` — latest detailed report.
- `.codeps/out/<project>/index.html` — interactive D3/Pico dashboard.

Start with `codeps findings --project <id> --limit 10 --format json`, then use
`codeps inspect-cycle` or `codeps inspect-node` for selected subjects, preserving the
project and scope. Pass `--scope files` for file detail. The report
is a trend signal, not a substitute for architectural judgment: its metrics are
intended to be good enough to reveal a direction of travel.

See the [CLI reference](/reference/cli.html) for the repository configuration
and GitHub Pages workflow, [Tutorials](/tutorials) for guided setup, and [How
Tos](/howtos) for SemanticDB and jdeps input. You can also use the
[codeps example projects](https://github.com/sake92/codeps-examples) as ready-made
repositories to try, and the
[codeps skill](https://github.com/sake92/skills/tree/main/skills/codeps) with
an AI coding agent. See [Use codeps with an AI agent](/howtos/agents.html) for
installation and example prompts.

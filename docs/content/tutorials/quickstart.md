---
layout: tutorial.html
title: First codebase status
description: Configure and generate your first codeps dashboard
---

# First codebase status

Compile your project with SemanticDB enabled, download the CLI, and save this as
`.codeps/config.yaml` at the Git repository root:

```yaml
projects:
  app:
    source: semanticdb
    inputs: [classes/META-INF/semanticdb]
    scope: packages
    skip-tests: true
```

For jdeps, use `source: jdeps` and point `inputs` at the text produced by
`jdeps -verbose:class` instead. See [SemanticDB setup](/howtos/semdb.html) and
[jdeps setup](/howtos/jdeps.html) for input locations.

```shell
curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar
java -jar codeps.jar status
```

The dashboard is at `.codeps/out/app/index.html`; history is stored in
`.codeps/app.ndjson`. Commit the configuration and history if you want a trend
that follows the repository. Use `inspect-cycle` and `inspect-node` when a
dashboard metric points to a concrete item to investigate.

The [CLI reference](/reference/cli.html) describes all configuration fields.

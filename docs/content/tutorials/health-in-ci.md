---
layout: tutorial.html
title: Track status in CI
description: Generate a codeps dashboard in CI
---

# Track status in CI

Keep `.codeps/config.yaml` in the repository. After the normal compile step has
produced SemanticDB (or jdeps input), run status:

```yaml
- name: Download codeps
  run: curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar

- name: Generate codebase status
  run: java -jar codeps.jar status
```

For a project named `backend`, CI now has:

- `.codeps/backend.ndjson` — compact history; commit it only from a trusted
  main-branch or scheduled job.
- `.codeps/out/backend/index.html` — static dashboard for GitHub Pages.

To deploy that dashboard as a Pages artifact:

```shell
mkdir -p site
cp -R .codeps/out/backend/. site/
```

The page uses Pico CSS and D3 from jsDelivr. Keep analysis configuration stable
while tracking a history; if its scope or filters change materially, begin a new
history rather than treating unlike snapshots as comparable.

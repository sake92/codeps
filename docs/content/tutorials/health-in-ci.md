---
layout: tutorial.html
title: Track status in CI
description: Generate a codeps dashboard in CI
---

# Track status in CI

Keep `.codeps/config.yaml` in the repository. After the normal compile step has
produced SemanticDB (or jdeps input), run status:

```yaml
- name: Generate codebase status
  run: codeps status
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
while tracking a history; if its inputs, filters, test handling, or collapse rules change materially, begin a new
history rather than treating unlike snapshots as comparable.

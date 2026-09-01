# Agent Instructions

## Build Tool
- Development uses **deder** (install: `brew install sake92/tap/deder`); project config lives in `deder.pkl` (module ids, deps, Scala version). No sbt/Mill files.
- For running the CLI, use the prebuilt uber jar — no deder needed:

  ```shell
  curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar
  ```

## Commands
| Task | Command |
|------|---------|
| Download CLI jar | `curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar` |
| Run CLI | `java -jar codeps.jar export --from semanticdb -i <dir> -o deps.json` |
| Build docs | `./scripts/build-docs.sh` (outputs `docs/_site/`) |
| All tests (dev, needs deder) | `deder exec -t test` |
| Test one module (dev, needs deder) | `deder exec -t test -m core-test` |
| CLI assembly (dev, needs deder) | `deder exec -t assembly -m cli` |

## External References
| Need | File |
|------|------|
| CLI reference | `docs/content/reference/cli.md` |
| Report JSON schema | `docs/content/reference/report.md` |
| Standard JSON export format | `docs/content/reference/json-input.md` |
| Build config | `deder.pkl` |
| CI/CD | `.github/workflows/` |

## Key Conventions
- Sources: `modules/<id>/src/` (main), `modules/<id>/test/src/` (tests); test module id is `<id>-test`.
- Tests: munit `FunSuite`, files named `*Spec.scala`.
- Fixtures: sources in `testFixtures/example1/`; compiled at test runtime by `FixtureCompiler` into `tmp/` (gitignored). `testFixtures/cyclic.json` is a checked-in graph in the standard JSON export format — used by CLI tests and as the homepage example output.
- JSON: all emitted JSON field names are camelCase (`generatedAt`, `nodesInCycles`, `solutions`, `propagators`, `score`, `extFanIn`, `fanIn`, `mutPorts`, ...); CLI table headers use the same names. The export graph format is called the *standard JSON export format* (page title/label; never "common JSON").
- Generated — do not edit: `docs/_site/`, `.deder/out/`, `tmp/`.

## Commit Attribution
AI commits MUST include:
```
Co-Authored-By: (the agent's name and attribution byline)
```

## Planning Artifacts
- Specs and implementation plans are local working artifacts (`docs/superpowers/`); never stage or commit them.

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
| Run CLI | `java -jar codeps.jar export --from semanticdb <dir> -o deps.json` |
| Build docs | `./scripts/build-docs.sh` (outputs `docs/_site/`) |
| All tests (dev, needs deder) | `deder test` |
| Test one module (dev, needs deder) | `deder exec -t test -m core-test` |
| CLI assembly (dev, needs deder) | `deder exec -t assembly -m cli` |

## External References
| Need | File |
|------|------|
| Architecture | `docs/content/reference/architecture.md` |
| CLI reference | `docs/content/reference/cli.md` |
| Build config | `deder.pkl` |
| CI/CD | `.github/workflows/` |

## Key Conventions
- Sources: `modules/<id>/src/` (main), `modules/<id>/test/src/` (tests); test module id is `<id>-test`.
- Tests: munit `FunSuite`, files named `*Spec.scala`.
- Fixtures: sources in `testFixtures/example1/`; compiled at test runtime by `FixtureCompiler` into `tmp/` (gitignored).
- Generated — do not edit: `docs/_site/`, `.deder/out/`, `tmp/`.

## Commit Attribution
AI commits MUST include:
```
Co-Authored-By: (the agent's name and attribution byline)
```

# Agent Instructions

## Build Tool
- Use **deder** (install: `brew install sake92/tap/deder`); project config lives in `deder.pkl` (module ids, deps, Scala version). No sbt/Mill files.

## Commands
| Task | Command |
|------|---------|
| All tests | `deder test` |
| Test one module | `deder exec -t test -m core-test` |
| Run CLI | `deder exec -t run -m cli semdb <dir> -i com.example -f json` |
| CLI assembly | `deder exec -t assembly -m cli` |
| Build docs | `./scripts/build-docs.sh` (outputs `docs/_site/`) |

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

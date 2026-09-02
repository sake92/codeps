# codeps

Code package dependency analyzer.

Parses [SemanticDB](https://scalameta.org/docs/semanticdb/specification.html) or `jdeps` output
into the [codeps export format](https://sake92.github.io/codeps/reference/json-input.html),
then emits a flat [metrics report](https://sake92.github.io/codeps/reference/report.html):
SCC facts with optional budgeted cut analysis, per-node exposed-surface metrics
(`ports`/`mutPorts`/`exposure`/`dependentsPerPublicPort`) and declaration-surface
encapsulation —
over the package graph, or the file graph of the packages you select.

- [Documentation](https://sake92.github.io/codeps/)
- Built with [deder](https://sake92.github.io/deder/) (development only — users run the prebuilt jar)

## Quick start

Your local build already produces everything codeps needs: a compiler with SemanticDB enabled emits
`.semanticdb` files alongside your compiled classes, and the JDK ships `jdeps` — codeps just reads
that existing output. No extra tooling to install.

Requires a JDK (11+).

```shell
# Download the prebuilt CLI jar
curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar

# Scala: compile with SemanticDB enabled
scala-cli compile --server=false --semanticdb -d classes src/

# Java: use the JDK's own analyzer
jdeps -verbose:class -filter:none -cp classes classes > jdeps.txt

# Export the graph, then analyze it
java -jar codeps.jar export --from semanticdb --input classes/META-INF/semanticdb -o deps.json
# or: java -jar codeps.jar export --from jdeps --input jdeps.txt -o deps.json
java -jar codeps.jar report-packages --input deps.json
# file-level view for one package:
java -jar codeps.jar report-files --include com.example --input deps.json
# table is the default; --color auto styles only interactive terminal output
# use --format markdown for deterministic GFM or --format json for machine-readable JSON
```

### Other languages

For any other ecosystem, produce the [codeps export format](https://sake92.github.io/codeps/reference/json-input.html)
with a tool of your choice (madge, pydeps, `go list`, ...) and feed it to `report-packages` —
codeps never parses that source code itself:

```shell
madge --json src | jq '... shape it into the codeps export format ...' | java -jar codeps.jar report-packages --input -
```

The metrics are language-agnostic once the node/edge list carries per-node `isExposed`/`ports`/`mutPorts`
(see the [metrics report](https://sake92.github.io/codeps/reference/report.html#exposed-surface)) —
a TS/Python extractor only has to emit the same generic JSON shape.

Human reports are bounded to 10 rows per section; add `--all` for every table or Markdown row.
Use `--analyze-cuts` (optionally with `--cut-time-limit` and `--cut-candidate-limit`) to request
bounded SCC cut analysis. The [CLI reference](https://sake92.github.io/codeps/reference/cli.html)
covers filtering, surface-column groups, ANSI color modes, and `inspect-cycle`/`inspect-node`.

## Development

Developing codeps itself requires [deder](https://sake92.github.io/deder/) (`brew install sake92/tap/deder`):

```shell
deder exec -t test      # run all tests
deder exec -t run -m cli export --from semanticdb --input tmp/examples/example1/classes/META-INF/semanticdb -o /tmp/deps.json
deder exec -t run -m cli report-packages --input /tmp/deps.json
```

The website is built with [flatmark](https://github.com/sake92/flatmark):

```shell
./scripts/build-docs.sh   # outputs docs/_site/
```

Deployed to GitHub Pages on every push to `main` (see `.github/workflows/ghpages.yml`).

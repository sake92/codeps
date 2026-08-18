# codeps

Code package dependency analyzer.

Parses [SemanticDB](https://scalameta.org/docs/semanticdb/specification.html) or `jdeps` output
into a [common JSON graph format](https://sake92.github.io/codeps/reference/json-input.html),
then renders it as DOT or Mermaid at any granularity (package, file, type or member) and
produces a multi-level [cycle analysis report](https://sake92.github.io/codeps/reference/report.html)
with severity grades and fix suggestions.

- [Documentation](https://sake92.github.io/codeps/)
- **[Live interactive demo](https://sake92.github.io/codeps/demo/cytoscape-graph.html)** —
  open a sample dependency graph right in your browser: layouts, DSM matrix view, filtering,
  cycle highlighting, scoped drill-down. No install needed.
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

# Export the graph, then render and analyze it
java -jar codeps.jar export --from semanticdb classes/META-INF/semanticdb -o deps.json
# or: java -jar codeps.jar export --from jdeps jdeps.txt -o deps.json
java -jar codeps.jar draw -g package -f dot deps.json
java -jar codeps.jar report deps.json -o report.json
```

### Other languages

For any other ecosystem, produce the [JSON input format](https://sake92.github.io/codeps/reference/json-input.html)
with a tool of your choice (madge, pydeps, `go list`, ...) and feed it to `draw` or `report` —
codeps never parses that source code itself:

```shell
madge --json src | jq '... shape it into the common JSON format ...' | java -jar codeps.jar draw -g package -f mermaid -
```

`codeps export` emits exactly this format — so exported graphs, tool-produced graphs and
`codeps report` output can all be loaded into the
[interactive demo](https://sake92.github.io/codeps/demo/cytoscape-graph.html).

## Development

Developing codeps itself requires [deder](https://sake92.github.io/deder/) (`brew install sake92/tap/deder`):

```shell
deder test      # run all tests
deder exec -t run -m cli export --from semanticdb tmp/examples/example1/classes/META-INF/semanticdb -o /tmp/deps.json
```

The website is built with [flatmark](https://github.com/sake92/flatmark):

```shell
./scripts/build-docs.sh   # outputs docs/_site/
```

Deployed to GitHub Pages on every push to `main` (see `.github/workflows/ghpages.yml`).

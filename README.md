# codeps

Code package dependency analyzer.

Parses [SemanticDB](https://scalameta.org/docs/semanticdb/specification.html) or `jdeps` output
and produces a package-level dependency graph, exportable as DOT, JSON or Mermaid.

- [Documentation and interactive demo](https://sake92.github.io/codeps/)
- Built with [deder](https://sake92.github.io/deder/) (development only — users run the prebuilt jar)

## Quick start

Your local build already produces everything codeps needs: a compiler with SemanticDB enabled emits `.semanticdb` files alongside your compiled classes, and the JDK ships `jdeps` — codeps just reads that existing output. No extra tooling to install.

Requires a JDK (11+).

```shell
# Download the prebuilt CLI jar
curl -L -o codeps.jar https://github.com/sake92/codeps/releases/download/main/codeps-cli-main.jar

# Scala: compile with SemanticDB enabled
scala-cli compile --server=false --semanticdb -d classes src/

# Java: use the JDK's own analyzer
jdeps -verbose:package -filter:none -cp classes classes > jdeps.txt

# Analyze
java -jar codeps.jar semdb classes/META-INF/semanticdb -i com.example -f dot
java -jar codeps.jar jdeps jdeps.txt -i com.example -f dot
```

## Development

Developing codeps itself requires [deder](https://sake92.github.io/deder/) (`brew install sake92/tap/deder`):

```shell
deder test      # run all tests
deder exec -t run -m cli semdb tmp/examples/example1/classes/META-INF/semanticdb -i com.example -f json
```

The website is built with [flatmark](https://github.com/sake92/flatmark):

```shell
./scripts/build-docs.sh   # outputs docs/_site/
```

Deployed to GitHub Pages on every push to `main` (see `.github/workflows/ghpages.yml`).

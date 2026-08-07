# codeps

Code package dependency analyzer.

Parses [SemanticDB](https://scalameta.org/docs/semanticdb/specification.html) or `jdeps` output
and produces a package-level dependency graph, exportable as DOT, JSON or Mermaid.

- [Documentation and interactive demo](https://sake92.github.io/codeps/)
- Built with [deder](https://sake92.github.io/deder/)

## Quick start

```shell
# Scala: compile with SemanticDB enabled
scala-cli compile --server=false --semanticdb -d classes src/

# Java: use the JDK's own analyzer
jdeps -verbose:package -filter:none -cp classes classes > jdeps.txt

# Analyze
deder exec -t run -m cli semdb classes/META-INF/semanticdb -i com.example -f dot
deder exec -t run -m cli jdeps jdeps.txt -i com.example -f dot
```

## Development

```shell
deder test      # run all tests
deder exec -t run -m cli semdb tmp/examples/example1/classes/META-INF/semanticdb -i com.example -f json
```

The website is built with [flatmark](https://github.com/sake92/flatmark):

```shell
./scripts/build-docs.sh   # outputs docs/_site/
```

Deployed to GitHub Pages on every push to `main` (see `.github/workflows/ghpages.yml`).

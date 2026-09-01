package ba.sake.codeps.graph

import ba.sake.codeps.model.*

object TestFilter:

  /** Built-in patterns used by `--skip-tests` when the user passes no `--test-pattern`.
    * A `test` path segment covers sbt/maven/mill/deder test layouts (`src/test/…`,
    * `modules/x/test/src/…`); the `.test.scala` suffix covers the scala-cli test scope;
    * the rest are naming conventions (`*Spec`, `*Test`, `*Tests`, `*Suite`). */
  val defaultPatterns: Seq[String] = Seq(
    "**/test/**",
    "**/*.test.scala",
    "**/*Spec.scala",
    "**/*Test.scala",
    "**/*Tests.scala",
    "**/*Suite.scala",
    "**/*Spec.java",
    "**/*Test.java",
    "**/*Tests.java",
    "**/*Suite.java"
  )

  /** Drops nodes defined in test files: `file` nodes whose id matches a pattern, and
    * `type`/`member` nodes whose `file` attribute matches. Package nodes and file-less
    * nodes (all of jdeps) never match. Edges are kept only when both endpoints survive
    * (self-edges dropped); childless package nodes are pruned afterwards. */
  def skipTests(graph: DepsGraph, patterns: Seq[String]): DepsGraph =
    val matchers = patterns.map(Glob.matches)
    def isTest(n: Node): Boolean =
      n.kind match
        case NodeKind.file => matchers.exists(m => m(n.id))
        case _             => n.file.exists(f => matchers.exists(m => m(f)))
    val kept = graph.nodes.filterNot(isTest)
    val keptIds = kept.map(_.id)
    val keptEdges = graph.edges.filter(e => keptIds.contains(e.source) && keptIds.contains(e.target) && e.source != e.target)
    Prune.emptyPackages(DepsGraph(kept, keptEdges, graph.symbolReferences, graph.declaredPublicSymbols))

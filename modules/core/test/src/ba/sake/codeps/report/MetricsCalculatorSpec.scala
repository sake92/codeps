package ba.sake.codeps.report

import ba.sake.codeps.graph.TestFilter
import ba.sake.codeps.model.*
import ba.sake.codeps.report.MetricsCalculator.Scope

class MetricsCalculatorSpec extends munit.FunSuite:

  private val pkgGraph = DepsGraph(
    nodes = Set(
      Node("com.a", NodeKind.`package`),
      Node("com.b", NodeKind.`package`),
      Node("com.a.A", NodeKind.`type`, Some("com.a"), Some("src/A.scala"), ports = 3.0),
      Node("com.a.A#m", NodeKind.member, Some("com.a.A"), Some("src/A.scala"), ports = 1.0, mutPorts = 1.0),
      Node("com.b.B", NodeKind.`type`, Some("com.b"), Some("src/B.scala"), ports = 0.5),
      Node("src/A.scala", NodeKind.file),
      Node("src/B.scala", NodeKind.file)
    ),
    edges = Set(
      Edge("com.a.A#m", "com.b.B"),
      Edge("com.b.B", "com.a.A"),
      Edge("com.a.A#m", "com.a.A") // intra-package, must vanish at package scope (self-loop)
    )
  )

  test("packages scope: nodes map to root package, ports sum, intra edges become self-loops") {
    val sg = MetricsCalculator.scopeGraph(pkgGraph, Scope.Packages).toOption.get
    assertEquals(sg.nodes, Set("com.a", "com.b"))
    assertEquals(sg.edges, Set(Edge("com.a", "com.b"), Edge("com.b", "com.a")))
    assertEquals(sg.ports("com.a"), 4.0) // A(3) + m(1)
    assertEquals(sg.mutPorts("com.a"), 1.0)
    assertEquals(sg.ports("com.b"), 0.5)
  }

  test("files scope: nodes map to their file id, file-less nodes are dropped") {
    val graph = pkgGraph.copy(nodes = pkgGraph.nodes + Node("com.c.orphan", NodeKind.`type`, Some("com.c"), None))
    val sg = MetricsCalculator.scopeGraph(graph, Scope.Files).toOption.get
    assertEquals(sg.nodes, Set("src/A.scala", "src/B.scala"))
    assertEquals(sg.ports("src/A.scala"), 4.0)
    assertEquals(sg.ports("src/B.scala"), 0.5)
    assertEquals(sg.edges, Set(Edge("src/A.scala", "src/B.scala"), Edge("src/B.scala", "src/A.scala")))
  }

  test("scope on a graph without mappable nodes errors") {
    val onlyFiles = DepsGraph(Set(Node("src/A.scala", NodeKind.file)), Set.empty)
    assert(MetricsCalculator.scopeGraph(onlyFiles, Scope.Packages).isLeft)
    val noFiles = DepsGraph(Set(Node("com.a", NodeKind.`package`)), Set.empty)
    assert(MetricsCalculator.scopeGraph(noFiles, Scope.Files).isLeft)
  }

  test("collapse re-sums ports and mut ports through the same id mapping") {
    val sg = MetricsCalculator.scopeGraph(pkgGraph, Scope.Packages).toOption.get
    val collapsed = MetricsCalculator.collapse(sg, Seq(CollapseRule.Wild("com")))
    assertEquals(collapsed.nodes, Set("com"))
    assertEquals(collapsed.ports("com"), 4.5)
    assertEquals(collapsed.mutPorts("com"), 1.0)
    assertEquals(collapsed.edges, Set.empty[Edge]) // both directions collapse onto "com" -> self-loops dropped
  }

  test("packages scope: fans, surface, orphans, summary on an acyclic graph") {
    val graph = DepsGraph(
      nodes = Set(
        Node("com.a", NodeKind.`package`),
        Node("com.b", NodeKind.`package`),
        Node("com.c", NodeKind.`package`),
        Node("com.a.A", NodeKind.`type`, Some("com.a"), None, ports = 3.0),
        Node("com.b.B", NodeKind.`type`, Some("com.b"), None, ports = 3.0),
        Node("com.c.C", NodeKind.`type`, Some("com.c"), None, ports = 3.0)
      ),
      edges = Set(Edge("com.a.A", "com.b.B"), Edge("com.b.B", "com.c.C"))
    )
    val report = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    assertEquals(report.scope, "packages")
    assertEquals(report.summary, Summary(nodes = 3, edges = 2, nodesInCycles = 0, orphans = 0, criticalPathLength = 2))
    assertEquals(report.cycles, Seq.empty)
    assertEquals(report.surface, Seq(
      SurfaceRow("com.b", 1, 1, 3.0, 0.0, 3.0, Some(1.0 / 3.0)),
      SurfaceRow("com.c", 1, 0, 3.0, 0.0, 3.0, Some(1.0 / 3.0)),
      SurfaceRow("com.a", 0, 1, 3.0, 0.0, 3.0, None)
    )) // utilization asc, nulls last, then node asc
  }

  test("orphans on a graph with an isolated node") {
    val graph = DepsGraph(
      nodes = Set(
        Node("p1", NodeKind.`package`),
        Node("p2", NodeKind.`package`),
        Node("p3", NodeKind.`package`),
        Node("iso", NodeKind.`package`),
        Node("p1.T1", NodeKind.`type`, Some("p1"), None),
        Node("p2.T2", NodeKind.`type`, Some("p2"), None),
        Node("p3.T3", NodeKind.`type`, Some("p3"), None),
        Node("iso.T4", NodeKind.`type`, Some("iso"), None)
      ),
      edges = Set(Edge("p1.T1", "p2.T2"), Edge("p2.T2", "p3.T3"))
    )
    val report = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    assertEquals(report.orphans, Seq("iso"))
    assertEquals(report.summary.orphans, 1)
  }

  test("critical path length ignores cycles via condensation") {
    // com.a <-> com.b cycle plus com.c -> com.a chain: condensation is com.c -> {a,b}, length 1
    val graph = DepsGraph(
      nodes = Set(
        Node("com.a", NodeKind.`package`),
        Node("com.b", NodeKind.`package`),
        Node("com.c", NodeKind.`package`),
        Node("com.a.A", NodeKind.`type`, Some("com.a"), None),
        Node("com.b.B", NodeKind.`type`, Some("com.b"), None),
        Node("com.c.C", NodeKind.`type`, Some("com.c"), None)
      ),
      edges = Set(Edge("com.a.A", "com.b.B"), Edge("com.b.B", "com.a.A"), Edge("com.c.C", "com.a.A"))
    )
    val report = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    assertEquals(report.summary.criticalPathLength, 1)
    assertEquals(report.summary.nodesInCycles, 2)
  }

  test("utilization guards: zero fan-in or zero ports yield null") {
    val graph = DepsGraph(
      nodes = Set(
        Node("p1", NodeKind.`package`),
        Node("p2", NodeKind.`package`),
        Node("p3", NodeKind.`package`),
        Node("p1.A", NodeKind.`type`, Some("p1"), None),
        Node("p2.B", NodeKind.`type`, Some("p2"), None),
        Node("p3.C", NodeKind.`type`, Some("p3"), None)
      ),
      edges = Set(Edge("p1.A", "p2.B"))
    ) // all ports 0 (no exposure info, e.g. jdeps data)
    val report = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    report.surface.foreach(r => assertEquals(r.utilization, None))
  }

  test("include/exclude filter the scope before aggregation") {
    val graph = DepsGraph(
      nodes = Set(
        Node("com.a", NodeKind.`package`),
        Node("com.b", NodeKind.`package`),
        Node("com.a.A", NodeKind.`type`, Some("com.a"), None),
        Node("com.b.B", NodeKind.`type`, Some("com.b"), None)
      ),
      edges = Set(Edge("com.a.A", "com.b.B"))
    )
    val report = MetricsCalculator.run(graph, Scope.Packages, includes = Seq("com.a")).toOption.get
    assertEquals(report.summary.nodes, 1)
    assertEquals(report.summary.edges, 0)
  }

  test("cycle: scc key, ext fan in, resolved cut on a plain ring") {
    val graph = DepsGraph(
      nodes = Set(
        Node("p1", NodeKind.`package`),
        Node("p2", NodeKind.`package`),
        Node("p3", NodeKind.`package`),
        Node("outside", NodeKind.`package`),
        Node("p1.A", NodeKind.`type`, Some("p1"), None),
        Node("p2.B", NodeKind.`type`, Some("p2"), None),
        Node("p3.C", NodeKind.`type`, Some("p3"), None),
        Node("outside.D", NodeKind.`type`, Some("outside"), None)
      ),
      edges = Set(
        Edge("p1.A", "p2.B"), Edge("p2.B", "p3.C"), Edge("p3.C", "p1.A"),
        Edge("outside.D", "p1.A")
      )
    )
    val report = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    assertEquals(report.cycles.size, 1)
    val cycle = report.cycles.head
    assertEquals(cycle.id, "scc:p1")
    assertEquals(cycle.members, Seq("p1", "p2", "p3", "p1"))
    assertEquals(cycle.size, 3)
    assertEquals(cycle.extFanIn, 1) // outside -> p1 only
    assertEquals(cycle.minCutsEstimate, 1) // cutting any ring edge resolves a 3-ring
    assertEquals(cycle.cutCandidates.toSet, Set(
      CutCandidate("p1", "p2", 1), CutCandidate("p2", "p3", 1), CutCandidate("p3", "p1", 1)))
  }

  test("cycle: chord edge cut has effect none (redundant with the ring)") {
    val graph = DepsGraph(
      nodes = Set(
        Node("p1", NodeKind.`package`),
        Node("p2", NodeKind.`package`),
        Node("p3", NodeKind.`package`),
        Node("p1.A", NodeKind.`type`, Some("p1"), None),
        Node("p2.B", NodeKind.`type`, Some("p2"), None),
        Node("p3.C", NodeKind.`type`, Some("p3"), None)
      ),
      edges = Set(
        Edge("p1.A", "p2.B"), Edge("p2.B", "p3.C"), Edge("p3.C", "p1.A"), Edge("p1.A", "p3.C")
      )
    )
    val report = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    val cycle = report.cycles.head
    assertEquals(cycle.members, Seq("p1", "p2", "p3", "p1"))
    assertEquals(cycle.cutCandidates, Seq(CutCandidate("p3", "p1", 1))) // only the fully resolving cut
    assertEquals(cycle.minCutsEstimate, 1) // p3 -> p1 resolves it
  }

  test("cycle: partial cut shrinks a two-ring + chord knot; greedy estimate counts cuts") {
    // a<->b and c<->d rings joined by a->c and d->b (one 4-member SCC)
    val graph = DepsGraph(
      nodes = Set(
        Node("a", NodeKind.`package`), Node("b", NodeKind.`package`),
        Node("c", NodeKind.`package`), Node("d", NodeKind.`package`),
        Node("a.A", NodeKind.`type`, Some("a"), None),
        Node("b.B", NodeKind.`type`, Some("b"), None),
        Node("c.C", NodeKind.`type`, Some("c"), None),
        Node("d.D", NodeKind.`type`, Some("d"), None)
      ),
      edges = Set(
        Edge("a.A", "b.B"), Edge("b.B", "a.A"), Edge("c.C", "d.D"), Edge("d.D", "c.C"),
        Edge("a.A", "c.C"), Edge("d.D", "b.B")
      )
    )
    val report = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    val cycle = report.cycles.head
    assertEquals(cycle.size, 4)
    assertEquals(cycle.members, Seq("a", "b", "a"))
    // b -> a strands both endpoints in singleton components (the leftover {c,d} ring
    // contains neither endpoint, so it does not count); c -> d strands the same way,
    // leaving {a,b} behind
    assertEquals(cycle.cutCandidates, Seq(CutCandidate("b", "a", 1), CutCandidate("c", "d", 1)))
    // b -> a resolves the whole 4-knot in one cut: its endpoints land in singleton
    // components (the leftover {c,d} cycle is a separate component that contains
    // neither endpoint, so it does not count)
    assertEquals(cycle.minCutsEstimate, 1)
  }

  test("cycles sorted by size desc, then ext_fan_in desc, then id") {
    val graph = DepsGraph(
      nodes = Set(
        Node("p1", NodeKind.`package`), Node("p2", NodeKind.`package`),
        Node("q1", NodeKind.`package`), Node("q2", NodeKind.`package`), Node("q3", NodeKind.`package`),
        Node("z1", NodeKind.`package`), Node("z2", NodeKind.`package`),
        Node("p1.A", NodeKind.`type`, Some("p1"), None),
        Node("p2.B", NodeKind.`type`, Some("p2"), None),
        Node("q1.C", NodeKind.`type`, Some("q1"), None),
        Node("q2.D", NodeKind.`type`, Some("q2"), None),
        Node("q3.E", NodeKind.`type`, Some("q3"), None),
        Node("z1.F", NodeKind.`type`, Some("z1"), None),
        Node("z2.G", NodeKind.`type`, Some("z2"), None)
      ),
      edges = Set(
        Edge("p1.A", "p2.B"), Edge("p2.B", "p1.A"),
        Edge("q1.C", "q2.D"), Edge("q2.D", "q3.E"), Edge("q3.E", "q1.C"),
        Edge("z1.F", "z2.G"), Edge("z2.G", "z1.F"),
        Edge("q1.C", "p1.A") // ext fan in into the p-knot
      )
    )
    val report = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    assertEquals(report.cycles.map(_.id), Seq("scc:q1", "scc:p1", "scc:z1"))
  }

  test("weighted edges: lighter edges are preferred cut candidates") {
    val graph = DepsGraph(
      nodes = Set(
        Node("p1", NodeKind.`package`), Node("p2", NodeKind.`package`), Node("p3", NodeKind.`package`),
        Node("p1.A", NodeKind.`type`, Some("p1"), None),
        Node("p2.B", NodeKind.`type`, Some("p2"), None),
        Node("p3.C", NodeKind.`type`, Some("p3"), None)
      ),
      edges = Set(
        Edge("p1.A", "p2.B", weight = 10), Edge("p2.B", "p3.C", weight = 1), Edge("p3.C", "p1.A", weight = 1)
      )
    )
    val report = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    val cycle = report.cycles.head
    assertEquals(cycle.cutCandidates.head.source, "p2") // p2 -> p3 (weight 1) before p3 -> p1 (tiebreak source)
    assertEquals(cycle.cutCandidates.map(_.weight), Seq(1, 1, 10))
  }

  test("files scope: include selects the package's files (descend into it)") {
    val graph = DepsGraph(
      nodes = Set(
        Node("com.a", NodeKind.`package`),
        Node("com.b", NodeKind.`package`),
        Node("com.a.A", NodeKind.`type`, Some("com.a"), Some("src/A.scala"), ports = 3.0),
        Node("com.a.A#m", NodeKind.member, Some("com.a.A"), Some("src/A.scala"), ports = 1.0),
        Node("com.b.B", NodeKind.`type`, Some("com.b"), Some("src/B.scala")),
        Node("src/A.scala", NodeKind.file),
        Node("src/B.scala", NodeKind.file)
      ),
      edges = Set(Edge("com.a.A#m", "com.b.B"), Edge("com.b.B", "com.a.A"))
    )
    val report = MetricsCalculator.run(graph, Scope.Files, includes = Seq("com.a")).toOption.get
    assertEquals(report.scope, "files")
    assertEquals(report.summary.nodes, 1) // only src/A.scala
    assertEquals(report.summary.edges, 0) // its only edge leaves the package -> dropped with com.b
    assertEquals(report.surface.head.ports, 4.0)
  }

  test("files scope on jdeps-style data errors") {
    val graph = DepsGraph(
      nodes = Set(Node("com.a.A", NodeKind.`type`, Some("com.a"), None)),
      edges = Set.empty
    )
    assert(MetricsCalculator.run(graph, Scope.Files).isLeft)
  }

  test("skip-tests drops test files before file-scope mapping") {
    val graph = DepsGraph(
      nodes = Set(
        Node("com.a", NodeKind.`package`),
        Node("com.a.A", NodeKind.`type`, Some("com.a"), Some("src/A.scala")),
        Node("com.a.T", NodeKind.`type`, Some("com.a"), Some("src/TSpec.scala")),
        Node("src/A.scala", NodeKind.file),
        Node("src/TSpec.scala", NodeKind.file)
      ),
      edges = Set(Edge("com.a.T", "com.a.A"))
    )
    val report = MetricsCalculator.run(graph, Scope.Files, testPatterns = Some(TestFilter.defaultPatterns)).toOption.get
    assertEquals(report.summary.nodes, 1)
    assertEquals(report.surface.head.node, "src/A.scala")
  }

  test("collapse at files scope merges files by prefix and re-sums ports") {
    val graph = DepsGraph(
      nodes = Set(
        Node("com.a", NodeKind.`package`),
        Node("com.a.A", NodeKind.`type`, Some("com.a"), Some("src/one/A.scala"), ports = 3.0),
        Node("com.a.B", NodeKind.`type`, Some("com.a"), Some("src/two/B.scala"), ports = 1.0),
        Node("src/one/A.scala", NodeKind.file),
        Node("src/two/B.scala", NodeKind.file)
      ),
      edges = Set(Edge("com.a.A", "com.a.B"))
    )
    val report = MetricsCalculator.run(graph, Scope.Files, collapseRules = Seq(CollapseRule.Wild("src"))).toOption.get
    assertEquals(report.summary.nodes, 1)
    assertEquals(report.surface.head.node, "src")
    assertEquals(report.surface.head.ports, 4.0)
    assertEquals(report.summary.edges, 0) // intra-src edge collapses to a self-loop
  }

package ba.sake.codeps

import ba.sake.codeps.model.*
import ba.sake.codeps.report.{Reporter, Severity}
import ba.sake.tupson.{*, given}

class ReporterSpec extends munit.FunSuite:

  // com.a <-> com.b (package cycle), a1 <-> a2 <-> b1 (file cycle),
  // m1 <-> m2/m3 cross-file member cycle, m4 <-> m5 same-file member cycle
  private val nodes = Set(
    Node("com.a", NodeKind.`package`),
    Node("com.b", NodeKind.`package`),
    Node("src/a1.scala", NodeKind.file),
    Node("src/a2.scala", NodeKind.file),
    Node("src/b1.scala", NodeKind.file),
    Node("src/a4.scala", NodeKind.file),
    Node("com.a.A1", NodeKind.`type`, Some("com.a"), Some("src/a1.scala")),
    Node("com.a.A2", NodeKind.`type`, Some("com.a"), Some("src/a2.scala")),
    Node("com.b.B1", NodeKind.`type`, Some("com.b"), Some("src/b1.scala")),
    Node("com.a.A4", NodeKind.`type`, Some("com.a"), Some("src/a4.scala")),
    Node("com.a.A5", NodeKind.`type`, Some("com.a"), Some("src/a4.scala")),
    Node("com.a.A1#m1", NodeKind.member, Some("com.a.A1"), Some("src/a1.scala")),
    Node("com.a.A2#m2", NodeKind.member, Some("com.a.A2"), Some("src/a2.scala")),
    Node("com.b.B1#m3", NodeKind.member, Some("com.b.B1"), Some("src/b1.scala")),
    Node("com.a.A4#m4", NodeKind.member, Some("com.a.A4"), Some("src/a4.scala")),
    Node("com.a.A5#m5", NodeKind.member, Some("com.a.A5"), Some("src/a4.scala"))
  )
  private val edges = Set(
    Edge("com.a.A1#m1", "com.b.B1#m3"),
    Edge("com.b.B1#m3", "com.a.A1#m1"),
    Edge("com.a.A1#m1", "com.a.A2#m2"),
    Edge("com.a.A2#m2", "com.a.A1#m1"),
    Edge("com.a.A4#m4", "com.a.A5#m5"),
    Edge("com.a.A5#m5", "com.a.A4#m4")
  )
  private val graph = DepsGraph(nodes, edges)
  private val report = Reporter.run(graph, Nil, Nil, Nil)

  test("package cycles are bad") {
    val pkg = report.levels("package")
    assert(pkg.cycles.nonEmpty)
    assert(pkg.cycles.forall(_.severity == Severity.bad))
    assert(pkg.cycles.head.members.toSet.contains("com.a"))
    assert(pkg.cycles.head.members.toSet.contains("com.b"))
  }

  test("file cycles are meh") {
    val file = report.levels("file")
    assert(file.cycles.nonEmpty)
    assert(file.cycles.forall(_.severity == Severity.meh))
  }

  test("cross-file member cycles are meh") {
    val member = report.levels("member")
    val crossFile = member.cycles.filter(_.severity == Severity.meh)
    assert(crossFile.nonEmpty)
    assert(crossFile.exists(_.members.toSet.contains("com.a.A1#m1")))
  }

  test("same-file member cycles are fine") {
    val member = report.levels("member")
    val fine = member.cycles.filter(_.severity == Severity.fine)
    assertEquals(fine.map(_.members.toSet), Seq(Set("com.a.A4#m4", "com.a.A5#m5")))
  }

  test("type-level cycles: same-file fine, cross-file meh") {
    val t = report.levels("type")
    val fine = t.cycles.filter(_.severity == Severity.fine)
    assertEquals(fine.map(_.members.toSet), Seq(Set("com.a.A4", "com.a.A5")))
    assert(t.cycles.exists(_.severity == Severity.meh))
  }

  test("jdeps-style nodes without a file are graded meh") {
    val jdepsGraph = DepsGraph(
      Set(
        Node("com.x.X", NodeKind.`type`, Some("com.x")),
        Node("com.y.Y", NodeKind.`type`, Some("com.y"))
      ),
      Set(Edge("com.x.X", "com.y.Y"), Edge("com.y.Y", "com.x.X"))
    )
    val t = Reporter.run(jdepsGraph, Nil, Nil, Nil).levels("type")
    assert(t.cycles.nonEmpty)
    assert(t.cycles.forall(_.severity == Severity.meh))
  }

  test("member level has no embedded graph or metrics") {
    val member = report.levels("member")
    assertEquals(member.graph, None)
    assertEquals(member.metrics, None)
  }

  test("package level embeds graph and metrics with hub scores") {
    val pkg = report.levels("package")
    assert(pkg.graph.isDefined)
    assert(pkg.graph.get.nodes.exists(_.id == "com.a"))
    val metrics = pkg.metrics.get
    assertEquals(metrics("com.a").in, 1)
    assertEquals(metrics("com.a").out, 1)
    assertEquals(metrics("com.a").hub, 1)
  }

  test("suggestions: break edges, hardest knots, easy wins at package level") {
    val pkg = report.levels("package")
    val suggestions = pkg.suggestions
    assert(suggestions.breakEdges.nonEmpty)
    assertEquals(suggestions.hardestKnots.toSet, Set("com.a", "com.b"))
    assertEquals(suggestions.easyWins.toSet, Set("com.a", "com.b"))
    // member level has break edges but no knots/wins
    val member = report.levels("member")
    assert(member.suggestions.breakEdges.nonEmpty)
    assertEquals(member.suggestions.hardestKnots, Seq.empty)
    assertEquals(member.suggestions.easyWins, Seq.empty)
  }

  test("cycle edges are in true dependency order") {
    val pkg = report.levels("package")
    val cycle = pkg.cycles.head
    // every consecutive pair of the cycle must be an actual edge
    val edgesOf = cycle.edges.toSet
    assert(cycle.members.sliding(2).forall { case Seq(a, b) => edgesOf.contains(Edge(a, b)) })
    // the cycle closes: last member == first member
    assertEquals(cycle.members.head, cycle.members.last)
  }

  test("break candidate is the member with the lowest degree") {
    val member = report.levels("member")
    val fine = member.cycles.find(_.severity == Severity.fine).get
    // m4 and m5 both have degree 1 inside the whole member graph; tie broken by id
    assertEquals(fine.breakCandidate, "com.a.A4#m4")
  }

  test("collapse-rewritten cycle members are graded conservatively meh") {
    val collapsed = Reporter.run(graph, Nil, Nil, Seq(CollapseRule.parse("com.a.**").toOption.get)).levels("member")
    assert(collapsed.cycles.nonEmpty)
    assert(collapsed.cycles.forall(_.severity == Severity.meh))
  }

  test("json round-trip") {
    assertEquals(report.toJson(spaces = 2, sort = true).parseJson[ba.sake.codeps.report.AnalysisReport], report)
  }

  test("severities serialize as lowercase strings") {
    val json = report.toJson(spaces = 0, sort = false)
    assert(json.contains("\"severity\":\"bad\""))
    assert(json.contains("\"severity\":\"meh\""))
    assert(json.contains("\"severity\":\"fine\""))
  }

  test("run is deterministic") {
    assertEquals(Reporter.run(graph, Nil, Nil, Nil), Reporter.run(graph, Nil, Nil, Nil))
  }

  test("testPatterns exclude matching nodes before every level") {
    val withTest = graph.copy(
      nodes = graph.nodes ++ Set(
        Node("src/MySpec.scala", NodeKind.file),
        Node("com.a.MySpec", NodeKind.`type`, Some("com.a"), Some("src/MySpec.scala")),
        Node("com.a.MySpec#verify", NodeKind.member, Some("com.a.MySpec"), Some("src/MySpec.scala"))
      ),
      edges = graph.edges + Edge("com.a.MySpec#verify", "com.b.B1#m3")
    )
    val filtered = Reporter.run(withTest, Nil, Nil, Nil, Some(Seq("**/*Spec.scala")))
    assert(!filtered.levels("file").graph.get.nodes.exists(_.id == "src/MySpec.scala"))
    assert(!filtered.levels("type").graph.get.nodes.exists(_.id == "com.a.MySpec"))
    assert(filtered.levels("file").graph.get.nodes.exists(_.id == "src/a1.scala"))
    assertEquals(
      filtered.levels("package").cycles.map(_.members.toSet),
      report.levels("package").cycles.map(_.members.toSet)
    )
  }

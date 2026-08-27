package ba.sake.codeps.graph

import ba.sake.codeps.model.*

class AggregatorSpec extends munit.FunSuite:

  private val granular = DepsGraph(
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
      Edge("com.a.A#m", "com.a.A") // intra-file, becomes a self-loop and is dropped
    )
  )

  test("type/member nodes collapse into file nodes; ports sum; edges re-wire") {
    val agg = Aggregator.fileLevel(granular)
    assertEquals(agg.nodes.map(_.kind).toSet, Set(NodeKind.`package`, NodeKind.file))
    val a = agg.nodes.find(_.id == "src/A.scala").get
    assertEquals(a, Node("src/A.scala", NodeKind.file, Some("com.a"), ports = 4.0, mutPorts = 1.0))
    assertEquals(agg.nodes.find(_.id == "src/B.scala").get.ports, 0.5)
    assertEquals(agg.edges, Set(Edge("src/A.scala", "src/B.scala"), Edge("src/B.scala", "src/A.scala")))
  }

  test("file-less type nodes collapse into their root package (jdeps shape)") {
    val jdepsLike = DepsGraph(
      nodes = Set(
        Node("com.a", NodeKind.`package`),
        Node("com.b", NodeKind.`package`),
        Node("com.a.A", NodeKind.`type`, Some("com.a"), None),
        Node("com.b.B", NodeKind.`type`, Some("com.b"), None)
      ),
      edges = Set(Edge("com.a.A", "com.b.B"))
    )
    val agg = Aggregator.fileLevel(jdepsLike)
    assertEquals(agg.nodes.map(_.id), Set("com.a", "com.b"))
    assertEquals(agg.edges, Set(Edge("com.a", "com.b")))
  }

  test("edge weights sum onto the same file pair") {
    val g = granular.copy(edges = granular.edges + Edge("com.a.A", "com.b.B"))
    val agg = Aggregator.fileLevel(g)
    assertEquals(agg.edges.count(_.source == "src/A.scala"), 1)
    assertEquals(agg.edges.find(e => e.source == "src/A.scala" && e.target == "src/B.scala").get.weight, 2)
  }

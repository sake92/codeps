package ba.sake.codeps.graph

import ba.sake.codeps.model.Edge

class TarjanSccSpec extends munit.FunSuite:

  private def comps(nodes: Set[String], edges: Set[Edge]) =
    TarjanScc.components(nodes, edges).map(_.toSeq.sorted)

  test("ring of three is one component") {
    val nodes = Set("a", "b", "c")
    val edges = Set(Edge("a", "b"), Edge("b", "c"), Edge("c", "a"))
    assertEquals(comps(nodes, edges), Seq(Seq("a", "b", "c")))
  }

  test("two-node mutual cycle plus a tail: one multi-member component, tail is a singleton") {
    val nodes = Set("a", "b", "c")
    val edges = Set(Edge("a", "b"), Edge("b", "a"), Edge("b", "c"))
    assertEquals(comps(nodes, edges), Seq(Seq("a", "b"), Seq("c")))
  }

  test("chorded square is one component") {
    val nodes = Set("a", "b", "c", "d")
    val edges = Set(Edge("a", "b"), Edge("b", "c"), Edge("c", "d"), Edge("d", "a"), Edge("a", "c"))
    assertEquals(comps(nodes, edges), Seq(Seq("a", "b", "c", "d")))
  }

  test("disjoint cycles and singletons, sorted by min member") {
    val nodes = Set("a", "b", "c", "x", "y", "iso")
    val edges = Set(Edge("a", "b"), Edge("b", "a"), Edge("c", "x"), Edge("x", "y"), Edge("y", "c"))
    assertEquals(comps(nodes, edges), Seq(Seq("a", "b"), Seq("c", "x", "y"), Seq("iso")))
  }

  test("acyclic graph: all singletons") {
    val nodes = Set("a", "b", "c")
    val edges = Set(Edge("a", "b"), Edge("b", "c"))
    assertEquals(comps(nodes, edges), Seq(Seq("a"), Seq("b"), Seq("c")))
  }

  test("knots returns only multi-member components") {
    val nodes = Set("a", "b", "c")
    val edges = Set(Edge("a", "b"), Edge("b", "a"), Edge("b", "c"))
    assertEquals(TarjanScc.knots(nodes, edges).map(_.toSeq.sorted), Seq(Seq("a", "b")))
  }

  test("output is deterministic across runs") {
    val nodes = (1 to 30).map(i => f"n$i%02d").toSet
    val sorted = nodes.toSeq.sorted
    val edges = sorted.map(n => Edge(n, sorted((n.hashCode.abs + 7) % sorted.size))).toSet
    assertEquals(TarjanScc.components(nodes, edges), TarjanScc.components(nodes, edges))
  }

package ba.sake.codeps

import ba.sake.codeps.graph.{CycleDetector, GraphBuilder}
import ba.sake.codeps.model.Edge

class CycleDetectorSpec extends munit.FunSuite:

  private def cyclesOf(nodes: Set[String], edges: Set[Edge]): Seq[Seq[String]] =
    CycleDetector.detect(GraphBuilder.build(nodes, edges))

  test("two-node cycle is detected") {
    val res = cyclesOf(Set("a", "b"), Set(Edge("a", "b"), Edge("b", "a")))
    assertEquals(res, Seq(Seq("a", "b", "a")))
  }

  test("three-node cycle with dangling tail") {
    val res = cyclesOf(
      Set("a", "b", "c", "tail"),
      Set(Edge("a", "b"), Edge("b", "c"), Edge("c", "a"), Edge("tail", "a"))
    )
    assertEquals(res, Seq(Seq("a", "b", "c", "a")))
  }

  test("disjoint cycles both reported, sorted") {
    val res = cyclesOf(
      Set("a", "b", "x", "y"),
      Set(Edge("a", "b"), Edge("b", "a"), Edge("x", "y"), Edge("y", "x"))
    )
    assertEquals(res, Seq(Seq("a", "b", "a"), Seq("x", "y", "x")))
  }

  test("cycle order follows actual edges, not alphabetical") {
    // a -> c -> b -> a is the real cycle; alphabetical sort would claim a -> b -> c
    val res = cyclesOf(
      Set("a", "b", "c"),
      Set(Edge("a", "c"), Edge("c", "b"), Edge("b", "a"))
    )
    assertEquals(res, Seq(Seq("a", "c", "b", "a")))
  }

  test("chorded SCC reports one representative cycle") {
    // a<->b and b<->c: strongly connected, but the walk reports a single cycle
    val res = cyclesOf(
      Set("a", "b", "c"),
      Set(Edge("a", "b"), Edge("b", "a"), Edge("b", "c"), Edge("c", "b"))
    )
    assertEquals(res, Seq(Seq("b", "c", "b")))
  }

  test("acyclic graph yields no cycles") {
    val res = cyclesOf(Set("a", "b", "c"), Set(Edge("a", "b"), Edge("b", "c")))
    assertEquals(res, Seq.empty)
  }

  test("isolated vertices are ignored") {
    val res = cyclesOf(Set("a", "b", "iso"), Set(Edge("a", "b"), Edge("b", "a")))
    assertEquals(res, Seq(Seq("a", "b", "a")))
  }

  test("self-loop (dropped by GraphBuilder) is not a cycle") {
    val res = cyclesOf(Set("a"), Set(Edge("a", "a")))
    assertEquals(res, Seq.empty)
  }

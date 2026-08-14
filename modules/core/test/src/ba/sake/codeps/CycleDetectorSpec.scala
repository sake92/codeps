package ba.sake.codeps

import ba.sake.codeps.graph.{CycleDetector, GraphBuilder}
import ba.sake.codeps.model.PackageEdge

class CycleDetectorSpec extends munit.FunSuite:

  private def cyclesOf(nodes: Set[String], edges: Set[PackageEdge]): Seq[Seq[String]] =
    CycleDetector.detect(GraphBuilder.build(nodes, edges))

  test("two-node cycle is detected") {
    val res = cyclesOf(Set("a", "b"), Set(PackageEdge("a", "b"), PackageEdge("b", "a")))
    assertEquals(res, Seq(Seq("a", "b", "a")))
  }

  test("three-node cycle with dangling tail") {
    val res = cyclesOf(
      Set("a", "b", "c", "tail"),
      Set(PackageEdge("a", "b"), PackageEdge("b", "c"), PackageEdge("c", "a"), PackageEdge("tail", "a"))
    )
    assertEquals(res, Seq(Seq("a", "b", "c", "a")))
  }

  test("disjoint cycles both reported, sorted") {
    val res = cyclesOf(
      Set("a", "b", "x", "y"),
      Set(PackageEdge("a", "b"), PackageEdge("b", "a"), PackageEdge("x", "y"), PackageEdge("y", "x"))
    )
    assertEquals(res, Seq(Seq("a", "b", "a"), Seq("x", "y", "x")))
  }

  test("cycle order follows actual edges, not alphabetical") {
    // a -> c -> b -> a is the real cycle; alphabetical sort would claim a -> b -> c
    val res = cyclesOf(
      Set("a", "b", "c"),
      Set(PackageEdge("a", "c"), PackageEdge("c", "b"), PackageEdge("b", "a"))
    )
    assertEquals(res, Seq(Seq("a", "c", "b", "a")))
  }

  test("chorded SCC reports one representative cycle") {
    // a<->b and b<->c: strongly connected, but the walk reports a single cycle
    val res = cyclesOf(
      Set("a", "b", "c"),
      Set(PackageEdge("a", "b"), PackageEdge("b", "a"), PackageEdge("b", "c"), PackageEdge("c", "b"))
    )
    assertEquals(res, Seq(Seq("b", "c", "b")))
  }

  test("acyclic graph yields no cycles") {
    val res = cyclesOf(Set("a", "b", "c"), Set(PackageEdge("a", "b"), PackageEdge("b", "c")))
    assertEquals(res, Seq.empty)
  }

  test("isolated vertices are ignored") {
    val res = cyclesOf(Set("a", "b", "iso"), Set(PackageEdge("a", "b"), PackageEdge("b", "a")))
    assertEquals(res, Seq(Seq("a", "b", "a")))
  }

  test("self-loop (dropped by GraphBuilder) is not a cycle") {
    val res = cyclesOf(Set("a"), Set(PackageEdge("a", "a")))
    assertEquals(res, Seq.empty)
  }

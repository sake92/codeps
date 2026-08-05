package ba.sake.codeps

import ba.sake.codeps.model.*
import ba.sake.codeps.graph.GraphBuilder
import scala.jdk.CollectionConverters.*

class GraphBuilderSpec extends munit.FunSuite:

  test("builds directed graph with isolated vertices preserved") {
    val nodes = Set("a.b", "a.c", "isolated.x")
    val edges = Set(PackageEdge("a.b", "a.c"))
    val g = GraphBuilder.build(nodes, edges)
    assertEquals(g.vertexSet().asScala.toSet, nodes)
    assertEquals(g.edgeSet().asScala.size, 1)
    val e = g.edgeSet().asScala.head
    assertEquals(g.getEdgeSource(e), "a.b")
    assertEquals(g.getEdgeTarget(e), "a.c")
    assert(!g.containsEdge("isolated.x", "a.b"))
  }

  test("duplicate edges are deduplicated") {
    val nodes = Set("a.b", "a.c")
    val edges = Set(PackageEdge("a.b", "a.c"), PackageEdge("a.b", "a.c"))
    val g = GraphBuilder.build(nodes, edges)
    assertEquals(g.edgeSet().asScala.size, 1)
  }

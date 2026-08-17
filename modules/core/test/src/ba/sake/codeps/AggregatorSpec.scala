package ba.sake.codeps

import ba.sake.codeps.model.*
import ba.sake.codeps.graph.Aggregator
import Aggregator.Level

class AggregatorSpec extends munit.FunSuite:

  val graph = DepsGraph(
    Set(
      Node("com.example.a", NodeKind.`package`),
      Node("src/a/Foo.scala", NodeKind.file),
      Node("com.example.a.Foo", NodeKind.`type`, Some("com.example.a"), Some("src/a/Foo.scala")),
      Node("com.example.a.Foo#doWork", NodeKind.member, Some("com.example.a.Foo"), Some("src/a/Foo.scala")),
      Node("com.example.a.topLevelHelper", NodeKind.member, Some("com.example.a"), Some("src/a/Helpers.scala"))
    ),
    Set(Edge("com.example.a.Foo#doWork", "com.example.a.topLevelHelper"))
  )

  test("member level is identity") {
    assertEquals(Aggregator.aggregate(graph, Level.Member), (graph.nodes.map(_.id), graph.edges))
  }

  test("type level lifts members to their parent, drops files and standalone packages") {
    val (nodes, edges) = Aggregator.aggregate(graph, Level.Type)
    // com.example.a survives only because topLevelHelper (a package member) falls back to it
    assertEquals(nodes, Set("com.example.a", "com.example.a.Foo"))
    assertEquals(edges, Set(Edge("com.example.a.Foo", "com.example.a")))
  }

  test("file level lifts types and members to their file attribute, drops packages") {
    val (nodes, edges) = Aggregator.aggregate(graph, Level.File)
    assertEquals(nodes, Set("src/a/Foo.scala", "src/a/Helpers.scala"))
    assertEquals(edges, Set(Edge("src/a/Foo.scala", "src/a/Helpers.scala")))
  }

  test("file level falls back to root package when file attribute missing (jdeps data)") {
    val g = DepsGraph(
      Set(Node("com.example.a", NodeKind.`package`), Node("com.example.a.Foo", NodeKind.`type`, Some("com.example.a"))),
      Set(Edge("com.example.a.Foo", "com.example.a"))
    )
    val (nodes, edges) = Aggregator.aggregate(g, Level.File)
    assertEquals(nodes, Set("com.example.a"))
    assertEquals(edges, Set.empty[Edge]) // package endpoint dropped at file level
  }

  test("package level lifts everything to root packages, dropping files") {
    val (nodes, edges) = Aggregator.aggregate(graph, Level.Package)
    assertEquals(nodes, Set("com.example.a"))
    assertEquals(edges, Set.empty[Edge]) // both endpoints collapse into one package
  }

  test("degraded data: members without parents, broken chains and dangling endpoints are dropped") {
    val g = DepsGraph(
      Set(
        Node("com.example.a", NodeKind.`package`),
        Node("com.example.a.Orphan", NodeKind.member), // no parentId
        Node("com.example.a.Broken", NodeKind.`type`, Some("com.example.a.Missing")), // parentId not in graph
        Node("com.example.a.Child#m", NodeKind.member, Some("com.example.a.Broken")),
        Node("com.example.a.Foo", NodeKind.`type`, Some("com.example.a"))
      ),
      Set(
        Edge("com.example.a.Orphan", "com.example.a.Foo"),
        Edge("com.example.a.Child#m", "com.example.a.Foo"),
        Edge("com.example.a.Foo", "com.example.a.Nonexistent") // endpoint not in graph
      )
    )
    // type level: member without parentId is dropped
    val (typeNodes, typeEdges) = Aggregator.aggregate(g, Level.Type)
    assertEquals(typeNodes, Set("com.example.a.Broken", "com.example.a.Foo"))
    assertEquals(typeEdges, Set(Edge("com.example.a.Broken", "com.example.a.Foo")))
    // package level: member whose parent chain references a missing node is dropped
    val (pkgNodes, pkgEdges) = Aggregator.aggregate(g, Level.Package)
    assertEquals(pkgNodes, Set("com.example.a"))
    assertEquals(pkgEdges, Set.empty[Edge])
  }

  test("cyclic parentId chains terminate and are excluded from package level") {
    // self-cycle: node whose parentId points at itself
    val g1 = DepsGraph(
      Set(
        Node("com.example.a", NodeKind.`package`),
        Node("com.example.a.C", NodeKind.`type`, Some("com.example.a.C"))
      ),
      Set.empty[Edge]
    )
    val (nodes1, edges1) = Aggregator.aggregate(g1, Level.Package)
    assert(!nodes1.contains("com.example.a.C"))
    assertEquals(edges1, Set.empty[Edge])
    // 2-node cycle: no package ancestor reachable
    val g2 = DepsGraph(
      Set(
        Node("a.b.X", NodeKind.`type`, Some("a.b.Y")),
        Node("a.b.Y", NodeKind.`type`, Some("a.b.X"))
      ),
      Set.empty[Edge]
    )
    val (nodes2, edges2) = Aggregator.aggregate(g2, Level.Package)
    assert(!nodes2.contains("a.b.X"))
    assert(!nodes2.contains("a.b.Y"))
    assertEquals(edges2, Set.empty[Edge])
  }

  test("edges collapsing onto the same pair are merged with summed weights") {
    val g = DepsGraph(
      Set(
        Node("com.example.a", NodeKind.`package`),
        Node("com.example.b", NodeKind.`package`),
        Node("com.example.a.Foo", NodeKind.`type`, Some("com.example.a")),
        Node("com.example.a.Bar", NodeKind.`type`, Some("com.example.a")),
        Node("com.example.b.Baz", NodeKind.`type`, Some("com.example.b")),
        Node("com.example.b.Qux", NodeKind.`type`, Some("com.example.b"))
      ),
      Set(
        Edge("com.example.a.Foo", "com.example.b.Baz"),
        Edge("com.example.a.Foo", "com.example.b.Qux"),
        Edge("com.example.a.Bar", "com.example.b.Baz")
      )
    )
    val (nodes, edges) = Aggregator.aggregate(g, Level.Package)
    assertEquals(nodes, Set("com.example.a", "com.example.b"))
    assertEquals(edges, Set(Edge("com.example.a", "com.example.b", 3)))
  }

  test("package nodes are dropped at file and type levels, kept at member level") {
    val g = DepsGraph(
      Set(
        Node("com.example.a", NodeKind.`package`),
        Node("com.example.b", NodeKind.`package`),
        Node("src/b/Bar.scala", NodeKind.file),
        Node("com.example.a.Foo", NodeKind.`type`, Some("com.example.a"), Some("src/a/Foo.scala")),
        Node("com.example.a.Foo#m", NodeKind.member, Some("com.example.a.Foo"), Some("src/a/Foo.scala"))
      ),
      Set(
        Edge("com.example.a.Foo#m", "com.example.b"), // edge INTO a package
        Edge("com.example.b", "com.example.a.Foo")     // edge OUT of a package
      )
    )
    val (fileNodes, fileEdges) = Aggregator.aggregate(g, Level.File)
    assertEquals(fileNodes, Set("src/a/Foo.scala", "src/b/Bar.scala"))
    assertEquals(fileEdges, Set.empty[Edge]) // both edges lose their package endpoint
    val (typeNodes, typeEdges) = Aggregator.aggregate(g, Level.Type)
    assertEquals(typeNodes, Set("com.example.a.Foo"))
    assertEquals(typeEdges, Set.empty[Edge]) // both edges lose their package endpoint
    val (memberNodes, _) = Aggregator.aggregate(g, Level.Member)
    assertEquals(memberNodes, g.nodes.map(_.id)) // member level is identity, packages kept
  }

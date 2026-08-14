package ba.sake.codeps

import ba.sake.codeps.model.*
import ba.sake.codeps.graph.Filter

class FilterSpec extends munit.FunSuite:

  val graph = DepsGraph(
    Set(
      Node("com.example.modules.module1", NodeKind.`package`),
      Node("com.example.modules.module1.Service1", NodeKind.`type`, Some("com.example.modules.module1"), Some("src/Service1.scala")),
      Node("com.example.modules.module2", NodeKind.`package`),
      Node("com.example.modules.module2.Service2", NodeKind.`type`, Some("com.example.modules.module2"), Some("src/Service2.scala")),
      Node("org.thirdparty", NodeKind.`package`),
      Node("org.thirdparty.Ext", NodeKind.`type`, Some("org.thirdparty"), Some("src/Ext.scala"))
    ),
    Set(
      Edge("com.example.modules.module1.Service1", "com.example.modules.module2.Service2"),
      Edge("com.example.modules.module2.Service2", "org.thirdparty.Ext")
    )
  )

  test("include defines the universe via root package; edges kept when both endpoints in universe") {
    val filtered = Filter(graph, Seq("com.example.modules"), Nil)
    assertEquals(
      filtered.nodes.map(_.id),
      Set("com.example.modules.module1", "com.example.modules.module1.Service1",
        "com.example.modules.module2", "com.example.modules.module2.Service2")
    )
    assertEquals(filtered.edges, Set(Edge("com.example.modules.module1.Service1", "com.example.modules.module2.Service2")))
  }

  test("no include keeps all nodes") {
    assertEquals(Filter(graph, Nil, Nil), graph)
  }

  test("exclude removes from universe and wins over include") {
    val filtered = Filter(graph, Seq("com.example.modules"), Seq("com.example.modules.module2"))
    assertEquals(filtered.nodes.map(_.id), Set("com.example.modules.module1", "com.example.modules.module1.Service1"))
    assertEquals(filtered.edges, Set.empty[Edge])
  }

  test("self-edges are dropped") {
    val withLoop = graph.copy(edges = graph.edges + Edge("com.example.modules.module1.Service1", "com.example.modules.module1.Service1"))
    val filtered = Filter(withLoop, Seq("com.example.modules"), Nil)
    assertEquals(filtered.edges, Set(Edge("com.example.modules.module1.Service1", "com.example.modules.module2.Service2")))
  }

  test("include pattern matches the root package itself and subpackages") {
    val withUtil = graph.copy(nodes = graph.nodes + Node("com.example.util", NodeKind.`package`))
    assert(Filter(withUtil, Seq("com.example"), Nil).nodes.map(_.id).contains("com.example.util"))
    assert(!Filter(withUtil, Seq("com.example"), Nil).nodes.map(_.id).contains("org.thirdparty"))
  }

  test("multi-hop parent walk: member under type under package is matched by root package include") {
    val withMember = graph.copy(
      nodes = graph.nodes + Node("com.example.modules.module2.Service2#run", NodeKind.member, Some("com.example.modules.module2.Service2"), Some("src/Service2.scala"))
    )
    val filtered = Filter(withMember, Seq("com.example.modules"), Nil)
    assertEquals(
      filtered.nodes.map(_.id),
      Set("com.example.modules.module1", "com.example.modules.module1.Service1",
        "com.example.modules.module2", "com.example.modules.module2.Service2",
        "com.example.modules.module2.Service2#run")
    )
    assertEquals(filtered.edges, Set(Edge("com.example.modules.module1.Service1", "com.example.modules.module2.Service2")))
  }

  test("dangling parentId: unresolvable root package → dropped with include, kept without") {
    val withOrphan = graph.copy(nodes = graph.nodes + Node("com.example.orphan.Thing", NodeKind.`type`, Some("com.example.missing")))
    val filtered = Filter(withOrphan, Seq("com.example.orphan"), Nil)
    assert(!filtered.nodes.map(_.id).contains("com.example.orphan.Thing"))
    assert(Filter(withOrphan, Nil, Nil).nodes.map(_.id).contains("com.example.orphan.Thing"))
  }

  test("file node without parentId: dropped with include, kept without") {
    val withFile = graph.copy(nodes = graph.nodes + Node("src/Service1.scala", NodeKind.file))
    assert(!Filter(withFile, Seq("com.example.modules"), Nil).nodes.map(_.id).contains("src/Service1.scala"))
    assert(Filter(withFile, Nil, Nil).nodes.map(_.id).contains("src/Service1.scala"))
  }

package ba.sake.codeps

import ba.sake.codeps.model.*
import ba.sake.tupson.{*, given}

class ModelSpec extends munit.FunSuite:

  val graph = DepsGraph(
    Set(
      Node("com.example.a", NodeKind.`package`),
      Node("src/com/example/a/Foo.scala", NodeKind.file),
      Node("com.example.a.Foo", NodeKind.`type`, Some("com.example.a"), Some("src/com/example/a/Foo.scala")),
      Node("com.example.a.Foo#doWork", NodeKind.member, Some("com.example.a.Foo"), Some("src/com/example/a/Foo.scala")),
      Node("com.example.a.topLevelHelper", NodeKind.member, Some("com.example.a"), Some("src/com/example/a/Helpers.scala"))
    ),
    Set(Edge("com.example.a.Foo#doWork", "com.example.a.topLevelHelper"))
  )

  test("json round-trip") {
    assertEquals(graph.toJson(spaces = 2, sort = true).parseJson[DepsGraph], graph)
  }

  test("edge weight defaults to 1 when missing in json") {
    val json = """{"nodes": [], "edges": [{"source": "a", "target": "b"}]}"""
    assertEquals(json.parseJson[DepsGraph].edges, Set(Edge("a", "b", 1)))
  }

  test("weighted edges round-trip through json") {
    val weighted = graph.copy(edges = Set(Edge("com.example.a.Foo#doWork", "com.example.a.topLevelHelper", 3)))
    assertEquals(weighted.toJson(spaces = 2, sort = true).parseJson[DepsGraph], weighted)
    assert(weighted.toJson(spaces = 0, sort = false).contains("\"weight\":3"))
  }

  test("kinds serialize as lowercase strings") {
    val json = graph.toJson(spaces = 0, sort = false)
    assert(json.contains("\"kind\":\"package\""))
    assert(json.contains("\"kind\":\"file\""))
    assert(json.contains("\"kind\":\"type\""))
    assert(json.contains("\"kind\":\"member\""))
  }

  test("unknown kind fails to parse") {
    val json = """{"nodes":[{"id":"a","kind":"banana"}],"edges":[]}"""
    val err = intercept[ba.sake.tupson.TupsonException](json.parseJson[DepsGraph])
    assert(err.getMessage.contains("banana"))
  }

  test("merge unions nodes and edges") {
    val other = DepsGraph(Set(Node("com.example.b", NodeKind.`package`)), Set(Edge("com.example.a", "com.example.b")))
    val merged = graph.merge(other)
    assert(merged.nodes.contains(Node("com.example.b", NodeKind.`package`)))
    assert(merged.edges.contains(Edge("com.example.a", "com.example.b")))
  }

  test("withoutDanglingEdges drops edges with missing endpoints") {
    val g = DepsGraph(
      Set(Node("com.example.a", NodeKind.`package`)),
      Set(Edge("com.example.a", "scala.collection.immutable"), Edge("com.example.a", "com.example.a"))
    )
    assertEquals(g.withoutDanglingEdges.edges, Set(Edge("com.example.a", "com.example.a")))
  }

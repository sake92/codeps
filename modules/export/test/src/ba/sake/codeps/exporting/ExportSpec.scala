package ba.sake.codeps.exporting

import ba.sake.codeps.graph.GraphBuilder
import ba.sake.codeps.model.Edge

class ExportSpec extends munit.FunSuite:

  val g = GraphBuilder.build(
    Set("com.example.a", "com.example.b", "isolated.pkg"),
    Set(Edge("com.example.a", "com.example.b"))
  )

  test("dot") {
    val expected = """digraph deps {
                     |  "com.example.a" -> "com.example.b";
                     |  "isolated.pkg";
                     |}
                     |""".stripMargin
    assertEquals(DotExporter.render(g), expected)
  }

  test("dot cycle comment lists cycle members") {
    val cycleGraph = GraphBuilder.build(
      Set("com.example.a", "com.example.b"),
      Set(Edge("com.example.a", "com.example.b"), Edge("com.example.b", "com.example.a"))
    )
    val expected = """digraph deps {
                     |  // cycles: com.example.a -> com.example.b -> com.example.a
                     |  "com.example.a" -> "com.example.b";
                     |  "com.example.b" -> "com.example.a";
                     |}
                     |""".stripMargin
    assertEquals(
      DotExporter.render(cycleGraph, Seq(Seq("com.example.a", "com.example.b", "com.example.a"))),
      expected
    )
  }

  test("mermaid cycle comment lists cycle members") {
    val cycleGraph = GraphBuilder.build(
      Set("com.example.a", "com.example.b"),
      Set(Edge("com.example.a", "com.example.b"), Edge("com.example.b", "com.example.a"))
    )
    val expected = """flowchart LR
                     |%% cycles: com.example.a -> com.example.b -> com.example.a
                     |  N0["com.example.a"]
                     |  N1["com.example.b"]
                     |  N0 --> N1
                     |  N1 --> N0
                     |""".stripMargin
    assertEquals(
      MermaidExporter.render(cycleGraph, Seq(Seq("com.example.a", "com.example.b", "com.example.a"))),
      expected
    )
  }

  test("mermaid") {
    val expected = """flowchart LR
                     |  N0["com.example.a"]
                     |  N1["com.example.b"]
                     |  N2["isolated.pkg"]
                     |  N0 --> N1
                     |""".stripMargin
    assertEquals(MermaidExporter.render(g), expected)
  }

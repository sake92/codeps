package ba.sake.codeps.exporting

import ba.sake.codeps.graph.GraphBuilder
import ba.sake.codeps.model.PackageEdge

class ExportSpec extends munit.FunSuite:

  val g = GraphBuilder.build(
    Set("com.example.a", "com.example.b", "isolated.pkg"),
    Set(PackageEdge("com.example.a", "com.example.b"))
  )

  test("dot") {
    val expected = """digraph deps {
                     |  "com.example.a" -> "com.example.b";
                     |  "isolated.pkg";
                     |}
                     |""".stripMargin
    assertEquals(DotExporter.render(g), expected)
  }

  test("json") {
    val expected = """{
                     |  "nodes": ["com.example.a", "com.example.b", "isolated.pkg"],
                     |  "edges": [["com.example.a", "com.example.b"]]
                     |}
                     |""".stripMargin
    assertEquals(JsonExporter.render(g), expected)
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

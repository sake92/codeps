package ba.sake.codeps.report

import ba.sake.codeps.artifacts.models.*
import ba.sake.codeps.model.{DeclarationSurface as GraphDeclarations}
import ba.sake.codeps.model.ExportGraphOps.*
import ba.sake.tupson.{*, given}
import java.time.Instant

class GeneratedArtifactsSpec extends munit.FunSuite {
  private val exportJson =
    """{"packages":{"nodes":[{"id":"p","ports":0,"mutPorts":0,"declarationSurface":{}}],"edges":[{"source":"p","target":"q"}]},"files":{"nodes":[],"edges":[]}}"""

  test(
    "generated export feeds graph analysis with default weights and counters"
  ) {
    val graph = exportJson.parseJson[ExportGraph]
    assertEquals(graph.packageDeps.edges.head.weight, 1)
    assertEquals(
      graph.packageDeps.nodes.head.declarationSurface,
      GraphDeclarations()
    )
    assertEquals(graph.toJson.parseJson[ExportGraph], graph)
    val weighted = exportJson
      .replace("\"target\":\"q\"", "\"target\":\"q\",\"weight\":7")
      .parseJson[ExportGraph]
    assertEquals(weighted.packageDeps.edges.head.weight, 7)
  }

  test("generated findings carry typed timestamps, versions and enums") {
    val finding = Finding(
      "f",
      "cycle",
      "high",
      "p",
      "evidence",
      "high",
      "inspect-node --id p"
    )
    val selection = FindingsSelection(
      1,
      "root",
      "packages",
      Instant.parse("2026-10-13T12:00:00Z"),
      1,
      0,
      0,
      Seq(finding)
    )
    val json = selection.toJson(spaces = 0)
    assertEquals(json.parseJson[FindingsSelection], selection)
    intercept[TupsonException](
      json.replace("\"high\"", "\"unknown\"").parseJson[FindingsSelection]
    )
    intercept[TupsonException](
      json
        .replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        .parseJson[FindingsSelection]
    )
  }
}

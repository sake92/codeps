package ba.sake.codeps.report

import ba.sake.codeps.model.*
import ba.sake.tupson.{*, given}
import org.typelevel.jawn.ast.{JArray, JNum, JObject, JString, JValue}

class GeneratedArtifactsSpec extends munit.FunSuite {
  private val report = MetricsReport(
    "packages",
    "2026-10-13T12:00:00Z",
    Summary(0, 0, 0, 0),
    Nil,
    Nil,
    Nil,
    Nil
  )
  private val exportJson =
    """{"packages":{"nodes":[{"id":"p","ports":0,"mutPorts":0,"declarationSurface":{}}],"edges":[{"source":"p","target":"q"}]},"files":{"nodes":[],"edges":[]}}"""
  private def fields(
      value: JValue
  ): scala.collection.mutable.Map[String, JValue] = value match {
    case JObject(result) => result
    case _               => fail("expected JSON object")
  }

  test(
    "generated export applies omitted weight and declaration-counter defaults"
  ) {
    val graph = exportJson.parseJson[ExportGraph]
    assertEquals(graph.packages.edges.head.weight, 1)
    assertEquals(
      graph.packages.nodes.head.declarationSurface,
      DeclarationSurface()
    )
    assertEquals(graph.toJson.parseJson[ExportGraph], graph)
    assertEquals(
      exportJson
        .replace("\"target\":\"q\"", "\"target\":\"q\",\"weight\":7")
        .parseJson[ExportGraph]
        .packages
        .edges
        .head
        .weight,
      7
    )
  }

  test("generated boundary rejects null weight and integer overflow") {
    intercept[TupsonException](
      exportJson
        .replace("\"target\":\"q\"", "\"target\":\"q\",\"weight\":null")
        .parseJson[ExportGraph]
    )
    intercept[TupsonException](
      exportJson
        .replace("\"target\":\"q\"", "\"target\":\"q\",\"weight\":2147483648")
        .parseJson[ExportGraph]
    )
  }

  test(
    "required package inventory and package scope are validated before generated decoding"
  ) {
    val json = MetricsReports(report).toJson(spaces = 0)
    val tree = json.parseJson[JValue]
    fields(fields(tree)("packages")).remove("cycles")
    intercept[TupsonException](tree.render().parseJson[MetricsReports])
    intercept[TupsonException](
      json
        .replace("\"scope\":\"packages\"", "\"scope\":\"files\"")
        .parseJson[MetricsReports]
    )
  }

  test(
    "report encoding omits optional non-nullable truncation and retains nullable files"
  ) {
    val json = MetricsReports(report).toJson(spaces = 0)
    assert(!json.contains("truncation"))
    assert(json.contains("\"files\":null"))
    assertEquals(json.parseJson[MetricsReports], MetricsReports(report))
  }

  test("obsolete report versions and legacy surface fields are rejected") {
    val json = report.toJson(spaces = 0)
    intercept[TupsonException](json.replace("\"schemaVersion\":3", "\"schemaVersion\":2").parseJson[MetricsReport])
    val row = SurfaceRow("p", 1, 1, 2, 0, 2, Some(0.5))
    val tree = report.copy(surface = Seq(row)).toJson.parseJson[JValue]
    val surface = fields(tree)("surface") match {
      case JArray(rows) => fields(rows.head)
      case _ => fail("expected surface array")
    }
    surface("utilization") = surface.remove("dependentsPerPublicPort").get
    intercept[TupsonException](tree.render().parseJson[MetricsReport])
  }

  test("generated findings reject unknown enums and invalid timestamps") {
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
      "root",
      "packages",
      report.generatedAt,
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
        .replace(report.generatedAt, "not-a-timestamp")
        .parseJson[FindingsSelection]
    )
  }
}

package ba.sake.codeps.cli

import ba.sake.codeps.model.*
import ba.sake.codeps.report.*
import ba.sake.tupson.{*, given}

class ArtifactSchemaSpec extends munit.FunSuite:
  private val at = "2026-10-12T10:00:00Z"
  private val report = MetricsReport("packages", at, Summary(2, 2, 2, 0),
    Seq(Cycle("scc:a", Seq("a", "b"), 2, 0,
      CutAnalysis("completedExact", Some(1), Seq(Solution(Seq(CutCandidate("a", "b", 1)))), 2),
      internalEdges = 2, witnessCycle = Seq("a", "b", "a"))),
    Seq(PropagatorRow("a", 2, 1, 1.5)),
    Seq(SurfaceRow("a", 2, 1, 3, 1, 6, Some(2.0 / 3), Some("scc:a"),
      publicSurface = 1, privateSurface = 2, publicMutableSurface = 1,
      totalDeclaredSurface = 3, encapsulationRatio = Some(1.0 / 3), publicMutableRatio = Some(1)),
      SurfaceRow("b", 1, 1, 0, 0, 0, None)),
    Nil, findings = Seq(Finding("cycle:scc:a", "cycle", "high", "scc:a", "size=2", "high", "inspect-cycle --id scc:a")))

  test("all artifact schemas are valid Draft 2020-12 schemas") {
    assertEquals(ArtifactSchemas.metaErrors, Nil)
  }

  test("serializers conform with optional scopes, nulls, cuts, and report truncation") {
    for files <- Seq(None, Some(report.copy(scope = "files"))) do
      val reports = MetricsReports(report, files)
      assertEquals(ArtifactSchemas.errors("report.schema.json", reports.toJson), Nil)
      val entry = HealthHistoryEntry(at, "abc123", HealthSnapshot.fromReport(report), files.map(HealthSnapshot.fromReport))
      assertEquals(ArtifactSchemas.errors("history.schema.json", entry.toJson), Nil)
    val truncated = report.copy(findings = (1 to 10001).map(i => report.findings.head.copy(id = s"finding:$i")))
    assertEquals(ArtifactSchemas.errors("report.schema.json", MetricsReports(truncated).toJson), Nil)
    val cached = truncated.toJson.parseJson[MetricsReport]
    val selection = ReportInspector.selectFindings(cached, "root", 10, None, None).toOption.get
    assertEquals(selection.total, 10000)
    assertEquals(selection.omitted, 9990)
    assertEquals(selection.sourceFindingsOmitted, 1)
    assertEquals(ArtifactSchemas.errors("findings.schema.json", selection.toJson), Nil)
    val cycle = ReportInspector.inspectCycle(report, "scc:a").toOption.get
    assertEquals(ArtifactSchemas.errors("inspect-cycle.schema.json", ReportInspector.renderJson(cycle)), Nil)
    for id <- Seq("a", "b") do
      val node = ReportInspector.inspectNode(report, id).toOption.get
      assertEquals(ArtifactSchemas.errors("inspect-node.schema.json", ReportInspector.renderJson(node)), Nil)
  }

  test("export schema accepts parser output, nullable package ids and default edge weights") {
    val fixture = os.read(os.pwd / "testFixtures" / "cyclic.json")
    assertEquals(ArtifactSchemas.errors("export.schema.json", fixture), Nil)
    val parsed = fixture.parseJson[ExportGraph]
    val graph = parsed.copy(files = FileGraph(
      Set(FileNode("A.scala", None, 0, 0, DeclarationSurface())), Set.empty))
    assertEquals(ArtifactSchemas.errors("export.schema.json", graph.toJson), Nil)
  }

  test("schemas reject wrong versions, enum values, types, null scopes and missing fields") {
    val json = MetricsReports(report).toJson(spaces = 0, sort = true)
    assert(ArtifactSchemas.errors("report.schema.json", json.replace("\"schemaVersion\":4", "\"schemaVersion\":99")).nonEmpty)
    assert(ArtifactSchemas.errors("report.schema.json", json.replace("\"schemaVersion\":3", "\"schemaVersion\":99")).nonEmpty)
    assert(ArtifactSchemas.errors("report.schema.json", json.replace("\"scope\":\"packages\"", "\"scope\":\"files\"")).nonEmpty)
    assert(ArtifactSchemas.errors("report.schema.json", """{"schemaVersion":4,"packages":null}""").nonEmpty)
    assert(ArtifactSchemas.errors("report.schema.json", """{"schemaVersion":4}""").nonEmpty)
    assert(ArtifactSchemas.errors("report.schema.json", json.replace("\"severity\":\"high\"", "\"severity\":\"unknown\"")).nonEmpty)
    assert(ArtifactSchemas.definitionErrors("summary", """{"nodes":"2","edges":2,"nodesInCycles":2,"orphans":0}""").nonEmpty)
  }

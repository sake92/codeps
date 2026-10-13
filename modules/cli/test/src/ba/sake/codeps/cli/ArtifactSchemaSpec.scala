package ba.sake.codeps.cli

import ba.sake.codeps.model.ExportGraphOps
import ba.sake.codeps.model.ExportGraphOps.*

import ba.sake.codeps.artifacts.models.{
  Edge as ExportEdge,
  DeclarationSurface as ExportDeclarationSurface,
  *
}

import ba.sake.codeps.model.*
import ba.sake.codeps.report.*
import ba.sake.tupson.{*, given}

class ArtifactSchemaSpec extends munit.FunSuite:
  private val at = java.time.Instant.parse("2026-10-12T10:00:00Z")
  private val report = ScopeReport(
    scope = "packages",
    generatedAt = at,
    summary = Summary(nodes = 2, edges = 2, nodesInCycles = 2, orphans = 0),
    cycles = Seq(
      Cycle(
        id = "scc:a",
        members = Seq("a", "b"),
        size = 2,
        extFanIn = 0,
        cutAnalysis = CutAnalysis(
          status = "completedExact",
          greedyCutEstimate = Some(1),
          solutions =
            Seq(Solution(cuts = Seq(Cut(edge = Seq("a", "b"), weight = 1)))),
          examinedCandidates = 2
        ),
        internalEdges = 2,
        witnessCycle = Seq("a", "b", "a"),
        incomingEdges = 0,
        outgoingEdges = 0
      )
    ),
    propagators =
      Seq(Propagator(node = "a", fanIn = 2, fanOut = 1, score = 1.5)),
    surface = Seq(
      Surface(
        node = "a",
        fanIn = 2,
        fanOut = 1,
        ports = 3,
        mutPorts = 1,
        exposure = 6,
        dependentsPerPublicPort = Some(2.0 / 3),
        cycleId = Some("scc:a"),
        publicSurface = 1,
        privateSurface = 2,
        publicMutableSurface = 1,
        totalDeclaredSurface = 3,
        encapsulationRatio = Some(1.0 / 3),
        publicMutableRatio = Some(1),
        protectedSurface = 0.0,
        packageSurface = 0.0,
        protectedMutableSurface = 0.0,
        packageMutableSurface = 0.0,
        privateMutableSurface = 0.0
      ),
      Surface(
        node = "b",
        fanIn = 1,
        fanOut = 1,
        ports = 0,
        mutPorts = 0,
        exposure = 0,
        dependentsPerPublicPort = None,
        cycleId = None,
        publicSurface = 0.0,
        protectedSurface = 0.0,
        packageSurface = 0.0,
        privateSurface = 0.0,
        publicMutableSurface = 0.0,
        protectedMutableSurface = 0.0,
        packageMutableSurface = 0.0,
        privateMutableSurface = 0.0,
        totalDeclaredSurface = 0.0,
        encapsulationRatio = None,
        publicMutableRatio = None
      )
    ),
    orphans = Nil,
    findings = Seq(
      Finding(
        id = "cycle:scc:a",
        kind = "cycle",
        severity = "high",
        subject = "scc:a",
        evidence = "size=2",
        confidence = "high",
        nextAction = "inspect-cycle --id scc:a"
      )
    ),
    schemaVersion = 3,
    truncation = None
  )

  test("all artifact schemas are valid Draft 2020-12 schemas") {
    assertEquals(ArtifactSchemas.metaErrors, Nil)
  }

  test(
    "serializers conform with optional scopes, nulls, cuts, and report truncation"
  ) {
    for files <- Seq(None, Some(report.copy(scope = "files"))) do
      val reports =
        MetricsReports(packages = report, files = files, schemaVersion = 4)
      assertEquals(
        ArtifactSchemas.errors("report.schema.json", reports.toJson),
        Nil
      )
      val entry = HealthHistoryEntry(
        at = at,
        commit = "abc123",
        packages = HealthCalculator.fromReport(report),
        files = files.map(HealthCalculator.fromReport),
        schemaVersion = 6
      )
      assertEquals(
        ArtifactSchemas.errors("history.schema.json", entry.toJson),
        Nil
      )
    val truncated = ReportInspector.forJson(
      report.copy(findings =
        (1 to 10001).map(i => report.findings.head.copy(id = s"finding:$i"))
      )
    )
    assertEquals(
      ArtifactSchemas.errors(
        "report.schema.json",
        MetricsReports(
          packages = truncated,
          files = None,
          schemaVersion = 4
        ).toJson
      ),
      Nil
    )
    val cached = truncated.toJson.parseJson[ScopeReport]
    val selection = ReportInspector
      .selectFindings(cached, "root", 10, None, None)
      .toOption
      .get
    assertEquals(selection.total, 10000)
    assertEquals(selection.omitted, 9990)
    assertEquals(selection.sourceFindingsOmitted, 1)
    assertEquals(
      ArtifactSchemas.errors("findings.schema.json", selection.toJson),
      Nil
    )
    val cycle = ReportInspector.inspectCycle(report, "scc:a").toOption.get
    assertEquals(
      ArtifactSchemas
        .errors("inspect-cycle.schema.json", ReportInspector.renderJson(cycle)),
      Nil
    )
    for id <- Seq("a", "b") do
      val node = ReportInspector.inspectNode(report, id).toOption.get
      assertEquals(
        ArtifactSchemas
          .errors("inspect-node.schema.json", ReportInspector.renderJson(node)),
        Nil
      )
  }

  test(
    "export schema accepts parser output, nullable package ids and default edge weights"
  ) {
    val fixture = os.read(os.pwd / "testFixtures" / "cyclic.json")
    assertEquals(ArtifactSchemas.errors("export.schema.json", fixture), Nil)
    val parsed = fixture.parseJson[ExportGraph]
    val graph = parsed.copy(files =
      FileGraph(
        nodes = (Set(
          FileNode(
            id = "A.scala",
            packageId = None,
            ports = 0,
            mutPorts = 0,
            declarationSurface =
              ExportGraphOps.declarations(DeclarationSurface())
          )
        )).toSeq,
        edges = Seq.empty
      )
    )
    assertEquals(
      ArtifactSchemas.errors("export.schema.json", graph.toJson),
      Nil
    )
  }

  test(
    "schemas reject wrong versions, enum values, types, null scopes and missing fields"
  ) {
    val json =
      MetricsReports(packages = report, files = None, schemaVersion = 4)
        .toJson(spaces = 0, sort = true)
    assert(
      ArtifactSchemas
        .errors(
          "report.schema.json",
          json.replace("\"schemaVersion\":4", "\"schemaVersion\":99")
        )
        .nonEmpty
    )
    assert(
      ArtifactSchemas
        .errors(
          "report.schema.json",
          json.replace("\"schemaVersion\":3", "\"schemaVersion\":99")
        )
        .nonEmpty
    )
    assert(
      ArtifactSchemas
        .errors(
          "report.schema.json",
          json.replace("\"scope\":\"packages\"", "\"scope\":\"files\"")
        )
        .nonEmpty
    )
    assert(
      ArtifactSchemas
        .errors("report.schema.json", """{"schemaVersion":4,"packages":null}""")
        .nonEmpty
    )
    assert(
      ArtifactSchemas
        .errors("report.schema.json", """{"schemaVersion":4}""")
        .nonEmpty
    )
    assert(
      ArtifactSchemas
        .errors(
          "report.schema.json",
          json.replace("\"severity\":\"high\"", "\"severity\":\"unknown\"")
        )
        .nonEmpty
    )
    assert(
      ArtifactSchemas
        .definitionErrors(
          "summary",
          """{"nodes":"2","edges":2,"nodesInCycles":2,"orphans":0}"""
        )
        .nonEmpty
    )
  }

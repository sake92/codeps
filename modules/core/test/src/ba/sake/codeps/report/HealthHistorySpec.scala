package ba.sake.codeps.report

import ba.sake.codeps.artifacts.models.{
  Edge as ExportEdge,
  DeclarationSurface as ExportDeclarationSurface,
  *
}

import ba.sake.tupson.{*, given}

class HealthHistorySpec extends munit.FunSuite:

  private def snapshot(nodes: Int = 100, ratio: Option[Double] = Some(0.2)) =
    HealthSnapshot(
      status = "healthy",
      health = HealthScore(
        score = 7,
        status = "healthy",
        factors = HealthFactors(
          cycles = 8.5,
          mutableSurface = 10.0,
          exposedSurface = 9.6,
          structuralUse = 10.0,
          propagators = 10.0
        )
      ),
      structure = HealthStructure(nodes = nodes, edges = 200),
      cycles =
        HealthCycles(count = 1, nodes = 2, largestScc = 2, internalEdges = 2),
      surface = HealthSurface(
        publicSurface = 2,
        publicMutableSurface = 0,
        totalDeclaredSurface = 10,
        encapsulationRatio = ratio
      ),
      findings = HealthFindings(critical = 0, high = 0, medium = 0, low = 0)
    )

  private def entry(
      packages: HealthSnapshot = snapshot(),
      files: Option[HealthSnapshot] = Some(snapshot())
  ) =
    HealthHistoryEntry(
      at = java.time.Instant.parse("2026-09-02T12:00:00Z"),
      commit = "abc123",
      packages = packages,
      files = files,
      schemaVersion = 6
    )

  test(
    "history JSON records one commit with direct package and file sections"
  ) {
    val value = entry()
    val json = value.toJson(spaces = 0, sort = true)
    assert(json.contains("\"schemaVersion\":6"))
    assert(json.contains("\"packages\":{"))
    assert(json.contains("\"files\":{"))
    assert(!json.contains("\"scopes\""))
    assertEquals(json.parseJson[HealthHistoryEntry], value)
  }

  test("history comparison records when either scope changes") {
    val previous = entry()
    assertEquals(
      HealthHistory.decision(
        Some(previous),
        entry(packages = snapshot(nodes = 101), files = Some(snapshot())),
        0.01,
        false
      ),
      HealthRecordingDecision.NotSignificant
    )
    assertEquals(
      HealthHistory.decision(
        Some(previous),
        entry(files = Some(snapshot(nodes = 102))),
        0.01,
        false
      ),
      HealthRecordingDecision.Significant(Seq("files.structure.nodes"))
    )
  }

  test("checkpoint records an otherwise unchanged entry") {
    assertEquals(
      HealthHistory.decision(Some(entry()), entry(), 0.01, true),
      HealthRecordingDecision.Checkpoint
    )
  }

  test("NDJSON parser reports the failing line") {
    val error = HealthHistory.parseNdjson("{}\nnot-json\n").swap.toOption.get
    assert(error.contains("line 1"))
  }

  test("obsolete history entries are rejected") {
    val legacy =
      """{"at":"2026-09-02T12:00:00Z","commit":"abc123","status":"healthy","health":{"score":7,"status":"healthy","penalties":{"cycles":4,"mutableSurface":2.5,"exposedSurface":2,"structuralUse":1,"propagators":0.5}},"structure":{"nodes":1,"edges":0,"criticalPathLength":0},"cycles":{"count":0,"nodes":0,"largestScc":0,"internalEdges":0},"surface":{"publicSurface":0,"publicMutableSurface":0,"totalDeclaredSurface":0,"encapsulationRatio":null},"findings":{"critical":0,"high":0,"medium":0,"low":0},"schemaVersion":1}"""
    assert(HealthHistory.parseNdjson(legacy).isLeft)
  }

  test("health score retains two decimal places after applying penalties") {
    val report = ReportFixtures.report.copy(
      scope = "packages",
      generatedAt = java.time.Instant.parse("2026-09-02T12:00:00Z"),
      summary = Summary(nodes = 12, edges = 10, nodesInCycles = 5, orphans = 0),
      cycles = Seq(
        ReportFixtures.cycle.copy(
          id = "scc:a",
          members = Seq("a", "b", "c", "d", "e"),
          size = 5,
          extFanIn = 0
        )
      ),
      propagators =
        Seq(Propagator(node = "a", fanIn = 3, fanOut = 3, score = 2.0)),
      surface = Seq(
        ReportFixtures.surface.copy(
          node = "a",
          fanIn = 0,
          fanOut = 0,
          ports = 0,
          mutPorts = 0,
          exposure = 0,
          dependentsPerPublicPort = None,
          publicSurface = 10,
          publicMutableSurface = 4,
          totalDeclaredSurface = 10
        )
      ),
      orphans = Nil,
      findings = Seq(
        Finding(
          id = "structuralUse:a",
          kind = "structuralUse",
          severity = "low",
          subject = "a",
          evidence = "",
          confidence = "structuralProxy",
          nextAction = "inspect-node a"
        )
      )
    )
    val health = HealthCalculator.fromReport(report).health
    assert(math.abs(health.factors.cycles - 3.645833333333333) < 1e-12)
    assertEquals(health.factors.mutableSurface, 6.0)
    assertEquals(health.score, 4.33)
  }

  test("dashboard formats health scores to two decimal places") {
    assert(
      HealthHistoryHtml
        .render(Seq(entry()))
        .contains("Number(value).toFixed(2)")
    )
  }

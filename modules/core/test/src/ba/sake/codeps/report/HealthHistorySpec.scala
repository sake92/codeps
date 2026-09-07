package ba.sake.codeps.report

import ba.sake.tupson.{*, given}

class HealthHistorySpec extends munit.FunSuite:

  private def snapshot(nodes: Int = 100, ratio: Option[Double] = Some(0.2)) =
    HealthSnapshot(
      status = "healthy",
      health = HealthScore(7, "healthy", HealthFactors(8.5, 10.0, 9.6, 10.0, 10.0)),
      structure = HealthStructure(nodes, 200, 4),
      cycles = HealthCycles(1, 2, 2, 2),
      surface = HealthSurface(2, 0, 10, ratio),
      findings = HealthFindings(0, 0, 0, 0)
    )

  private def entry(packages: HealthSnapshot = snapshot(), files: Option[HealthSnapshot] = Some(snapshot())) =
    HealthHistoryEntry("2026-09-02T12:00:00Z", "abc123", packages, files)

  test("history JSON records one commit with direct package and file sections") {
    val value = entry()
    val json = value.toJson(spaces = 0, sort = true)
    assert(json.contains("\"schemaVersion\":4"))
    assert(json.contains("\"packages\":{"))
    assert(json.contains("\"files\":{"))
    assert(!json.contains("\"scopes\""))
    assertEquals(json.parseJson[HealthHistoryEntry], value)
  }

  test("history comparison records when either scope changes") {
    val previous = entry()
    assertEquals(HealthHistory.decision(Some(previous), entry(packages = snapshot(nodes = 101)), 0.01, false), HealthRecordingDecision.NotSignificant)
    assertEquals(HealthHistory.decision(Some(previous), entry(files = Some(snapshot(nodes = 102))), 0.01, false), HealthRecordingDecision.Significant(Seq("files.structure.nodes")))
  }

  test("checkpoint records an otherwise unchanged entry") {
    assertEquals(HealthHistory.decision(Some(entry()), entry(), 0.01, true), HealthRecordingDecision.Checkpoint)
  }

  test("NDJSON parser reports the failing line") {
    val error = HealthHistory.parseNdjson("{}\nnot-json\n").swap.toOption.get
    assert(error.contains("line 1"))
  }

  test("NDJSON parser migrates a package-only v1 line") {
    val legacy = """{"at":"2026-09-02T12:00:00Z","commit":"abc123","status":"healthy","health":{"score":7,"status":"healthy","penalties":{"cycles":4,"mutableSurface":2.5,"exposedSurface":2,"structuralUse":1,"propagators":0.5}},"structure":{"nodes":1,"edges":0,"criticalPathLength":0},"cycles":{"count":0,"nodes":0,"largestScc":0,"internalEdges":0},"surface":{"publicSurface":0,"publicMutableSurface":0,"totalDeclaredSurface":0,"encapsulationRatio":null},"findings":{"critical":0,"high":0,"medium":0,"low":0},"schemaVersion":1}"""
    val parsed = HealthHistory.parseNdjson(legacy).toOption.get.head
    assertEquals(parsed.schemaVersion, 4)
    assertEquals(parsed.files, None)
    assertEquals(parsed.packages.health.factors, HealthFactors(0.0, 0.0, 0.0, 0.0, 0.0))
  }

  test("health score gives cycles the largest capped penalty") {
    val report = MetricsReport(
      scope = "packages", generatedAt = "2026-09-02T12:00:00Z", summary = Summary(10, 10, 5, 0, 2),
      cycles = Seq(Cycle("scc:a", Seq("a", "b", "c", "d", "e"), 5, 0)), propagators = Seq(PropagatorRow("a", 3, 3, 2.0)),
      surface = Seq(SurfaceRow("a", 0, 0, 0, 0, 0, None, publicSurface = 10, publicMutableSurface = 4, totalDeclaredSurface = 10)),
      orphans = Nil, findings = Seq(Finding("structuralUse:a", "structuralUse", "low", "a", "", "structuralProxy", "inspect-node a"))
    )
    val health = HealthSnapshot.fromReport(report).health
    assertEquals(health.factors.cycles, 3.125)
    assertEquals(health.factors.mutableSurface, 6.0)
    assertEquals(health.score, 4)
  }

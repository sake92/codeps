package ba.sake.codeps.report

import ba.sake.codeps.artifacts.models.{
  Edge as ExportEdge,
  DeclarationSurface as ExportDeclarationSurface,
  *
}

import ba.sake.tupson.{*, given}
import org.typelevel.jawn.Parser
import org.typelevel.jawn.ast.JObject
import org.typelevel.jawn.ast.JValue.facade

class MetricsReportSpec extends munit.FunSuite:

  test("report json uses the exact camelCase shape from the spec") {
    val report = ReportFixtures.report.copy(
      scope = "packages",
      generatedAt = java.time.Instant.parse("2026-08-27T10:00:00Z"),
      summary =
        Summary(nodes = 100, edges = 214, nodesInCycles = 34, orphans = 3),
      cycles = Seq(
        ReportFixtures.cycle.copy(
          id = "scc:cache",
          members = Seq("cache", "scheduler"),
          witnessCycle = Seq("cache", "scheduler", "cache"),
          size = 2,
          extFanIn = 5,
          internalEdges = 2,
          incomingEdges = 5,
          outgoingEdges = 3,
          cutAnalysis = CutAnalysis(
            status = "completedExact",
            greedyCutEstimate = Some(1),
            solutions = Seq(
              Solution(cuts =
                Seq(Cut(edge = Seq("scheduler", "cache"), weight = 4))
              )
            ),
            examinedCandidates = 2
          )
        )
      ),
      propagators =
        Seq(Propagator(node = "cache", fanIn = 3, fanOut = 2, score = 2.0)),
      surface = Seq(
        ReportFixtures.surface.copy(
          node = "cache",
          fanIn = 3,
          fanOut = 2,
          ports = 9.0,
          mutPorts = 5.0,
          exposure = 24.0,
          dependentsPerPublicPort = Some(0.33),
          cycleId = Some("scc:cache")
        ),
        ReportFixtures.surface.copy(
          node = "outside",
          fanIn = 0,
          fanOut = 1,
          ports = 1.0,
          mutPorts = 0.0,
          exposure = 1.0,
          dependentsPerPublicPort = None
        )
      ),
      orphans = Seq("DeadUtil.scala"),
      findings = Seq(
        Finding(
          id = "cycle:scc:cache",
          kind = "cycle",
          severity = "high",
          subject = "scc:cache",
          evidence = "size=2, extFanIn=5, greedyCutEstimate=1",
          confidence = "high",
          nextAction = "inspect-cycle scc:cache"
        )
      )
    )
    val json = report.toJson(spaces = 0, sort = false)
    assert(json.contains("\"scope\":\"packages\""))
    assert(json.contains("\"schemaVersion\":3"))
    assert(json.contains("\"generatedAt\":\"2026-08-27T10:00:00Z\""))
    assert(json.contains("\"nodesInCycles\":34"))
    assert(!json.contains("criticalPathLength"))
    assert(json.contains("\"extFanIn\":5"))
    assert(json.contains("\"cutAnalysis\""))
    assert(json.contains("\"status\":\"completedExact\""))
    assert(json.contains("\"greedyCutEstimate\":1"))
    assert(json.contains("\"examinedCandidates\":2"))
    assert(json.contains("\"cycles\""))
    assert(json.contains("\"solutions\""))
    // object key order is hash-based, so assert each camelCase key-value pair independently
    assert(json.contains("\"cuts\":[{"))
    assert(json.contains("\"edge\":[\"scheduler\",\"cache\"]"))
    assert(json.contains("\"weight\":4"))
    assert(json.contains("\"propagators\":[{"))
    assert(json.contains("\"node\":\"cache\""))
    assert(json.contains("\"score\":2"))
    assert(!json.contains("cutCandidates"))
    assert(json.contains("\"edge\":[\"scheduler\",\"cache\"]"))
    assert(!json.contains("\"effect\""))
    assert(!json.contains("\"new_size\""))
    assert(json.contains("\"mutPorts\":5"))
    assert(json.contains("\"fanIn\":3"))
    assert(!json.contains("\"mut_ports\""))
    assert(!json.contains("\"fan_in\""))
    assert(json.contains("\"exposure\":24"))
    assert(json.contains("\"dependentsPerPublicPort\":0.33"))
    assert(json.contains("\"members\":[\"cache\",\"scheduler\"]"))
    assert(
      json.contains("\"witnessCycle\":[\"cache\",\"scheduler\",\"cache\"]")
    )
    assert(json.contains("\"internalEdges\":2"))
    assert(json.contains("\"incomingEdges\":5"))
    assert(json.contains("\"outgoingEdges\":3"))
    assert(json.contains("\"cycleId\":\"scc:cache\""))
    assert(json.contains("\"cycleId\":null"))
    assert(json.contains("\"findings\":[{"))
    assert(json.contains("\"kind\":\"cycle\""))
    assert(json.contains("\"nextAction\":\"inspect-cycle scc:cache\""))
    assert(!json.contains("articulation_points"))
  }

  test(
    "integral doubles render as integers, null structural proxy renders as null"
  ) {
    val row = ReportFixtures.surface.copy(
      node = "x",
      fanIn = 1,
      fanOut = 0,
      ports = 3.0,
      mutPorts = 0.0,
      exposure = 3.0,
      dependentsPerPublicPort = None
    )
    val json = row.toJson(spaces = 0, sort = false)
    assert(json.contains("\"ports\":3"))
    assert(json.contains("\"dependentsPerPublicPort\":null"))
  }

  test("report JSON retains complete solutions without display-only metadata") {
    val cuts = (1 to 9).map(i =>
      Cut(
        edge = Seq(s"canonical.source.$i", s"canonical.target.$i"),
        weight = i
      )
    )
    val report = ReportFixtures.report.copy(
      scope = "packages",
      generatedAt = java.time.Instant.parse("2026-08-27T10:00:00Z"),
      summary =
        Summary(nodes = 10, edges = 20, nodesInCycles = 10, orphans = 0),
      cycles = Seq(
        ReportFixtures.cycle.copy(
          id = "scc:canonical.source.1",
          members = Seq("canonical.source.1", "canonical.target.1"),
          size = 10,
          extFanIn = 0,
          cutAnalysis = CutAnalysis(
            status = "completedExact",
            greedyCutEstimate = Some(3),
            solutions = Seq(Solution(cuts = cuts)),
            examinedCandidates = 4
          )
        )
      ),
      propagators = Seq.empty,
      surface = Seq.empty,
      orphans = Seq.empty
    )
    val json = report.toJson(spaces = 0, sort = false)
    val rootKeys =
      Parser.parseFromString(json).toOption.get.asInstanceOf[JObject].vs.keySet

    assertEquals(
      rootKeys,
      Set(
        "schemaVersion",
        "scope",
        "generatedAt",
        "summary",
        "cycles",
        "propagators",
        "surface",
        "orphans",
        "findings",
        "truncation"
      )
    )
    assertEquals("\"edge\":".r.findAllIn(json).length, 9)
    assert(
      json.contains("\"edge\":[\"canonical.source.9\",\"canonical.target.9\"]")
    )
    assert(json.contains("\"internalEdges\":0"))
  }

  test("v3 report JSON can be read back for inspection") {
    val report = ReportFixtures.report.copy(
      scope = "packages",
      generatedAt = java.time.Instant.parse("2026-08-27T10:00:00Z"),
      summary = Summary(nodes = 2, edges = 2, nodesInCycles = 2, orphans = 0),
      cycles = Seq(
        ReportFixtures.cycle.copy(
          id = "scc:a",
          members = Seq("a", "b"),
          witnessCycle = Seq("a", "b", "a"),
          size = 2,
          extFanIn = 1,
          internalEdges = 2,
          incomingEdges = 1,
          outgoingEdges = 2
        )
      ),
      propagators = Seq.empty,
      surface = Seq(
        ReportFixtures.surface.copy(
          node = "a",
          fanIn = 1,
          fanOut = 1,
          ports = 1,
          mutPorts = 0,
          exposure = 1,
          dependentsPerPublicPort = None,
          cycleId = Some("scc:a")
        ),
        ReportFixtures.surface.copy(
          node = "b",
          fanIn = 1,
          fanOut = 1,
          ports = 1,
          mutPorts = 0,
          exposure = 1,
          dependentsPerPublicPort = None,
          cycleId = Some("scc:a")
        )
      ),
      orphans = Seq.empty,
      findings = Seq.empty
    )
    assertEquals(
      report.toJson(spaces = 0, sort = false).parseJson[ScopeReport],
      report
    )
  }

  test("report parser rejects schema version 1") {
    val json =
      """{"schemaVersion":1,"scope":"packages","generatedAt":"2026-08-27T10:00:00Z","summary":{"nodes":0,"edges":0,"nodesInCycles":0,"orphans":0,"criticalPathLength":0},"cycles":[],"propagators":[],"surface":[],"orphans":[],"findings":[]}"""
    val error =
      intercept[ba.sake.tupson.TupsonException](json.parseJson[ScopeReport])
    assert(error.getMessage.contains("schemaVersion"))
  }

  test("surface fields serialize with camelCase names") {
    val row = ReportFixtures.surface.copy(
      node = "p",
      fanIn = 2,
      fanOut = 1,
      ports = 3.0,
      mutPorts = 1.0,
      exposure = 6.0,
      dependentsPerPublicPort = Some(2.0 / 3.0),
      publicSurface = 3.0,
      protectedSurface = 2.0,
      packageSurface = 1.0,
      privateSurface = 4.0,
      publicMutableSurface = 1.0,
      totalDeclaredSurface = 10.0,
      encapsulationRatio = Some(0.3),
      publicMutableRatio = Some(1.0 / 3.0)
    )
    val report = ReportFixtures.report.copy(
      scope = "packages",
      generatedAt = java.time.Instant.parse("2026-08-27T10:00:00Z"),
      summary = Summary(nodes = 1, edges = 0, nodesInCycles = 0, orphans = 0),
      cycles = Nil,
      propagators = Nil,
      surface = Seq(row),
      orphans = Nil,
      truncation = Some(ReportTruncation(findingsOmitted = 3))
    )
    val json = report.toJson(spaces = 0, sort = false)
    assert(json.contains("\"dependentsPerPublicPort\":"))
    assert(json.contains("\"publicSurface\":3"))
    assert(json.contains("\"totalDeclaredSurface\":10"))
    assert(json.contains("\"findingsOmitted\":3"))
    assert(!json.contains("publicSymbols"))
    assertEquals(json.parseJson[ScopeReport], report)
  }

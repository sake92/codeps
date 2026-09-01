package ba.sake.codeps.report

import ba.sake.tupson.{*, given}
import org.typelevel.jawn.Parser
import org.typelevel.jawn.ast.JObject
import org.typelevel.jawn.ast.JValue.facade

class MetricsReportSpec extends munit.FunSuite:

  test("report json uses the exact camelCase shape from the spec") {
    val report = MetricsReport(
      scope = "packages",
      generatedAt = "2026-08-27T10:00:00Z",
      summary = Summary(nodes = 100, edges = 214, nodesInCycles = 34, orphans = 3, criticalPathLength = 7),
      cycles = Seq(Cycle(
        id = "scc:cache",
        members = Seq("cache", "scheduler"),
        witnessCycle = Seq("cache", "scheduler", "cache"),
        size = 2,
        extFanIn = 5,
        internalEdges = 2,
        incomingEdges = 5,
        outgoingEdges = 3,
        cutAnalysis = CutAnalysis("completed", Some(1), Seq(Solution(Seq(CutCandidate("scheduler", "cache", 4)))), 2)
      )),
      propagators = Seq(PropagatorRow("cache", 3, 2, 2.0)),
      surface = Seq(
        SurfaceRow("cache", 3, 2, 9.0, 5.0, 24.0, Some(0.33), Some("scc:cache")),
        SurfaceRow("outside", 0, 1, 1.0, 0.0, 1.0, None, None)
      ),
      orphans = Seq("DeadUtil.scala"),
      findings = Seq(Finding(
        "cycle:scc:cache", "cycle", "high", "scc:cache",
        "size=2, extFanIn=5, greedyCutEstimate=1", "high", "inspect-cycle scc:cache"
      ))
    )
    val json = report.toJson(spaces = 0, sort = false)
    assert(json.contains("\"scope\":\"packages\""))
    assert(json.contains("\"schemaVersion\":2"))
    assert(json.contains("\"generatedAt\":\"2026-08-27T10:00:00Z\""))
    assert(json.contains("\"nodesInCycles\":34"))
    assert(json.contains("\"criticalPathLength\":7"))
    assert(json.contains("\"extFanIn\":5"))
    assert(json.contains("\"cutAnalysis\""))
    assert(json.contains("\"status\":\"completed\""))
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
    assert(json.contains("\"utilization\":0.33"))
    assert(json.contains("\"members\":[\"cache\",\"scheduler\"]"))
    assert(json.contains("\"witnessCycle\":[\"cache\",\"scheduler\",\"cache\"]"))
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

  test("report serialization always emits schema version 2") {
    val report = MetricsReport("packages", "2026-08-27T10:00:00Z", Summary(0, 0, 0, 0, 0), Nil, Nil, Nil, Nil, schemaVersion = 1)
    assert(report.toJson(spaces = 0, sort = false).contains("\"schemaVersion\":2"))
    assert(!report.toJson(spaces = 0, sort = false).contains("\"schemaVersion\":1"))
  }

  test("integral doubles render as integers, null utilization renders as null") {
    val row = SurfaceRow("x", 1, 0, 3.0, 0.0, 3.0, None)
    val json = row.toJson(spaces = 0, sort = false)
    assert(json.contains("\"ports\":3"))
    assert(!json.contains("3.0"))
    assert(json.contains("\"utilization\":null"))
  }

  test("report JSON retains complete solutions without display-only metadata") {
    val cuts = (1 to 9).map(i => CutCandidate(s"canonical.source.$i", s"canonical.target.$i", i))
    val report = MetricsReport(
      scope = "packages",
      generatedAt = "2026-08-27T10:00:00Z",
      summary = Summary(10, 20, 10, 0, 1),
      cycles = Seq(Cycle("scc:canonical.source.1", Seq("canonical.source.1", "canonical.target.1"), 10, 0,
        CutAnalysis("completed", Some(3), Seq(Solution(cuts)), 4))),
      propagators = Seq.empty,
      surface = Seq.empty,
      orphans = Seq.empty
    )
    val json = report.toJson(spaces = 0, sort = false)
    val rootKeys = Parser.parseFromString(json).toOption.get.asInstanceOf[JObject].vs.keySet

    assertEquals(rootKeys, Set("schemaVersion", "scope", "generatedAt", "summary", "cycles", "propagators", "surface", "orphans", "findings"))
    assertEquals("\"edge\":".r.findAllIn(json).length, 9)
    assert(json.contains("\"edge\":[\"canonical.source.9\",\"canonical.target.9\"]"))
    assert(json.contains("\"internalEdges\":0"))
  }

  test("v2 report JSON can be read back for inspection") {
    val report = MetricsReport(
      scope = "packages",
      generatedAt = "2026-08-27T10:00:00Z",
      summary = Summary(2, 2, 2, 0, 0),
      cycles = Seq(Cycle(
        id = "scc:a",
        members = Seq("a", "b"),
        witnessCycle = Seq("a", "b", "a"),
        size = 2,
        extFanIn = 1,
        internalEdges = 2,
        incomingEdges = 1,
        outgoingEdges = 2,
        cutAnalysis = CutAnalysis.notRequested
      )),
      propagators = Seq.empty,
      surface = Seq(SurfaceRow("a", 1, 1, 1, 0, 1, None, Some("scc:a")), SurfaceRow("b", 1, 1, 1, 0, 1, None, Some("scc:a"))),
      orphans = Seq.empty,
      findings = Seq.empty
    )
    assertEquals(report.toJson(spaces = 0, sort = false).parseJson[MetricsReport], report)
  }

  test("v2 report parser rejects schema version 1") {
    val json = """{"schemaVersion":1,"scope":"packages","generatedAt":"2026-08-27T10:00:00Z","summary":{"nodes":0,"edges":0,"nodesInCycles":0,"orphans":0,"criticalPathLength":0},"cycles":[],"propagators":[],"surface":[],"orphans":[],"findings":[]}"""
    val error = intercept[ba.sake.tupson.TupsonException](json.parseJson[MetricsReport])
    assert(error.getMessage.contains("incompatible schema version"))
  }

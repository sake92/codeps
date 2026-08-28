package ba.sake.codeps.report

import ba.sake.tupson.{*, given}

class MetricsReportSpec extends munit.FunSuite:

  test("report json uses the exact camelCase shape from the spec") {
    val report = MetricsReport(
      scope = "packages",
      generatedAt = "2026-08-27T10:00:00Z",
      summary = Summary(nodes = 100, edges = 214, nodesInCycles = 34, orphans = 3, criticalPathLength = 7),
      cycles = Seq(Cycle(
        id = "scc:cache",
        members = Seq("cache", "scheduler", "cache"),
        size = 2,
        extFanIn = 5,
        minCutsEstimate = 1,
        solutions = Seq(Solution(Seq(CutCandidate("scheduler", "cache", 4))))
      )),
      propagators = Seq(PropagatorRow("cache", 3, 2, 2.0)),
      surface = Seq(SurfaceRow("cache", 3, 2, 9.0, 5.0, 24.0, Some(0.33))),
      orphans = Seq("DeadUtil.scala")
    )
    val json = report.toJson(spaces = 0, sort = false)
    assert(json.contains("\"scope\":\"packages\""))
    assert(json.contains("\"generatedAt\":\"2026-08-27T10:00:00Z\""))
    assert(json.contains("\"nodesInCycles\":34"))
    assert(json.contains("\"criticalPathLength\":7"))
    assert(json.contains("\"extFanIn\":5"))
    assert(json.contains("\"minCutsEstimate\":1"))
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
    assert(!json.contains("articulation_points"))
  }

  test("integral doubles render as integers, null utilization renders as null") {
    val row = SurfaceRow("x", 1, 0, 3.0, 0.0, 3.0, None)
    val json = row.toJson(spaces = 0, sort = false)
    assert(json.contains("\"ports\":3"))
    assert(!json.contains("3.0"))
    assert(json.contains("\"utilization\":null"))
  }

package ba.sake.codeps.report

import ba.sake.tupson.{*, given}

class MetricsReportSpec extends munit.FunSuite:

  test("report json uses the exact snake_case shape from the spec") {
    val report = MetricsReport(
      scope = "packages",
      generatedAt = "2026-08-27T10:00:00+02:00",
      summary = Summary(nodes = 100, edges = 214, nodesInCycles = 34, orphans = 3, criticalPathLength = 7),
      cycles = Seq(Cycle(
        id = "scc:cache",
        members = Seq("cache", "scheduler", "cache"),
        size = 2,
        extFanIn = 5,
        minCutsEstimate = 1,
        cutCandidates = Seq(CutCandidate("scheduler", "cache", 4))
      )),
      surface = Seq(SurfaceRow("cache", 3, 2, 9.0, 5.0, 24.0, Some(0.33))),
      orphans = Seq("DeadUtil.scala")
    )
    val json = report.toJson(spaces = 0, sort = false)
    assert(json.contains("\"scope\":\"packages\""))
    assert(json.contains("\"generated_at\":\"2026-08-27T10:00:00+02:00\""))
    assert(json.contains("\"nodes_in_cycles\":34"))
    assert(json.contains("\"critical_path_length\":7"))
    assert(json.contains("\"ext_fan_in\":5"))
    assert(json.contains("\"min_cuts_estimate\":1"))
    assert(json.contains("\"cycles\""))
    assert(json.contains("\"cut_candidates\""))
    assert(json.contains("\"edge\":[\"scheduler\",\"cache\"]"))
    assert(!json.contains("\"effect\""))
    assert(!json.contains("\"new_size\""))
    assert(json.contains("\"mut_ports\":5"))
    assert(json.contains("\"fan_in\":3"))
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

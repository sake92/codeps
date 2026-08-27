package ba.sake.codeps.report

class ReportTableSpec extends munit.FunSuite:

  private val report = MetricsReport(
    scope = "packages",
    generatedAt = "2026-08-27T10:00:00+02:00",
    summary = Summary(nodes = 2, edges = 1, nodesInCycles = 2, orphans = 1, criticalPathLength = 1),
    cycles = Seq(Cycle("scc:cache", Seq("cache", "scheduler", "cache"), 2, 5, 1,
      Seq(CutCandidate("scheduler", "cache", 4, "resolved", 1)))),
    surface = Seq(
      SurfaceRow("cache", 3, 2, 9.0, 5.0, 24.0, Some(0.33)),
      SurfaceRow("iso", 0, 0, 0.0, 0.0, 0.0, None)
    ),
    orphans = Seq("iso"),
    articulationPoints = Seq("cache")
  )

  test("table renders summary, cycles, surface, orphans, articulation points") {
    val text = ReportTable.render(report)
    assert(text.contains("scope: packages"))
    assert(text.contains("nodes: 2"))
    assert(text.contains("critical_path_length: 1"))
    assert(text.contains("scc:cache"))
    assert(text.contains("scheduler -> cache (w=4, resolved -> 1)"))
    assert(text.contains("0.33"))
    assert(text.contains("iso"))
  }

  test("null utilization renders as a dash") {
    val surfaceLine = ReportTable.render(report).linesIterator.find(_.contains("iso")).get
    assert(surfaceLine.contains("—"))
  }

  test("empty report renders (none) placeholders") {
    val empty = MetricsReport(
      scope = "packages",
      generatedAt = "x",
      summary = Summary(0, 0, 0, 0, 0),
      cycles = Seq.empty,
      surface = Seq.empty,
      orphans = Seq.empty,
      articulationPoints = Seq.empty
    )
    val text = ReportTable.render(empty)
    assert(text.contains("(none)"))
  }

package ba.sake.codeps.report

class ReportTableSpec extends munit.FunSuite:

  private val report = MetricsReport(
    scope = "packages",
    generatedAt = "2026-08-27T10:00:00Z",
    summary = Summary(nodes = 2, edges = 1, nodesInCycles = 2, orphans = 1, criticalPathLength = 1),
    cycles = Seq(Cycle("scc:cache", Seq("cache", "scheduler", "cache"), 2, 5, 1,
      Seq(Solution(Seq(CutCandidate("scheduler", "cache", 4)))))),
    propagators = Seq(PropagatorRow("cache", 3, 2, 2.0)),
    surface = Seq(
      SurfaceRow("cache", 3, 2, 9.0, 5.0, 24.0, Some(0.33)),
      SurfaceRow("iso", 0, 0, 0.0, 0.0, 0.0, None)
    ),
    orphans = Seq("iso")
  )

  test("table renders summary, cycles, surface, orphans") {
    val text = ReportTable.render(report)
    assert(text.contains("scope: packages"))
    assert(text.contains("nodes: 2"))
    assert(text.contains("criticalPathLength: 1"))
    assert(text.contains("scc:cache"))
    assert(text.contains("1) scheduler -> cache (w=4)"))
    assert(text.contains("0.33"))
    assert(text.contains("iso"))
    assert(text.contains("Change propagators"))
    assert(text.contains("2.00"))
  }

  test("tiny utilization renders with more precision") {
    val r = report.copy(surface = Seq(SurfaceRow("tiny", 1, 0, 356.5, 0.0, 356.5, Some(1.0 / 356.5))))
    val line = ReportTable.render(r).linesIterator.find(_.contains("tiny")).get
    assert(line.contains("0.0028")) // 1/356.5, not the misleading 0.00
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
      propagators = Seq.empty,
      surface = Seq.empty,
      orphans = Seq.empty
    )
    val text = ReportTable.render(empty)
    assert(text.contains("(none)"))
  }

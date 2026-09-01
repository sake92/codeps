package ba.sake.codeps.report

import ba.sake.codeps.model.{DepsGraph, Edge, Node, NodeKind}
import ba.sake.codeps.report.MetricsCalculator.Scope

class ReportTableSpec extends munit.FunSuite:

  private val report = MetricsReport(
    scope = "packages",
    generatedAt = "2026-08-27T10:00:00Z",
    summary = Summary(nodes = 2, edges = 1, nodesInCycles = 2, orphans = 1, criticalPathLength = 1),
    cycles = Seq(Cycle("scc:cache", Seq("cache", "scheduler", "cache"), 2, 5,
      CutAnalysis("completedExact", Some(1), Seq(Solution(Seq(CutCandidate("scheduler", "cache", 4)))), 2))),
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
    assert(text.contains("Cycle scc:cache"))
    assert(text.contains("solution 1: scheduler -> cache (w=4)"))
    assert(text.contains("0.33"))
    assert(text.contains("iso"))
    assert(text.contains("Change propagators"))
    assert(text.contains("2.00"))
  }

  test("surface defaults to the compact core columns") {
    val rich = report.copy(surface = Seq(SurfaceRow(
      node = "cache",
      fanIn = 3,
      fanOut = 2,
      ports = 9.0,
      mutPorts = 5.0,
      exposure = 24.0,
      dependentsPerPublicPort = Some(0.33),
      publicSurface = 3.0,
      protectedSurface = 2.0,
      packageSurface = 1.0,
      privateSurface = 4.0,
      publicMutableSurface = 1.0,
      protectedMutableSurface = 1.0,
      packageMutableSurface = 0.0,
      privateMutableSurface = 1.0,
      totalDeclaredSurface = 10.0,
      encapsulationRatio = Some(0.3),
      publicMutableRatio = Some(1.0 / 3.0)
    )))
    val surfaceSection = ReportTable.render(rich).substring(
      ReportTable.render(rich).indexOf("Surface risks"),
      ReportTable.render(rich).indexOf("Public surface")
    )
    val header = surfaceSection.linesIterator.find(_.startsWith("node")).get

    assertEquals(header.trim.split("\\s+").toSeq, Seq("node", "in", "out", "ports", "mut", "encap%", "use"))
    assert(!header.contains("fanIn"))
    assert(!header.contains("mutPorts"))
    assert(surfaceSection.contains("0.30"))
    assert(surfaceSection.contains("0.33"))
  }

  test("surface column groups compose in canonical order and deduplicate columns") {
    val groups = Seq(
      ReportTable.ColumnGroup.Mutability,
      ReportTable.ColumnGroup.Visibility,
      ReportTable.ColumnGroup.Core,
      ReportTable.ColumnGroup.Visibility
    )
    val text = ReportTable.render(report, columns = groups)
    val surfaceSection = text.substring(text.indexOf("Surface risks"), text.indexOf("Public surface"))
    val header = surfaceSection.linesIterator.find(_.startsWith("node")).get

    assertEquals(
      header.trim.split("\\s+").toSeq,
      Seq("node", "in", "out", "ports", "mut", "encap%", "use", "pub", "prot", "pkg", "priv", "pubMut", "protMut", "pkgMut", "privMut")
    )
    assertEquals(header.trim.split("\\s+").count(_ == "pub"), 1)
    assert(!header.contains("publicSurface"))
    assert(!header.contains("publicMutableSurface"))
  }

  test("all surface columns expose the complete accounting view with short headings") {
    val text = ReportTable.render(report, columns = Seq(ReportTable.ColumnGroup.All))
    val surfaceSection = text.substring(text.indexOf("Surface risks"), text.indexOf("Public surface"))
    val header = surfaceSection.linesIterator.find(_.startsWith("node")).get

    assertEquals(
      header.trim.split("\\s+").toSeq,
      Seq("node", "in", "out", "ports", "mut", "encap%", "use", "pub", "prot", "pkg", "priv",
        "pubMut", "protMut", "pkgMut", "privMut", "exp", "total", "mut%")
    )
    assert(!header.contains("fanIn"))
    assert(!header.contains("encapsulationRatio"))
    assert(!header.contains("totalDeclaredSurface"))
  }

  test("default table bounds each inventory and --all shows every row") {
    val rows = (1 to 20).map { i =>
      SurfaceRow(s"node$i", i, 0, i.toDouble, 0.0, i.toDouble, Some(1.0))
    }
    val reportWithTwentySurfaceRows = report.copy(surface = rows)

    val text = ReportTable.render(reportWithTwentySurfaceRows)
    assert(text.contains("Surface risks (top 10 of 20)"))
    assert(text.contains("node10"))
    assert(!text.contains("node20"))

    val allText = ReportTable.render(reportWithTwentySurfaceRows, showAll = true)
    assert(allText.contains("Surface risks (all 20)"))
    assert(allText.contains("node20"))
  }

  test("structured findings render as a bounded ranked section") {
    val findings = (1 to 20).map { i =>
      Finding(s"finding:$i", "cycle", "high", s"node$i", s"size=$i", "high", s"inspect-cycle node$i")
    }
    val withFindings = report.copy(findings = findings)
    val text = ReportTable.render(withFindings)
    assert(text.contains("Findings (top 10 of 20)"))
    assert(text.contains("node10"))
    assert(!text.contains("node20"))
    assert(ReportTable.render(withFindings, showAll = true).contains("node20"))
  }

  test("cycles table keeps only identifying and count columns; solutions use separate blocks") {
    val withThreeSolutions = report.copy(cycles = Seq(report.cycles.head.copy(cutAnalysis = CutAnalysis("completedExact", Some(1), Seq(
      Solution(Seq(CutCandidate("scheduler", "cache", 4))),
      Solution(Seq(CutCandidate("cache", "scheduler", 5))),
      Solution(Seq(CutCandidate("scheduler", "cache", 6)))
    ), 3))))
    val text = ReportTable.render(withThreeSolutions)
    val cyclesSection = text.substring(text.indexOf("Cycles"), text.indexOf("Change propagators"))
    val header = cyclesSection.linesIterator.find(_.startsWith("id")).get

    assert(header.contains("id"))
    assert(header.contains("size"))
    assert(header.contains("extFanIn"))
    assert(header.contains("greedyCutEstimate"))
    assert(header.contains("status"))
    assert(!header.contains("solutions"))
    assert(cyclesSection.contains("Cycle scc:cache"))
    assert(cyclesSection.contains("solution 1: scheduler -> cache (w=4)"))
    assert(cyclesSection.contains("solution 2: cache -> scheduler (w=5)"))
    assert(cyclesSection.contains("solution 3: scheduler -> cache (w=6)"))
  }

  test("large solution displays at most eight cuts and points to the complete JSON list") {
    val cuts = (1 to 9).map(i => CutCandidate(s"source$i", s"target$i", i))
    val large = report.copy(cycles = Seq(report.cycles.head.copy(cutAnalysis = CutAnalysis("completedExact", Some(1), Seq(Solution(cuts)), 1))))
    val text = ReportTable.render(large)

    assert(text.contains("source8 -> target8 (w=8)"))
    assert(!text.contains("source9 -> target9 (w=9)"))
    assert(text.contains("… 1 more (full list in JSON)"))
  }

  test("dense knot omits its cut wall and directs the user to propagators and JSON") {
    val names = (0 until 10).map(i => s"p$i")
    val graph = DepsGraph(
      nodes = names.flatMap { name =>
        Seq(Node(name, NodeKind.`package`), Node(s"$name.T", NodeKind.`type`, Some(name), None))
      }.toSet,
      edges = names.indices.flatMap { i =>
        val next = (i + 1) % names.size
        Seq(Edge(s"${names(i)}.T", s"${names(next)}.T"), Edge(s"${names(next)}.T", s"${names(i)}.T"))
      }.toSet
    )
    val denseReport = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    val denseReportWithSolution = denseReport.copy(
      cycles = denseReport.cycles.map(_.copy(cutAnalysis = CutAnalysis("completedExact", Some(4), Seq(Solution(Seq(CutCandidate(s"${names(0)}.T", s"${names(1)}.T", 1)))), 1)))
    )
    val text = ReportTable.render(denseReportWithSolution)

    assert(text.contains("dense knot: inspect propagators; full cut list via --format json"))
    assert(!text.contains("solution 1:"))
  }

  test("dense knot without solutions reports the bounded search result") {
    val names = (0 until 10).map(i => s"p$i")
    val graph = DepsGraph(
      nodes = names.flatMap { name =>
        Seq(Node(name, NodeKind.`package`), Node(s"$name.T", NodeKind.`type`, Some(name), None))
      }.toSet,
      edges = names.indices.flatMap { i =>
        val next = (i + 1) % names.size
        Seq(Edge(s"${names(i)}.T", s"${names(next)}.T"), Edge(s"${names(next)}.T", s"${names(i)}.T"))
      }.toSet
    )
    val denseReport = MetricsCalculator.run(graph, Scope.Packages).toOption.get
    val noSolutionReport = denseReport.copy(
      cycles = denseReport.cycles.map(_.copy(cutAnalysis = CutAnalysis("budgetExceeded", Some(4), Seq.empty, 2)))
    )
    val text = ReportTable.render(noSolutionReport)

    assert(text.contains("dense knot: inspect propagators; no complete solution was found within the search bounds"))
    assert(!text.contains("full cut list via --format json"))
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

  test("strips common prefix and announces it once") {
    val r = MetricsReport(
      scope = "packages",
      generatedAt = "x",
      summary = Summary(2, 1, 2, 1, 1),
      cycles = Seq(Cycle("scc:cache", Seq("org.sake.cache", "org.sake.scheduler"), 2, 5,
        CutAnalysis("completedExact", Some(1), Seq(Solution(Seq(CutCandidate("org.sake.scheduler", "org.sake.cache", 4)))), 1))),
      propagators = Seq(PropagatorRow("org.sake.cache", 3, 2, 2.0)),
      surface = Seq(
        SurfaceRow("org.sake.cache", 3, 2, 9.0, 5.0, 24.0, Some(0.33)),
        SurfaceRow("org.sake.iso", 0, 0, 0.0, 0.0, 0.0, None)
      ),
      orphans = Seq("org.sake.iso")
    )
    val text = ReportTable.render(r)
    assertEquals(text.linesIterator.count(_.startsWith("common prefix stripped")), 1)
    assert(text.contains("common prefix stripped: org.sake. (full ids via --format json)"))
    assert(text.contains("scc:cache"))
    assert(text.contains("scheduler -> cache (w=4)"))
    assert(!text.contains("org.sake.scheduler"))
    assert(!text.contains("org.sake.cache"))
    assert(text.linesIterator.exists(_.startsWith("cache"))) // propagator + surface rows
    assert(text.linesIterator.exists(_.startsWith("iso"))) // orphan + surface rows
  }

  test("no announcement when ids share no common prefix") {
    val text = ReportTable.render(report) // fixture ids: cache/scheduler/iso
    assertEquals(text.linesIterator.count(_.startsWith("common prefix stripped")), 0)
  }

  test("file scope strips common path prefix and keeps scc recognizable") {
    val r = report.copy(
      scope = "files",
      cycles = Seq(
        Cycle(
          "scc:server/src/a/A.scala",
          Seq("server/src/a/A.scala", "server/src/b/B.scala"),
          2,
          5,
          CutAnalysis("completedExact", Some(1), Seq(Solution(Seq(CutCandidate("server/src/b/B.scala", "server/src/a/A.scala", 4)))), 1)
        )
      ),
      propagators = Seq(PropagatorRow("server/src/a/A.scala", 3, 2, 2.0)),
      surface = Seq(SurfaceRow("server/src/b/B.scala", 3, 2, 9.0, 5.0, 24.0, Some(0.33))),
      orphans = Seq("server/src/c/C.scala")
    )
    val text = ReportTable.render(r)
    assert(text.contains("common prefix stripped: server/src/ (full ids via --format json)"))
    assert(text.contains("scc:a/A.scala"))
    assert(text.contains("b/B.scala -> a/A.scala (w=4)"))
    assert(text.contains("c/C.scala"))
    assert(!text.contains("server/src/a/A.scala"))
  }

package ba.sake.codeps.report

class ReportMarkdownSpec extends munit.FunSuite:

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
    orphans = Seq("iso"),
    findings = Seq(Finding("finding:cache", "cycle", "high", "cache", "size=2", "high", "inspect-cycle cache"))
  )

  test("renders deterministic GFM headings, summary, findings, cycles, and surface sections") {
    val text = ReportMarkdown.render(report)

    assert(text.startsWith("# codeps report\n"))
    assert(text.contains("## Health summary"))
    assert(text.contains("## Findings (top 1 of 1)"))
    assert(text.contains("## Cycles (top 1 of 1)"))
    assert(text.contains("## Surface risks (top 2 of 2)"))
    assert(text.contains("## Public surface"))
    assert(text.contains("## Public mutability"))
    assert(text.contains("## Public exposure ratio"))
    assert(text.contains("## Orphans"))
    assert(text.contains("|         Metric         | Value |"))
    assert(text.contains("| `cache` | `3`"))
    assert(text.contains("Solution 1"))
    assert(!text.contains("\u001b["))
    assertEquals(ReportMarkdown.render(report), ReportMarkdown.render(report))
  }

  test("flexmark table formatting keeps Markdown punctuation literal in cells") {
    val withPunctuation = report.copy(
      findings = Seq(Finding(
        id = "finding:pkg|special",
        kind = "cycle",
        severity = "high",
        subject = "pkg|special *literal* [value]",
        evidence = "value with | pipe and `ticks`",
        confidence = "high",
        nextAction = "inspect-node pkg|special"
      ))
    )

    val text = ReportMarkdown.render(withPunctuation)
    assert(text.contains("`pkg|special *literal* [value]`"))
    assert(text.contains("`` value with | pipe and `ticks` ``"))
    assert(text.linesIterator.count(_.startsWith("|")) >= 10)
    assert(!text.contains("\u001b["))
  }

  test("default Markdown view is bounded and records omitted rows") {
    val many = (1 to 20).map { i =>
      Finding(s"finding:$i", "cycle", "high", s"node$i", s"size=$i", "high", s"inspect-node node$i")
    }
    val text = ReportMarkdown.render(report.copy(findings = many))
    assert(text.contains("## Findings (top 10 of 20)"))
    assert(text.contains("`10` rows omitted from this bounded view"))
    assert(text.contains("`node10`"))
    assert(!text.contains("`node20`"))
    assert(ReportMarkdown.render(report.copy(findings = many), showAll = true).contains("## Findings (all 20)"))
    assert(ReportMarkdown.render(report.copy(findings = many), showAll = true).contains("`node20`"))
  }

  test("renders JSON inventory truncation facts") {
    val text = ReportMarkdown.render(report.copy(
      truncation = Some(ReportTruncation(findingsOmitted = 3, publicSymbolsOmitted = 4))
    ))
    assert(text.contains("Findings omitted from the JSON inventory: `3`"))
    assert(text.contains("Public symbols omitted from the JSON inventory: `4`"))
  }

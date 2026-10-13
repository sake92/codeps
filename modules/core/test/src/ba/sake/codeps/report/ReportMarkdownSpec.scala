package ba.sake.codeps.report

import ba.sake.codeps.artifacts.models.{
  Edge as ExportEdge,
  DeclarationSurface as ExportDeclarationSurface,
  *
}

class ReportMarkdownSpec extends munit.FunSuite:

  private val report = ReportFixtures.report.copy(
    scope = "packages",
    generatedAt = java.time.Instant.parse("2026-08-27T10:00:00Z"),
    summary = Summary(nodes = 2, edges = 1, nodesInCycles = 2, orphans = 1),
    cycles = Seq(
      ReportFixtures.cycle.copy(
        id = "scc:cache",
        members = Seq("cache", "scheduler", "cache"),
        size = 2,
        extFanIn = 5,
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
        dependentsPerPublicPort = Some(0.33)
      ),
      ReportFixtures.surface.copy(
        node = "iso",
        fanIn = 0,
        fanOut = 0,
        ports = 0.0,
        mutPorts = 0.0,
        exposure = 0.0,
        dependentsPerPublicPort = None
      )
    ),
    orphans = Seq("iso"),
    findings = Seq(
      Finding(
        id = "finding:cache",
        kind = "cycle",
        severity = "high",
        subject = "cache",
        evidence = "size=2",
        confidence = "high",
        nextAction = "inspect-cycle cache"
      )
    )
  )

  test(
    "renders deterministic GFM headings, summary, findings, cycles, and surface sections"
  ) {
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
    assert(text.contains("Nodes"))
    assert(text.contains("| `cache` | `3`"))
    assert(text.contains("Solution 1"))
    assert(!text.contains("\u001b["))
    assertEquals(ReportMarkdown.render(report), ReportMarkdown.render(report))
  }

  test(
    "flexmark table formatting keeps Markdown punctuation literal in cells"
  ) {
    val withPunctuation = report.copy(
      findings = Seq(
        Finding(
          id = "finding:pkg|special",
          kind = "cycle",
          severity = "high",
          subject = "pkg|special *literal* [value]",
          evidence = "value with | pipe and `ticks`",
          confidence = "high",
          nextAction = "inspect-node pkg|special"
        )
      )
    )

    val text = ReportMarkdown.render(withPunctuation)
    assert(text.contains("`pkg|special *literal* [value]`"))
    assert(text.contains("`` value with | pipe and `ticks` ``"))
    assert(text.linesIterator.count(_.startsWith("|")) >= 10)
    assert(!text.contains("\u001b["))
  }

  test("default Markdown view is bounded and records omitted rows") {
    val many = (1 to 20).map { i =>
      Finding(
        id = s"finding:$i",
        kind = "cycle",
        severity = "high",
        subject = s"node$i",
        evidence = s"size=$i",
        confidence = "high",
        nextAction = s"inspect-node node$i"
      )
    }
    val text = ReportMarkdown.render(report.copy(findings = many))
    assert(text.contains("## Findings (top 10 of 20)"))
    assert(text.contains("`10` rows omitted from this bounded view"))
    assert(text.contains("`node10`"))
    assert(!text.contains("`node20`"))
    assert(
      ReportMarkdown
        .render(report.copy(findings = many), showAll = true)
        .contains("## Findings (all 20)")
    )
    assert(
      ReportMarkdown
        .render(report.copy(findings = many), showAll = true)
        .contains("`node20`")
    )
  }

  test("renders JSON inventory truncation facts") {
    val text = ReportMarkdown.render(
      report.copy(
        truncation = Some(ReportTruncation(findingsOmitted = 3))
      )
    )
    assert(text.contains("Findings omitted from the JSON inventory: `3`"))
    assert(!text.contains("Public symbols omitted from the JSON inventory"))
  }

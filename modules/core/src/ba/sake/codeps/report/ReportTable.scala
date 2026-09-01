package ba.sake.codeps.report

/** Plain aligned-text rendering of the same data as the JSON report — a secondary
  * presentation, never a separate computation. */
object ReportTable:

  private val maxDisplayedCuts = 8
  private val denseKnotWithSolutionsNote = "dense knot: inspect propagators; full cut list via --format json"
  private val denseKnotWithoutSolutionsNote =
    "dense knot: inspect propagators; no complete solution was found within the search bounds"

  private val maxRowsPerSection = 10

  /** Render the human-facing triage view. The report model remains complete;
    * this edge applies a small, explicit bound unless the caller requests the
    * full inventory. */
  def render(report: MetricsReport, showAll: Boolean = false): String =
    val sb = new StringBuilder
    def bounded[A](rows: Seq[A]): Seq[A] = if showAll then rows else rows.take(maxRowsPerSection)
    def sectionTitle(label: String, total: Int, shown: Int): String =
      if showAll then s"$label (all $total)" else s"$label (top $shown of $total)"
    val displayedCycles = bounded(report.cycles)
    val displayedFindings = bounded(report.findings)
    val displayedPropagators = bounded(report.propagators)
    val displayedSurface = bounded(report.surface)
    val displayedOrphans = bounded(report.orphans)
    val stripIds: Iterable[String] =
      report.cycles.flatMap(_.members) ++
        report.propagators.map(_.node) ++
        report.surface.map(_.node) ++
        report.orphans
    val separator = if report.scope == "files" then '/' else '.'
    val (strippedPrefix, stripped) = PrefixStripper.strip(stripIds, separator)
    def disp(id: String): String = stripped.getOrElse(id, id)
    sb.append(s"scope: ${report.scope}    generatedAt: ${report.generatedAt}\n\n")
    sb.append("Summary\n")
    val s = report.summary
    sb.append(
      s"  nodes: ${s.nodes}    edges: ${s.edges}    nodesInCycles: ${s.nodesInCycles}" +
        s"    orphans: ${s.orphans}    criticalPathLength: ${s.criticalPathLength}\n\n"
    )

    sb.append(sectionTitle("Findings", report.findings.size, displayedFindings.size) + "\n")
    if displayedFindings.isEmpty then sb.append("  (none)\n")
    else
      sb.append(table(
        Seq("kind", "severity", "subject", "evidence", "confidence", "nextAction"),
        displayedFindings.map(f => Seq(
          f.kind,
          f.severity,
          disp(f.subject),
          f.evidence,
          f.confidence,
          f.nextAction
        ))
      ))
    sb.append("\n")

    sb.append(sectionTitle("Cycles", report.cycles.size, displayedCycles.size) + "\n")
    sb.append("(size desc, extFanIn desc)\n")
    strippedPrefix.foreach(p => sb.append(s"common prefix stripped: $p (full ids via --format json)\n"))
    if displayedCycles.isEmpty then sb.append("  (none)\n")
    else
      sb.append(table(
        Seq("id", "size", "extFanIn", "greedyCutEstimate", "status"),
        displayedCycles.map { k =>
          Seq(
            "scc:" + disp(k.members.head),
            k.size.toString,
            k.extFanIn.toString,
            k.cutAnalysis.greedyCutEstimate.map(_.toString).getOrElse("—"),
            k.cutAnalysis.status
          )
        }
      ))
      displayedCycles.foreach { cycle =>
        sb.append(s"\n  Cycle scc:${disp(cycle.members.head)}\n")
        val analysis = cycle.cutAnalysis
        if analysis.status == "notRequested" then
          sb.append("    cut analysis: notRequested (pass --analyze-cuts)\n")
        else if isDenseKnot(cycle) then
          val note = if analysis.solutions.nonEmpty then denseKnotWithSolutionsNote else denseKnotWithoutSolutionsNote
          sb.append(s"    $note\n")
        else
          analysis.solutions.zipWithIndex.foreach { (solution, index) =>
              val displayedCuts = solution.cuts.take(maxDisplayedCuts)
                .map(c => s"${disp(c.source)} -> ${disp(c.target)} (w=${c.weight})")
              val omitted = solution.cuts.size - maxDisplayedCuts
              val suffix = if omitted > 0 then s", … $omitted more (full list in JSON)" else ""
              sb.append(s"    solution ${index + 1}: ${displayedCuts.mkString(", ")}$suffix\n")
            }
      }
    sb.append("\n")

    sb.append(sectionTitle("Change propagators", report.propagators.size, displayedPropagators.size) +
      " (score = (fanIn/avgFanIn + fanOut/avgFanOut)/2; score > 1)\n")
    if displayedPropagators.isEmpty then sb.append("  (none)\n")
    else
      sb.append(table(
        Seq("node", "fanIn", "fanOut", "score"),
        displayedPropagators.map(p => Seq(disp(p.node), p.fanIn.toString, p.fanOut.toString, f"${p.score}%.2f"))
      ))
    sb.append("\n")

    sb.append(sectionTitle("Surface risks", report.surface.size, displayedSurface.size) +
      " (utilization asc; — = no fan-in)\n")
    if displayedSurface.isEmpty then sb.append("  (none)\n")
    else
      sb.append(table(
        Seq("node", "fanIn", "fanOut", "ports", "mutPorts", "exposure", "utilization"),
        displayedSurface.map(r => Seq(
          disp(r.node),
          r.fanIn.toString,
          r.fanOut.toString,
          num(r.ports),
          num(r.mutPorts),
          num(r.exposure),
          r.utilization.map(u => if u > 0 && u < 0.01 then f"$u%.4f" else f"$u%.2f").getOrElse("—")
        ))
      ))
    sb.append("\n")

    sb.append(sectionTitle("Orphans", report.orphans.size, displayedOrphans.size) + "\n")
    if displayedOrphans.isEmpty then sb.append("  (none)\n")
    else displayedOrphans.foreach(o => sb.append(s"  ${disp(o)}\n"))
    sb.result()

  /** Aligns columns to the widest cell, two-space gaps. */
  private def table(header: Seq[String], rows: Seq[Seq[String]]): String =
    val widths = header.indices.map { i =>
      (header(i) +: rows.map(_(i))).map(_.length).max
    }
    def line(cells: Seq[String]): String =
      cells.indices.map(i => cells(i).padTo(widths(i), ' ')).mkString("  ")
    (line(header) +: rows.map(line)).mkString("\n")

  private def num(d: Double): String =
    if !d.isNaN && !d.isInfinite && d == math.rint(d) then d.toLong.toString else d.toString

  private def isDenseKnot(cycle: Cycle): Boolean =
    cycle.size >= 10 &&
      cycle.internalEdges >= 20 &&
      cycle.cutAnalysis.greedyCutEstimate.exists(_.toDouble / cycle.internalEdges >= 0.15)

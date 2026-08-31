package ba.sake.codeps.report

/** Plain aligned-text rendering of the same data as the JSON report — a secondary
  * presentation, never a separate computation. */
object ReportTable:

  private val maxDisplayedCuts = 8
  private val denseKnotNote = "dense knot: inspect propagators; full cut list via --format json"

  def render(report: MetricsReport): String =
    val sb = new StringBuilder
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

    sb.append("Cycles (size desc, extFanIn desc)\n")
    strippedPrefix.foreach(p => sb.append(s"common prefix stripped: $p (full ids via --format json)\n"))
    if report.cycles.isEmpty then sb.append("  (none)\n")
    else
      sb.append(table(
        Seq("id", "size", "extFanIn", "minCutsEstimate"),
        report.cycles.map { k =>
          Seq("scc:" + disp(k.members.head), k.size.toString, k.extFanIn.toString, k.minCutsEstimate.toString)
        }
      ))
      report.cycles.foreach { cycle =>
        sb.append(s"\n  Cycle scc:${disp(cycle.members.head)}\n")
        if isDenseKnot(cycle) then sb.append(s"    $denseKnotNote\n")
        else
          cycle.solutions.zipWithIndex.foreach { (solution, index) =>
            val displayedCuts = solution.cuts.take(maxDisplayedCuts)
              .map(c => s"${disp(c.source)} -> ${disp(c.target)} (w=${c.weight})")
            val omitted = solution.cuts.size - maxDisplayedCuts
            val suffix = if omitted > 0 then s", … $omitted more (full list in JSON)" else ""
            sb.append(s"    solution ${index + 1}: ${displayedCuts.mkString(", ")}$suffix\n")
          }
      }
    sb.append("\n")

    sb.append("Change propagators (score = (fanIn/avgFanIn + fanOut/avgFanOut)/2; score > 1, top 10)\n")
    if report.propagators.isEmpty then sb.append("  (none)\n")
    else
      sb.append(table(
        Seq("node", "fanIn", "fanOut", "score"),
        report.propagators.map(p => Seq(disp(p.node), p.fanIn.toString, p.fanOut.toString, f"${p.score}%.2f"))
      ))
    sb.append("\n")

    sb.append("Surface (utilization asc; — = no fan-in)\n")
    sb.append(table(
      Seq("node", "fanIn", "fanOut", "ports", "mutPorts", "exposure", "utilization"),
      report.surface.map(r => Seq(
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

    sb.append("Orphans\n")
    if report.orphans.isEmpty then sb.append("  (none)\n")
    else report.orphans.foreach(o => sb.append(s"  ${disp(o)}\n"))
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
      cycle.minCutsEstimate.toDouble / cycle.internalEdges >= 0.15

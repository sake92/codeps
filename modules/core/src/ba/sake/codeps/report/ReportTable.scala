package ba.sake.codeps.report

/** Plain aligned-text rendering of the same data as the JSON report — a secondary
  * presentation, never a separate computation. */
object ReportTable:

  def render(report: MetricsReport): String =
    val sb = new StringBuilder
    sb.append(s"scope: ${report.scope}    generatedAt: ${report.generatedAt}\n\n")
    sb.append("Summary\n")
    val s = report.summary
    sb.append(
      s"  nodes: ${s.nodes}    edges: ${s.edges}    nodesInCycles: ${s.nodesInCycles}" +
        s"    orphans: ${s.orphans}    criticalPathLength: ${s.criticalPathLength}\n\n"
    )

    sb.append("Cycles (size desc, extFanIn desc)\n")
    if report.cycles.isEmpty then sb.append("  (none)\n")
    else
      sb.append(table(
        Seq("id", "size", "extFanIn", "minCutsEstimate", "solutions"),
        report.cycles.map { k =>
          val sols = if k.solutions.isEmpty then "—"
          else k.solutions.zipWithIndex.map { (s, i) =>
            s"${i + 1}) " + s.cuts.map(c => s"${c.source} -> ${c.target} (w=${c.weight})").mkString(", ")
          }.mkString("  ")
          Seq(k.id, k.size.toString, k.extFanIn.toString, k.minCutsEstimate.toString, sols)
        }
      ))
    sb.append("\n")

    sb.append("Change propagators (score = (fanIn/avgFanIn + fanOut/avgFanOut)/2; score > 1, top 10)\n")
    if report.propagators.isEmpty then sb.append("  (none)\n")
    else
      sb.append(table(
        Seq("node", "fanIn", "fanOut", "score"),
        report.propagators.map(p => Seq(p.node, p.fanIn.toString, p.fanOut.toString, f"${p.score}%.2f"))
      ))
    sb.append("\n")

    sb.append("Surface (utilization asc; — = no fan-in)\n")
    sb.append(table(
      Seq("node", "fanIn", "fanOut", "ports", "mutPorts", "exposure", "utilization"),
      report.surface.map(r => Seq(
        r.node,
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
    else report.orphans.foreach(o => sb.append(s"  $o\n"))
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

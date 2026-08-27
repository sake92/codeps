package ba.sake.codeps.report

/** Plain aligned-text rendering of the same data as the JSON report — a secondary
  * presentation, never a separate computation. */
object ReportTable:

  def render(report: MetricsReport): String =
    val sb = new StringBuilder
    sb.append(s"scope: ${report.scope}    generated_at: ${report.generatedAt}\n\n")
    sb.append("Summary\n")
    val s = report.summary
    sb.append(
      s"  nodes: ${s.nodes}    edges: ${s.edges}    nodes_in_cycles: ${s.nodesInCycles}" +
        s"    orphans: ${s.orphans}    critical_path_length: ${s.criticalPathLength}\n\n"
    )

    sb.append("Cycles (size desc, ext_fan_in desc)\n")
    if report.cycles.isEmpty then sb.append("  (none)\n")
    else
      sb.append(table(
        Seq("id", "size", "ext_fan_in", "min_cuts_estimate", "cut candidates"),
        report.cycles.map { k =>
          val cuts = if k.cutCandidates.isEmpty then "—"
          else k.cutCandidates.map(c => s"${c.source} -> ${c.target} (w=${c.weight})").mkString(", ")
          Seq(k.id, k.size.toString, k.extFanIn.toString, k.minCutsEstimate.toString, cuts)
        }
      ))
    sb.append("\n")

    sb.append("Surface (utilization asc; — = no fan-in)\n")
    sb.append(table(
      Seq("node", "fan_in", "fan_out", "ports", "mut_ports", "exposure", "utilization"),
      report.surface.map(r => Seq(
        r.node,
        r.fanIn.toString,
        r.fanOut.toString,
        num(r.ports),
        num(r.mutPorts),
        num(r.exposure),
        r.utilization.map(u => f"$u%.2f").getOrElse("—")
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

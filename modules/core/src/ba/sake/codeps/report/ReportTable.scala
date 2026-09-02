package ba.sake.codeps.report

import fansi.{Attrs, Bold, Color, Str}

/** Plain aligned-text rendering of the same data as the JSON report — a secondary
  * presentation, never a separate computation. */
object ReportTable:

  /** Named groups of surface columns exposed by the CLI. The group vocabulary
    * stays in the presentation layer: it does not add fields to MetricsReport. */
  enum ColumnGroup:
    case Core, Visibility, Mutability, Coupling, All

  private val maxDisplayedCuts = 8
  private val denseKnotWithSolutionsNote = "dense knot: inspect propagators; full cut list via --format json"
  private val denseKnotWithoutSolutionsNote =
    "dense knot: inspect propagators; no complete solution was found within the search bounds"

  private val maxRowsPerSection = 10
  private val sectionSeparator = "-" * 80

  /** Render the human-facing triage view. The report model remains complete;
    * this edge applies a small, explicit bound unless the caller requests the
    * full inventory. */
  def render(
      report: MetricsReport,
      showAll: Boolean = false,
      columns: Seq[ColumnGroup] = Nil,
      color: Boolean = false
  ): String =
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
    val idSeparator = if report.scope == "files" then '/' else '.'
    val (strippedPrefix, stripped) = PrefixStripper.strip(stripIds, idSeparator)
    def disp(id: String): String = stripped.getOrElse(id, id)
    sb.append(s"scope: ${report.scope}    generatedAt: ${report.generatedAt}\n\n")
    sb.append(styled("Summary", headingAttrs, color) + "\n")
    val s = report.summary
    sb.append(
      s"  nodes: ${s.nodes}    edges: ${s.edges}    nodesInCycles: ${s.nodesInCycles}" +
        s"    orphans: ${s.orphans}    criticalPathLength: ${s.criticalPathLength}\n\n"
    )

    def appendSectionSeparator(): Unit = sb.append(styled(sectionSeparator, separatorAttrs, color) + "\n")
    def appendSectionTitle(label: String, total: Int, shown: Int, suffix: String = ""): Unit =
      sb.append(styled(sectionTitle(label, total, shown) + suffix, headingAttrs, color) + "\n")

    appendSectionSeparator()
    appendSectionTitle("Findings", report.findings.size, displayedFindings.size)
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
        )),
        color
      ))
    sb.append("\n")

    appendSectionSeparator()
    appendSectionTitle("Cycles", report.cycles.size, displayedCycles.size)
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
        },
        color
      ))
      displayedCycles.foreach { cycle =>
        sb.append("\n  " + styled(s"Cycle scc:${disp(cycle.members.head)}", headingAttrs, color) + "\n")
        val analysis = cycle.cutAnalysis
        if analysis.status == "notRequested" then
          sb.append("    cut analysis: notRequested (pass --analyze-cuts)\n")
        else if isDenseKnot(cycle) then
          val note = if analysis.solutions.nonEmpty then denseKnotWithSolutionsNote else denseKnotWithoutSolutionsNote
          sb.append("    " + styled(note, truncationAttrs, color) + "\n")
        else
          analysis.solutions.zipWithIndex.foreach { (solution, index) =>
              val displayedCuts = solution.cuts.take(maxDisplayedCuts)
                .map(c => s"${disp(c.source)} -> ${disp(c.target)} (w=${c.weight})")
              val omitted = solution.cuts.size - maxDisplayedCuts
              val suffix = if omitted > 0 then s", … $omitted more (full list in JSON)" else ""
              sb.append(s"    solution ${index + 1}: ${displayedCuts.mkString(", ")}")
              if omitted > 0 then sb.append(styled(suffix, truncationAttrs, color))
              sb.append("\n")
            }
      }
    sb.append("\n")

    appendSectionSeparator()
    appendSectionTitle("Change propagators", report.propagators.size, displayedPropagators.size,
      " (score = (fanIn/avgFanIn + fanOut/avgFanOut)/2; score > 1)")
    if displayedPropagators.isEmpty then sb.append("  (none)\n")
    else
      sb.append(table(
        Seq("node", "fanIn", "fanOut", "score"),
        displayedPropagators.map(p => Seq(disp(p.node), p.fanIn.toString, p.fanOut.toString, f"${p.score}%.2f")),
        color
      ))
    sb.append("\n")

    appendSectionSeparator()
    appendSectionTitle("Surface risks", report.surface.size, displayedSurface.size,
      " (dependentsPerPublicPort asc; — = no fan-in)")
    if displayedSurface.isEmpty then sb.append("  (none)\n")
    else
      val selectedColumns = surfaceColumns(columns, disp)
      sb.append(table(
        selectedColumns.map(_.heading),
        displayedSurface.map(row => selectedColumns.map(_.value(row))),
        color
      ))
    sb.append("\n")

    def rankedSection(label: String, rows: Seq[SurfaceRow], header: String)(metric: SurfaceRow => Double): Unit =
      val ranked = rows.filter(row => metric(row) > 0.0).sortBy(row => (-metric(row), row.node))
      appendSectionSeparator()
      appendSectionTitle(label, ranked.size, bounded(ranked).size)
      if ranked.isEmpty then sb.append("  (none)\n")
      else sb.append(table(Seq("node", header), bounded(ranked).map(row => Seq(disp(row.node), num(metric(row)))), color))
      sb.append("\n")

    rankedSection("Public surface", report.surface, "pub")(_.publicSurface)
    rankedSection("Public mutability", report.surface, "pubMut")(_.publicMutableSurface)
    val encapsulated = report.surface.filter(_.encapsulationRatio.exists(_ > 0.0))
      .sortBy(row => (-row.encapsulationRatio.getOrElse(0.0), row.node))
    appendSectionSeparator()
    appendSectionTitle("Public exposure ratio", encapsulated.size, bounded(encapsulated).size)
    if encapsulated.isEmpty then sb.append("  (none)\n")
    else sb.append(table(Seq("node", "encap%"), bounded(encapsulated).map(row =>
      Seq(disp(row.node), row.encapsulationRatio.map(value => f"$value%.2f").getOrElse("—"))), color))
    sb.append("\n")

    if report.orphans.nonEmpty then
      appendSectionSeparator()
      appendSectionTitle("Orphans", report.orphans.size, displayedOrphans.size)
      displayedOrphans.foreach(o => sb.append(s"  ${disp(o)}\n"))
    sb.result()

  /** Aligns columns to the widest visible cell, two-space gaps. */
  private def table(header: Seq[String], rows: Seq[Seq[String]], color: Boolean): String =
    val styledHeader = header.map(value => styledStr(value, headingAttrs, color))
    val styledRows = rows.map(_.zip(header).map { case (value, heading) =>
      styledCell(heading, value, color)
    })
    val widths = header.indices.map { i =>
      (styledHeader(i) +: styledRows.map(_(i))).map(_.length).max
    }
    def line(cells: Seq[Str]): String =
      cells.indices.map { i =>
        cells(i).render + (" " * (widths(i) - cells(i).length))
      }.mkString("  ")
    (line(styledHeader) +: styledRows.map(line)).mkString("\n")

  private val headingAttrs: Attrs = Attrs(Bold.On, Color.Cyan)
  private val separatorAttrs: Attrs = Color.DarkGray
  private val truncationAttrs: Attrs = Color.DarkGray

  private def styled(value: String, attrs: Attrs, color: Boolean): String =
    styledStr(value, attrs, color).render

  private def styledStr(value: String, attrs: Attrs, color: Boolean): Str =
    if color then attrs(Str(value)) else Str(value)

  private def styledCell(heading: String, value: String, color: Boolean): Str =
    if !color || heading != "severity" then Str(value)
    else
      value match
        case "critical" => Attrs(Bold.On, Color.Red)(Str(value))
        case "high"     => Attrs(Bold.On, Color.Red)(Str(value))
        case "medium"   => Attrs(Bold.On, Color.Yellow)(Str(value))
        case "low"      => Color.Cyan(Str(value))
        case _           => Str(value)

  private def num(d: Double): String =
    if !d.isNaN && !d.isInfinite && d == math.rint(d) then d.toLong.toString else d.toString

  private[report] case class SurfaceColumn(heading: String, value: SurfaceRow => String)

  private[report] def surfaceColumns(
      requested: Seq[ColumnGroup],
      disp: String => String
  ): Seq[SurfaceColumn] =
    // Groups are semantic views, so a focused group can stand alone. Some
    // indicators intentionally belong to more than one view (for example,
    // fan-in is both a core risk signal and a coupling measure); the final
    // fold below removes those overlaps when groups are composed.
    val core = Seq(
      SurfaceColumn("node", row => disp(row.node)),
      SurfaceColumn("in", _.fanIn.toString),
      SurfaceColumn("out", _.fanOut.toString),
      SurfaceColumn("ports", row => num(row.ports)),
      SurfaceColumn("mut", row => num(row.mutPorts)),
      SurfaceColumn("encap%", row => row.encapsulationRatio.map(value => f"$value%.2f").getOrElse("—")),
      SurfaceColumn("use", row => usage(row.dependentsPerPublicPort))
    )
    val visibility = Seq(
      SurfaceColumn("pub", row => num(row.publicSurface)),
      SurfaceColumn("prot", row => num(row.protectedSurface)),
      SurfaceColumn("pkg", row => num(row.packageSurface)),
      SurfaceColumn("priv", row => num(row.privateSurface)),
      SurfaceColumn("total", row => num(row.totalDeclaredSurface))
    )
    val mutability = Seq(
      SurfaceColumn("mut", row => num(row.mutPorts)),
      SurfaceColumn("pubMut", row => num(row.publicMutableSurface)),
      SurfaceColumn("protMut", row => num(row.protectedMutableSurface)),
      SurfaceColumn("pkgMut", row => num(row.packageMutableSurface)),
      SurfaceColumn("privMut", row => num(row.privateMutableSurface)),
      SurfaceColumn("mut%", row => row.publicMutableRatio.map(value => f"$value%.2f").getOrElse("—"))
    )
    val coupling = Seq(
      SurfaceColumn("in", _.fanIn.toString),
      SurfaceColumn("out", _.fanOut.toString),
      SurfaceColumn("exp", row => num(row.exposure)),
      SurfaceColumn("use", row => usage(row.dependentsPerPublicPort))
    )
    val all = core ++ visibility ++ mutability ++ coupling
    val columnsByGroup = Map[ColumnGroup, Seq[SurfaceColumn]](
      ColumnGroup.Core -> core,
      ColumnGroup.Visibility -> visibility,
      ColumnGroup.Mutability -> mutability,
      ColumnGroup.Coupling -> coupling,
      ColumnGroup.All -> all
    )
    // Group order is canonical rather than dependent on flag order. This
    // keeps repeated invocations reproducible and makes `all` a stable view.
    val effective = if requested.isEmpty then Seq(ColumnGroup.Core) else requested
    val selectedGroups =
      if effective.contains(ColumnGroup.All) then Seq(ColumnGroup.All)
      else Seq(ColumnGroup.Core, ColumnGroup.Visibility, ColumnGroup.Mutability, ColumnGroup.Coupling)
        .filter(effective.contains)
    val selected = selectedGroups.flatMap(group => columnsByGroup(group))
    // A node identifier is useful even for a group-only request such as
    // `--columns visibility`; keep it as the one invariant identity column.
    val nodeColumn = SurfaceColumn("node", row => disp(row.node))
    val withNode = if selected.exists(_.heading == nodeColumn.heading) then selected else nodeColumn +: selected
    withNode.foldLeft(Vector.empty[SurfaceColumn]) { (unique, column) =>
      if unique.exists(_.heading == column.heading) then unique else unique :+ column
    }

  private def usage(value: Option[Double]): String =
    value.map(u => if u > 0 && u < 0.01 then f"$u%.4f" else f"$u%.2f").getOrElse("—")

  private def isDenseKnot(cycle: Cycle): Boolean =
    cycle.size >= 10 &&
      cycle.internalEdges >= 20 &&
      cycle.cutAnalysis.greedyCutEstimate.exists(_.toDouble / cycle.internalEdges >= 0.15)

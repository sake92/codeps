package ba.sake.codeps.report

import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.formatter.Formatter
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.util.format.{MarkdownTable, TableCell as FormatTableCell}
import com.vladsch.flexmark.util.sequence.LineAppendableImpl

/** GFM rendering of the bounded, human-facing report view.
  *
  * The report model stays the source of truth. This renderer only assembles the
  * selected rows and lets flexmark parse and format the resulting document. In
  * particular, table layout and cell delimiters are owned by flexmark's table
  * extension; dynamic cells are code spans so ids and evidence remain literal
  * Markdown text even when they contain pipes or other Markdown punctuation.
  */
object ReportMarkdown:

  private val maxRowsPerSection = 10
  private val maxDisplayedCuts = 8

  private val options: MutableDataSet =
    MutableDataSet()
      .set(Parser.EXTENSIONS, java.util.Arrays.asList(TablesExtension.create()))
      .set(TablesExtension.FORMAT_TABLE_LEAD_TRAIL_PIPES, true)
      .set(TablesExtension.FORMAT_TABLE_SPACE_AROUND_PIPES, true)
      .set(TablesExtension.FORMAT_TABLE_ADJUST_COLUMN_WIDTH, true)

  private val parser = Parser.builder(options).build()
  private val formatter = Formatter.builder(options).build()

  /** Render Markdown without ANSI styling. The default view shows the same
    * bounded inventories as ReportTable; `showAll` requests every row. */
  def render(
      report: MetricsReport,
      showAll: Boolean = false,
      columns: Seq[ReportTable.ColumnGroup] = Nil
  ): String =
    def bounded[A](rows: Seq[A]): Seq[A] = if showAll then rows else rows.take(maxRowsPerSection)
    val sectionTitle = (label: String, total: Int, shown: Int) =>
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

    val source = new StringBuilder
    source.append("# codeps report\n\n")
    source.append(s"- Scope: ${code(report.scope)}\n")
    source.append(s"- Generated at: ${code(report.generatedAt)}\n\n")

    source.append("## Health summary\n\n")
    val summary = report.summary
    source.append(table(
      Seq("Metric", "Value"),
      Seq(
        Seq("Nodes", summary.nodes.toString),
        Seq("Edges", summary.edges.toString),
        Seq("Nodes in cycles", summary.nodesInCycles.toString),
        Seq("Orphans", summary.orphans.toString),
        Seq("Critical path length", summary.criticalPathLength.toString)
      )
    ))
    source.append("\n\n")

    appendSection(source, sectionTitle("Findings", report.findings.size, displayedFindings.size))
    appendOmittedFact(source, report.findings.size - displayedFindings.size)
    if displayedFindings.isEmpty then source.append("_(none)_\n")
    else
      source.append(table(
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
      source.append("\n")
    source.append("\n")

    appendSection(source, sectionTitle("Cycles", report.cycles.size, displayedCycles.size))
    appendOmittedFact(source, report.cycles.size - displayedCycles.size)
    source.append("_(size desc, extFanIn desc)_\n\n")
    strippedPrefix.foreach(prefix =>
      source.append(s"Common prefix stripped: ${code(prefix)} (full ids via ${code("--format json")})\n\n")
    )
    if displayedCycles.isEmpty then source.append("_(none)_\n")
    else
      source.append(table(
        Seq("id", "size", "extFanIn", "greedyCutEstimate", "status"),
        displayedCycles.map { cycle =>
          Seq(
            "scc:" + disp(cycle.members.head),
            cycle.size.toString,
            cycle.extFanIn.toString,
            cycle.cutAnalysis.greedyCutEstimate.map(_.toString).getOrElse("—"),
            cycle.cutAnalysis.status
          )
        }
      ))
      source.append("\n")
      displayedCycles.foreach { cycle =>
        source.append(s"### Cycle ${code("scc:" + disp(cycle.members.head))}\n\n")
        val analysis = cycle.cutAnalysis
        if analysis.status == "notRequested" then
          source.append(s"Cut analysis: ${code("notRequested")} (pass ${code("--analyze-cuts")})\n\n")
        else if isDenseKnot(cycle) then
          val note =
            if analysis.solutions.nonEmpty then
              "Dense knot: inspect propagators; full cut list via --format json"
            else
              "Dense knot: inspect propagators; no complete solution was found within the search bounds"
          source.append(s"> $note\n\n")
        else
          analysis.solutions.zipWithIndex.foreach { (solution, index) =>
            val displayedCuts = solution.cuts.take(maxDisplayedCuts)
              .map(cut => s"${disp(cut.source)} → ${disp(cut.target)} (w=${cut.weight})")
            val omitted = solution.cuts.size - maxDisplayedCuts
            source.append(s"- Solution ${index + 1}: ${displayedCuts.map(code).mkString(", ")}")
            if omitted > 0 then
              source.append(s"; ${code(omitted.toString + " more cuts omitted (full list in JSON)")}")
            source.append("\n")
          }
          if analysis.solutions.isEmpty then source.append("No complete solution was found.\n")
          source.append("\n")
      }
    source.append("\n")

    appendSection(source, sectionTitle("Change propagators", report.propagators.size, displayedPropagators.size))
    appendOmittedFact(source, report.propagators.size - displayedPropagators.size)
    source.append("_score = (fanIn/avgFanIn + fanOut/avgFanOut)/2; score > 1_\n\n")
    if displayedPropagators.isEmpty then source.append("_(none)_\n")
    else
      source.append(table(
        Seq("node", "fanIn", "fanOut", "score"),
        displayedPropagators.map(row => Seq(disp(row.node), row.fanIn.toString, row.fanOut.toString, f"${row.score}%.2f"))
      ))
      source.append("\n")
    source.append("\n")

    appendSection(source, sectionTitle("Surface risks", report.surface.size, displayedSurface.size))
    appendOmittedFact(source, report.surface.size - displayedSurface.size)
    source.append("_dependentsPerPublicPort asc; — = no fan-in_\n\n")
    if displayedSurface.isEmpty then source.append("_(none)_\n")
    else
      val selectedColumns = ReportTable.surfaceColumns(columns, disp)
      source.append(table(
        selectedColumns.map(_.heading),
        displayedSurface.map(row => selectedColumns.map(_.value(row)))
      ))
      source.append("\n")
    source.append("\n")

    appendRankedSection(source, "Public surface", report.surface, "pub", showAll)(_.publicSurface)(disp)
    appendRankedSection(source, "Public mutability", report.surface, "pubMut", showAll)(_.publicMutableSurface)(disp)
    val encapsulated = report.surface.filter(_.encapsulationRatio.exists(_ > 0.0))
      .sortBy(row => (-row.encapsulationRatio.getOrElse(0.0), row.node))
    appendSection(source, sectionTitle("Public exposure ratio", encapsulated.size, bounded(encapsulated).size))
    appendOmittedFact(source, encapsulated.size - bounded(encapsulated).size)
    if encapsulated.isEmpty then source.append("_(none)_\n")
    else
      source.append(table(
        Seq("node", "encap%"),
        bounded(encapsulated).map(row => Seq(disp(row.node), row.encapsulationRatio.map(value => f"$value%.2f").getOrElse("—")))
      ))
      source.append("\n")
    source.append("\n")

    if report.orphans.nonEmpty then
      appendSection(source, sectionTitle("Orphans", report.orphans.size, displayedOrphans.size))
      appendOmittedFact(source, report.orphans.size - displayedOrphans.size)
      displayedOrphans.foreach(orphan => source.append(s"- ${code(disp(orphan))}\n"))
      source.append("\n")

    source.append("## Omitted and truncation facts\n\n")
    report.truncation match
      case None =>
        source.append("No JSON inventory truncation was recorded. The Markdown view is bounded per section; pass ")
        source.append(s"${code("--all")} to include every triage row.\n")
      case Some(truncation) =>
        source.append(s"- Findings omitted from the JSON inventory: ${code(truncation.findingsOmitted.toString)}\n")
        source.append(s"- The Markdown view remains bounded per section; pass ${code("--all")} to include every triage row.\n")

    val rendered = formatter.render(parser.parse(source.result())).trim
    if rendered.isEmpty then "" else rendered + "\n"

  private def appendSection(source: StringBuilder, title: String): Unit =
    source.append(s"## $title\n\n")

  private def appendOmittedFact(source: StringBuilder, omitted: Int): Unit =
    if omitted > 0 then
      source.append(s"> ${code(omitted.toString)} rows omitted from this bounded view; pass ${code("--all")} to show all.\n\n")

  private def appendRankedSection(
      source: StringBuilder,
      label: String,
      rows: Seq[SurfaceRow],
      header: String,
      showAll: Boolean
  )(metric: SurfaceRow => Double)(disp: String => String): Unit =
    val ranked = rows.filter(row => metric(row) > 0.0).sortBy(row => (-metric(row), row.node))
    val shown = if showAll then ranked else ranked.take(maxRowsPerSection)
    appendSection(source, if showAll then s"$label (all ${ranked.size})" else s"$label (top ${shown.size} of ${ranked.size})")
    appendOmittedFact(source, ranked.size - shown.size)
    if ranked.isEmpty then source.append("_(none)_\n")
    else
      source.append(table(Seq("node", header), shown.map(row => Seq(disp(row.node), number(metric(row))))))
      source.append("\n")
    source.append("\n")

  /** A code span is a literal Markdown cell. The fence grows to the longest
    * backtick run in the value, so even unusual ids/evidence remain valid GFM. */
  private def code(value: String): String =
    val normalized = value.replace("\r\n", "\n").replace('\r', '\n').replace('\n', ' ').replace('\t', ' ')
    if normalized.isEmpty then ""
    else
      val longestFence = "`+".r.findAllMatchIn(normalized).map(_.matched.length).maxOption.getOrElse(0)
      val fence = "`" * (longestFence + 1)
      val padded = normalized.startsWith("`") || normalized.endsWith("`") || normalized.startsWith(" ") || normalized.endsWith(" ")
      if padded then s"$fence $normalized $fence" else s"$fence$normalized$fence"

  /** Build a table with flexmark's table model. Parsing and formatting the
    * completed document below gives the table extension the final say over GFM
    * delimiters, widths, and whitespace. */
  private def table(headers: Seq[String], rows: Seq[Seq[String]]): String =
    val markdownTable = new MarkdownTable("", options)
    markdownTable.setHeader()
    headers.foreach(header => markdownTable.addCell(new FormatTableCell(header, 1, 1)))
    markdownTable.nextRow()
    markdownTable.setSeparator()
    headers.foreach(_ => markdownTable.addCell(new FormatTableCell("", 1, 1)))
    markdownTable.setBody()
    rows.zipWithIndex.foreach { (row, rowIndex) =>
      headers.indices.foreach { index =>
        markdownTable.addCell(new FormatTableCell(code(row.lift(index).getOrElse("")), 1, 1))
      }
      if rowIndex < rows.size - 1 then markdownTable.nextRow()
    }
    markdownTable.finalizeTable()
    val output = new LineAppendableImpl(new java.lang.StringBuilder, 0)
    markdownTable.appendTable(output)
    output.toString

  private def number(value: Double): String =
    if !value.isNaN && !value.isInfinite && value == math.rint(value) then value.toLong.toString else value.toString

  private def isDenseKnot(cycle: Cycle): Boolean =
    cycle.size >= 10 &&
      cycle.internalEdges >= 20 &&
      cycle.cutAnalysis.greedyCutEstimate.exists(_.toDouble / cycle.internalEdges >= 0.15)

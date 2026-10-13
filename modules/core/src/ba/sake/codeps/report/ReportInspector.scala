package ba.sake.codeps.report

import ba.sake.codeps.artifacts.models.{
  Edge as ExportEdge,
  DeclarationSurface as ExportDeclarationSurface,
  *
}

import ba.sake.tupson.{*, given}

object ReportInspector:
  private[report] val maxJsonFindings = 10000

  def forJson(report: ScopeReport): ScopeReport =
    val omitted = math.max(0, report.findings.size - maxJsonFindings)
    report.copy(
      findings = report.findings.take(maxJsonFindings),
      truncation = report.truncation.orElse(if omitted > 0 then
        Some(ReportTruncation(omitted))
      else None)
    )

  def selectFindings(
      report: ScopeReport,
      project: String,
      limit: Int,
      severity: Option[String],
      kind: Option[String]
  ): Either[String, FindingsSelection] =
    val severities = Set("critical", "high", "medium", "low")
    val kinds = Set("cycle", "propagator", "mutableSurface", "structuralUse")
    if limit < 1 || limit > 10000 then Left("limit must be between 1 and 10000")
    else if severity.exists(value => !severities.contains(value)) then
      Left("unknown severity (expected critical, high, medium, or low)")
    else if kind.exists(value => !kinds.contains(value)) then
      Left(
        "unknown kind (expected cycle, propagator, mutableSurface, or structuralUse)"
      )
    else
      val matching = report.findings.filter(f =>
        severity.forall(_ == f.severity) && kind.forall(_ == f.kind)
      )
      val selected = matching.take(limit)
      Right(
        FindingsSelection(
          project = project,
          scope = report.scope,
          generatedAt = report.generatedAt,
          total = matching.size,
          omitted = matching.size - selected.size,
          sourceFindingsOmitted =
            report.truncation.map(_.findingsOmitted).getOrElse(0),
          findings = selected,
          schemaVersion = 1
        )
      )

  def renderTable(selection: FindingsSelection): String =
    val sb = new StringBuilder
    sb.append(
      s"${selection.project} (${selection.scope}): ${selection.findings.size}/${selection.total} cached matches; " +
        s"omitted=${selection.omitted}, sourceFindingsOmitted=${selection.sourceFindingsOmitted}\n"
    )
    appendFindings(sb, selection.findings)
    sb.result()

  /** Parses report JSON carrying the current schema version. */
  def parse(json: String): Either[String, ScopeReport] =
    try Right(json.parseJson[ScopeReport])
    catch case e: Exception => Left(e.getMessage)

  def inspectCycle(
      report: ScopeReport,
      id: String
  ): Either[String, CycleInspection] =
    report.cycles.find(_.id == id) match
      case None        => Left(s"unknown cycle id: $id")
      case Some(cycle) =>
        Right(
          CycleInspection(
            id = cycle.id,
            members = cycle.members.sorted,
            witnessCycle = cycle.witnessCycle,
            size = cycle.size,
            internalEdges = cycle.internalEdges,
            incomingEdges = cycle.incomingEdges,
            outgoingEdges = cycle.outgoingEdges,
            extFanIn = cycle.extFanIn,
            cutAnalysis = cycle.cutAnalysis,
            findings =
              report.findings.filter(_.subject == cycle.id).sortBy(_.id)
          )
        )

  def inspectNode(
      report: ScopeReport,
      id: String
  ): Either[String, NodeInspection] =
    report.surface.find(_.node == id) match
      case None          => Left(s"unknown node id: $id")
      case Some(surface) =>
        Right(
          NodeInspection(
            id = id,
            surface = surface,
            cycleId = surface.cycleId,
            findings = report.findings.filter(_.subject == id).sortBy(_.id)
          )
        )

  def renderJson(detail: CycleInspection): String =
    detail.toJson(spaces = 2, sort = true)

  def renderJson(detail: NodeInspection): String = detail.toJson(spaces = 2, sort = true)

  def renderTable(detail: CycleInspection): String =
    val sb = new StringBuilder
    sb.append(s"cycle: ${detail.id}\n")
    sb.append(s"size: ${detail.size}\n")
    sb.append(s"extFanIn: ${detail.extFanIn}\n")
    sb.append("members:\n")
    detail.members.foreach(member => sb.append(s"  $member\n"))
    sb.append(s"witnessCycle: ${detail.witnessCycle.mkString(" -> ")}\n")
    sb.append(
      s"edge counts: internalEdges=${detail.internalEdges} " +
        s"incomingEdges=${detail.incomingEdges} outgoingEdges=${detail.outgoingEdges}\n"
    )
    appendCutAnalysis(sb, detail.cutAnalysis)
    appendFindings(sb, detail.findings)
    sb.result()

  def renderTable(detail: NodeInspection): String =
    val sb = new StringBuilder
    sb.append(s"node: ${detail.id}\n")
    sb.append(s"cycleId: ${detail.cycleId.getOrElse("null")}\n\n")
    sb.append("Surface\n")
    val row = detail.surface
    sb.append("  fanIn  fanOut  ports  mutPorts  exposure  dependentsPerPublicPort\n")
    sb.append(
      s"  ${row.fanIn}      ${row.fanOut}       ${number(row.ports)}      " +
        s"${number(row.mutPorts)}         ${number(row.exposure)}       " +
        s"${row.dependentsPerPublicPort.map(number).getOrElse("—")}\n"
    )
    appendFindings(sb, detail.findings)
    sb.result()

  private def appendCutAnalysis(
      sb: StringBuilder,
      analysis: CutAnalysis
  ): Unit =
    sb.append(s"cutAnalysis.status: ${analysis.status}\n")
    sb.append(
      s"cutAnalysis.greedyCutEstimate: ${analysis.greedyCutEstimate.getOrElse("null")}\n"
    )
    sb.append(
      s"cutAnalysis.examinedCandidates: ${analysis.examinedCandidates}\n"
    )
    if analysis.solutions.nonEmpty then
      sb.append("solutions:\n")
      analysis.solutions.zipWithIndex.foreach { (solution, index) =>
        val cuts = solution.cuts.map(c =>
          s"${c.edge.head} -> ${c.edge(1)} (w=${c.weight})"
        )
        sb.append(s"  ${index + 1}: ${cuts.mkString(", ")}\n")
      }

  private def appendFindings(sb: StringBuilder, findings: Seq[Finding]): Unit =
    sb.append("findings:\n")
    if findings.isEmpty then sb.append("  (none)\n")
    else findings.foreach(f => sb.append(s"  ${f.id}: ${f.kind} [${f.severity}] ${f.evidence}\n"))

  private def number(value: Double): String =
    if !value.isNaN && !value.isInfinite && value == math.rint(value) then
      value.toLong.toString
    else value.toString

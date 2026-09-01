package ba.sake.codeps.report

import ba.sake.tupson.{*, given}
import org.typelevel.jawn.ast.{JNull, JObject, JValue}

/** The selected, report-only detail returned by `inspect-cycle`.
  * No graph or cut analysis is run here: all values come from the v2 report index.
  */
case class CycleInspection(
    id: String,
    members: Seq[String],
    witnessCycle: Seq[String],
    size: Int,
    internalEdges: Int,
    incomingEdges: Int,
    outgoingEdges: Int,
    extFanIn: Int,
    cutAnalysis: CutAnalysis,
    findings: Seq[Finding]
)

/** The selected, report-only detail returned by `inspect-node`. */
case class NodeInspection(
    id: String,
    surface: SurfaceRow,
    cycleId: Option[String],
    findings: Seq[Finding]
)

object ReportInspector:

  /** Parses only report JSON carrying schema version 2. */
  def parse(json: String): Either[String, MetricsReport] =
    try Right(json.parseJson[MetricsReport])
    catch case e: TupsonException => Left(e.getMessage)

  def inspectCycle(report: MetricsReport, id: String): Either[String, CycleInspection] =
    report.cycles.find(_.id == id) match
      case None => Left(s"unknown cycle id: $id")
      case Some(cycle) =>
        Right(CycleInspection(
          id = cycle.id,
          members = cycle.members.sorted,
          witnessCycle = cycle.witnessCycle,
          size = cycle.size,
          internalEdges = cycle.internalEdges,
          incomingEdges = cycle.incomingEdges,
          outgoingEdges = cycle.outgoingEdges,
          extFanIn = cycle.extFanIn,
          cutAnalysis = cycle.cutAnalysis,
          findings = report.findings.filter(_.subject == cycle.id).sortBy(_.id)
        ))

  def inspectNode(report: MetricsReport, id: String): Either[String, NodeInspection] =
    report.surface.find(_.node == id) match
      case None => Left(s"unknown node id: $id")
      case Some(surface) =>
        Right(NodeInspection(
          id = id,
          surface = surface,
          cycleId = surface.cycleId,
          findings = report.findings.filter(_.subject == id).sortBy(_.id)
        ))

  def renderJson(detail: CycleInspection): String = detail.toJson(spaces = 2, sort = true)

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
    sb.append("  fanIn  fanOut  ports  mutPorts  exposure  utilization\n")
    sb.append(
      s"  ${row.fanIn}      ${row.fanOut}       ${number(row.ports)}      " +
        s"${number(row.mutPorts)}         ${number(row.exposure)}       " +
        s"${row.utilization.map(number).getOrElse("—")}\n"
    )
    appendFindings(sb, detail.findings)
    sb.result()

  private def appendCutAnalysis(sb: StringBuilder, analysis: CutAnalysis): Unit =
    sb.append(s"cutAnalysis.status: ${analysis.status}\n")
    sb.append(s"cutAnalysis.greedyCutEstimate: ${analysis.greedyCutEstimate.getOrElse("null")}\n")
    sb.append(s"cutAnalysis.examinedCandidates: ${analysis.examinedCandidates}\n")
    if analysis.solutions.nonEmpty then
      sb.append("solutions:\n")
      analysis.solutions.zipWithIndex.foreach { (solution, index) =>
        val cuts = solution.cuts.map(c => s"${c.source} -> ${c.target} (w=${c.weight})")
        sb.append(s"  ${index + 1}: ${cuts.mkString(", ")}\n")
      }

  private def appendFindings(sb: StringBuilder, findings: Seq[Finding]): Unit =
    sb.append("findings:\n")
    if findings.isEmpty then sb.append("  (none)\n")
    else findings.foreach(f => sb.append(s"  ${f.id}: ${f.kind} [${f.severity}] ${f.evidence}\n"))

  private def number(value: Double): String =
    if !value.isNaN && !value.isInfinite && value == math.rint(value) then value.toLong.toString
    else value.toString

object CycleInspection:
  given JsonRW[CycleInspection] with
    override def write(value: CycleInspection): JValue =
      JObject(scala.collection.mutable.Map.from(Seq(
        "id" -> JsonRW[String].write(value.id),
        "members" -> JsonRW[Seq[String]].write(value.members),
        "witnessCycle" -> JsonRW[Seq[String]].write(value.witnessCycle),
        "size" -> JsonRW[Int].write(value.size),
        "internalEdges" -> JsonRW[Int].write(value.internalEdges),
        "incomingEdges" -> JsonRW[Int].write(value.incomingEdges),
        "outgoingEdges" -> JsonRW[Int].write(value.outgoingEdges),
        "extFanIn" -> JsonRW[Int].write(value.extFanIn),
        "cutAnalysis" -> JsonRW[CutAnalysis].write(value.cutAnalysis),
        "findings" -> JsonRW[Seq[Finding]].write(value.findings)
      )))
    override def parse(path: String, jValue: JValue): CycleInspection =
      throw new UnsupportedOperationException("cycle inspections are output-only")

object NodeInspection:
  given JsonRW[NodeInspection] with
    override def write(value: NodeInspection): JValue =
      JObject(scala.collection.mutable.Map.from(Seq(
        "id" -> JsonRW[String].write(value.id),
        "surface" -> JsonRW[SurfaceRow].write(value.surface),
        "cycleId" -> (value.cycleId match
          case None     => JNull
          case Some(id) => JsonRW[String].write(id)),
        "findings" -> JsonRW[Seq[Finding]].write(value.findings)
      )))
    override def parse(path: String, jValue: JValue): NodeInspection =
      throw new UnsupportedOperationException("node inspections are output-only")

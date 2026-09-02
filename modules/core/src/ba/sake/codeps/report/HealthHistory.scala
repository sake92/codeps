package ba.sake.codeps.report

import ba.sake.tupson.{*, given}
import fansi.{Attrs, Bold, Color, Str}

/** Compact, overall repository-health facts retained across runs. This is a
  * separate wire contract from MetricsReport: it intentionally contains no
  * per-node inventory or report configuration. */
case class HealthSnapshot(
    at: String,
    commit: String,
    status: String,
    structure: HealthStructure,
    cycles: HealthCycles,
    surface: HealthSurface,
    findings: HealthFindings,
    schemaVersion: Int = 1
) derives JsonRW:
  def numericMetrics: Seq[(String, Option[Double])] = Seq(
    "structure.nodes" -> Some(structure.nodes.toDouble),
    "structure.edges" -> Some(structure.edges.toDouble),
    "structure.criticalPathLength" -> Some(structure.criticalPathLength.toDouble),
    "cycles.count" -> Some(cycles.count.toDouble),
    "cycles.nodes" -> Some(cycles.nodes.toDouble),
    "cycles.largestScc" -> Some(cycles.largestScc.toDouble),
    "cycles.internalEdges" -> Some(cycles.internalEdges.toDouble),
    "surface.publicSurface" -> Some(surface.publicSurface),
    "surface.publicMutableSurface" -> Some(surface.publicMutableSurface),
    "surface.totalDeclaredSurface" -> Some(surface.totalDeclaredSurface),
    "surface.encapsulationRatio" -> surface.encapsulationRatio,
    "findings.critical" -> Some(findings.critical.toDouble),
    "findings.high" -> Some(findings.high.toDouble),
    "findings.medium" -> Some(findings.medium.toDouble),
    "findings.low" -> Some(findings.low.toDouble)
  )

case class HealthStructure(nodes: Int, edges: Int, criticalPathLength: Int) derives JsonRW
case class HealthCycles(count: Int, nodes: Int, largestScc: Int, internalEdges: Int) derives JsonRW
case class HealthSurface(
    publicSurface: Double,
    publicMutableSurface: Double,
    totalDeclaredSurface: Double,
    encapsulationRatio: Option[Double]
) derives JsonRW
case class HealthFindings(critical: Int, high: Int, medium: Int, low: Int) derives JsonRW

object HealthSnapshot:
  def fromReport(report: MetricsReport, commit: String): HealthSnapshot =
    val severityCounts = report.findings.groupMapReduce(_.severity)(_ => 1)(_ + _)
    val publicSurface = report.surface.map(_.publicSurface).sum
    val publicMutableSurface = report.surface.map(_.publicMutableSurface).sum
    val totalDeclaredSurface = report.surface.map(_.totalDeclaredSurface).sum
    val status = Seq("critical", "high", "medium", "low").find(severityCounts.getOrElse(_, 0) > 0).getOrElse("healthy")
    HealthSnapshot(
      at = report.generatedAt,
      commit = commit,
      status = status,
      structure = HealthStructure(report.summary.nodes, report.summary.edges, report.summary.criticalPathLength),
      cycles = HealthCycles(
        count = report.cycles.size,
        nodes = report.summary.nodesInCycles,
        largestScc = report.cycles.map(_.size).maxOption.getOrElse(0),
        internalEdges = report.cycles.map(_.internalEdges).sum
      ),
      surface = HealthSurface(
        publicSurface,
        publicMutableSurface,
        totalDeclaredSurface,
        if totalDeclaredSurface == 0 then None else Some(publicSurface / totalDeclaredSurface)
      ),
      findings = HealthFindings(
        severityCounts.getOrElse("critical", 0), severityCounts.getOrElse("high", 0),
        severityCounts.getOrElse("medium", 0), severityCounts.getOrElse("low", 0)
      )
    )

enum HealthRecordingDecision:
  case Initial
  case Significant(metrics: Seq[String])
  case Checkpoint
  case NotSignificant

object HealthHistory:
  def parseNdjson(text: String): Either[String, Seq[HealthSnapshot]] =
    text.linesIterator.zipWithIndex.foldLeft[Either[String, Vector[HealthSnapshot]]](Right(Vector.empty)) { case (acc, (line, index)) =>
      acc.flatMap { snapshots =>
        if line.trim.isEmpty then Right(snapshots)
        else
          try Right(snapshots :+ line.parseJson[HealthSnapshot])
          catch case e: Exception => Left(s"invalid history line ${index + 1}: ${e.getMessage}")
      }
    }

  def decision(previous: Option[HealthSnapshot], current: HealthSnapshot, relativeChange: Double, checkpointDue: Boolean): HealthRecordingDecision =
    previous match
      case None => HealthRecordingDecision.Initial
      case Some(last) =>
        val changed = last.numericMetrics.zip(current.numericMetrics).collect {
          case ((name, before), (_, after)) if significant(before, after, relativeChange) => name
        }
        if changed.nonEmpty then HealthRecordingDecision.Significant(changed)
        else if checkpointDue then HealthRecordingDecision.Checkpoint
        else HealthRecordingDecision.NotSignificant

  def renderTable(snapshot: HealthSnapshot, decision: HealthRecordingDecision, color: Boolean = false): String =
    val out = StringBuilder()
    out.append(styled("Overall dependency health", Attrs(Bold.On, Color.Cyan), color) + "\n")
    out.append(s"  status: ${styled(snapshot.status, statusAttrs(snapshot.status), color)}    at: ${snapshot.at}    commit: ${snapshot.commit}\n")
    out.append(s"  ${decisionText(decision)}\n\n")
    out.append(styled("Structure", Attrs(Bold.On, Color.Cyan), color) + "\n")
    out.append(s"  nodes: ${snapshot.structure.nodes}    edges: ${snapshot.structure.edges}    criticalPathLength: ${snapshot.structure.criticalPathLength}\n\n")
    out.append(styled("Cycles", Attrs(Bold.On, Color.Cyan), color) + "\n")
    out.append(s"  count: ${snapshot.cycles.count}    nodes: ${snapshot.cycles.nodes}    largestScc: ${snapshot.cycles.largestScc}    internalEdges: ${snapshot.cycles.internalEdges}\n\n")
    out.append(styled("Surface", Attrs(Bold.On, Color.Cyan), color) + "\n")
    out.append(s"  publicSurface: ${number(snapshot.surface.publicSurface)}    publicMutableSurface: ${number(snapshot.surface.publicMutableSurface)}    totalDeclaredSurface: ${number(snapshot.surface.totalDeclaredSurface)}    encapsulationRatio: ${ratio(snapshot.surface.encapsulationRatio)}\n\n")
    out.append(styled("Findings", Attrs(Bold.On, Color.Cyan), color) + "\n")
    out.append(s"  critical: ${snapshot.findings.critical}    high: ${snapshot.findings.high}    medium: ${snapshot.findings.medium}    low: ${snapshot.findings.low}\n")
    out.toString

  def renderMarkdown(snapshot: HealthSnapshot, decision: HealthRecordingDecision): String =
    val out = StringBuilder()
    out.append("# Overall dependency health\n\n")
    out.append(s"**Status:** ${snapshot.status}  \n**Commit:** `${snapshot.commit}`  \n**Recorded:** ${snapshot.at}\n\n")
    out.append(s"_${decisionText(decision)}._\n\n")
    out.append("## Structure\n\n| Metric | Value |\n|---|---:|\n")
    out.append(s"| Nodes | ${snapshot.structure.nodes} |\n| Edges | ${snapshot.structure.edges} |\n| Critical path length | ${snapshot.structure.criticalPathLength} |\n\n")
    out.append("## Cycles\n\n| Metric | Value |\n|---|---:|\n")
    out.append(s"| Cycles | ${snapshot.cycles.count} |\n| Nodes in cycles | ${snapshot.cycles.nodes} |\n| Largest SCC | ${snapshot.cycles.largestScc} |\n| Internal edges | ${snapshot.cycles.internalEdges} |\n\n")
    out.append("## Surface\n\n| Metric | Value |\n|---|---:|\n")
    out.append(s"| Public surface | ${number(snapshot.surface.publicSurface)} |\n| Public mutable surface | ${number(snapshot.surface.publicMutableSurface)} |\n| Total declared surface | ${number(snapshot.surface.totalDeclaredSurface)} |\n| Encapsulation ratio | ${ratio(snapshot.surface.encapsulationRatio)} |\n\n")
    out.append("## Findings\n\n| Severity | Count |\n|---|---:|\n")
    out.append(s"| Critical | ${snapshot.findings.critical} |\n| High | ${snapshot.findings.high} |\n| Medium | ${snapshot.findings.medium} |\n| Low | ${snapshot.findings.low} |\n")
    out.toString

  private def decisionText(decision: HealthRecordingDecision): String = decision match
    case HealthRecordingDecision.Initial => "snapshot recorded (initial)"
    case HealthRecordingDecision.Significant(metrics) => s"snapshot recorded (significant: ${metrics.mkString(", ")})"
    case HealthRecordingDecision.Checkpoint => "snapshot recorded (checkpoint)"
    case HealthRecordingDecision.NotSignificant => "current health snapshot is not significantly different from the last one; skipping recording"

  private def styled(value: String, attrs: Attrs, color: Boolean): String =
    if color then attrs(Str(value)).render else value

  private def statusAttrs(status: String): Attrs = status match
    case "critical" | "high" => Attrs(Bold.On, Color.Red)
    case "medium"             => Attrs(Bold.On, Color.Yellow)
    case "low"                => Color.Cyan
    case _                     => Attrs(Bold.On, Color.Green)

  private def number(value: Double): String =
    if value == math.rint(value) then value.toLong.toString else String.format(java.util.Locale.ROOT, "%.2f", Double.box(value))

  private def ratio(value: Option[Double]): String = value match
    case None => "—"
    case Some(v) => String.format(java.util.Locale.ROOT, "%.1f%%", Double.box(v * 100.0))

  private def significant(before: Option[Double], after: Option[Double], threshold: Double): Boolean =
    (before, after) match
      case (None, None)             => false
      case (Some(_), None) | (None, Some(_)) => true
      case (Some(a), Some(b)) if a == b => false
      case (Some(0.0), Some(_)) | (Some(_), Some(0.0)) => true
      case (Some(a), Some(b)) => math.abs(b - a) / math.abs(a) > threshold

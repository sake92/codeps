package ba.sake.codeps.report

import ba.sake.tupson.{*, given}

/** Health facts for one analysis granularity. Commit metadata belongs to the
  * enclosing history entry, once for both package and file scopes. */
case class HealthSnapshot(
    status: String,
    health: HealthScore,
    structure: HealthStructure,
    cycles: HealthCycles,
    surface: HealthSurface,
    findings: HealthFindings
) derives JsonRW:
  def numericMetrics: Seq[(String, Option[Double])] = Seq(
    "health.score" -> Some(health.score.toDouble),
    "structure.nodes" -> Some(structure.nodes.toDouble),
    "structure.edges" -> Some(structure.edges.toDouble),
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

/** One NDJSON line: one commit, with package metrics always present and file
  * metrics present whenever the source format provides a file graph. */
case class HealthHistoryEntry(
    at: String,
    commit: String,
    packages: HealthSnapshot,
    files: Option[HealthSnapshot] = None,
    schemaVersion: Int = 5
) derives JsonRW

case class HealthStructure(nodes: Int, edges: Int) derives JsonRW
case class HealthCycles(count: Int, nodes: Int, largestScc: Int, internalEdges: Int) derives JsonRW
case class HealthSurface(publicSurface: Double, publicMutableSurface: Double, totalDeclaredSurface: Double, encapsulationRatio: Option[Double]) derives JsonRW
case class HealthFindings(critical: Int, high: Int, medium: Int, low: Int) derives JsonRW
case class HealthFactors(cycles: Double, mutableSurface: Double, exposedSurface: Double, structuralUse: Double, propagators: Double) derives JsonRW
case class HealthScore(score: Int, status: String, factors: HealthFactors) derives JsonRW

/** Previous history schemas had one package snapshot per line. */
private case class LegacyHealthSnapshot(
    at: String, commit: String, status: String, health: HealthScore,
    structure: HealthStructure, cycles: HealthCycles, surface: HealthSurface,
    findings: HealthFindings, schemaVersion: Int
) derives JsonRW:
  def normalized: HealthHistoryEntry =
    HealthHistoryEntry(at, commit, HealthSnapshot(status, health, structure, cycles, surface, findings))

private case class LegacyHealthScore(score: Int, status: String, penalties: LegacyHealthPenalties) derives JsonRW
private case class LegacyHealthPenalties(cycles: Double, mutableSurface: Double, exposedSurface: Double, structuralUse: Double, propagators: Double) derives JsonRW
private case class LegacyPenaltySnapshot(
    at: String, commit: String, status: String, health: LegacyHealthScore,
    structure: HealthStructure, cycles: HealthCycles, surface: HealthSurface,
    findings: HealthFindings, schemaVersion: Int
) derives JsonRW:
  def normalized: HealthHistoryEntry =
    val factors = if schemaVersion == 1 then
      HealthFactors(healthFactor(health.penalties.cycles, 4.0), healthFactor(health.penalties.mutableSurface, 2.5), healthFactor(health.penalties.exposedSurface, 2.0), healthFactor(health.penalties.structuralUse, 1.0), healthFactor(health.penalties.propagators, 0.5))
    else HealthFactors(10.0 - health.penalties.cycles, 10.0 - health.penalties.mutableSurface, 10.0 - health.penalties.exposedSurface, 10.0 - health.penalties.structuralUse, 10.0 - health.penalties.propagators)
    HealthHistoryEntry(at, commit, HealthSnapshot(status, HealthScore(health.score, health.status, factors), structure, cycles, surface, findings))

object HealthSnapshot:
  def fromReport(report: MetricsReport): HealthSnapshot =
    val severityCounts = report.findings.groupMapReduce(_.severity)(_ => 1)(_ + _)
    val publicSurface = report.surface.map(_.publicSurface).sum
    val publicMutableSurface = report.surface.map(_.publicMutableSurface).sum
    val totalDeclaredSurface = report.surface.map(_.totalDeclaredSurface).sum
    val cycles = HealthCycles(report.cycles.size, report.summary.nodesInCycles, report.cycles.map(_.size).maxOption.getOrElse(0), report.cycles.map(_.internalEdges).sum)
    val surface = HealthSurface(publicSurface, publicMutableSurface, totalDeclaredSurface, if totalDeclaredSurface == 0 then None else Some(publicSurface / totalDeclaredSurface))
    val health = score(report, cycles, surface)
    HealthSnapshot(
      health.status, health, HealthStructure(report.summary.nodes, report.summary.edges), cycles, surface,
      HealthFindings(severityCounts.getOrElse("critical", 0), severityCounts.getOrElse("high", 0), severityCounts.getOrElse("medium", 0), severityCounts.getOrElse("low", 0))
    )

  private def score(report: MetricsReport, cycles: HealthCycles, surface: HealthSurface): HealthScore =
    val nodeCount = math.max(report.summary.nodes, 1).toDouble
    val cycleCoverage = cycles.nodes.toDouble / nodeCount
    val cyclePenalty = if cycles.count == 0 then 0.0 else math.min(4.0, 1.5 + 2.5 * cycleCoverage)
    val mutablePenalty = if surface.publicSurface == 0 then 0.0 else math.min(2.5, 2.5 * surface.publicMutableSurface / surface.publicSurface)
    val exposedSurfacePenalty = surface.encapsulationRatio.fold(0.0)(ratio => math.min(2.0, 2.0 * ratio))
    val structuralUsePenalty = math.min(1.0, report.findings.count(_.kind == "structuralUse").toDouble / nodeCount)
    val propagatorPenalty = math.min(0.5, report.propagators.size.toDouble / nodeCount * 0.5)
    val factors = HealthFactors(
      healthFactor(cyclePenalty, 4.0), healthFactor(mutablePenalty, 2.5), healthFactor(exposedSurfacePenalty, 2.0),
      healthFactor(structuralUsePenalty, 1.0), healthFactor(propagatorPenalty, 0.5)
    )
    val numericScore = math.max(1, math.min(10, math.floor(10.0 - cyclePenalty - mutablePenalty - exposedSurfacePenalty - structuralUsePenalty - propagatorPenalty).toInt))
    val status = numericScore match
      case 1 | 2 => "critical"
      case 3 | 4 => "unhealthy"
      case 5 | 6 => "needs-attention"
      case 7 | 8 => "healthy"
      case _     => "excellent"
    HealthScore(numericScore, status, factors)

private def healthFactor(points: Double, maximum: Double): Double = 10.0 - points / maximum * 10.0

enum HealthRecordingDecision:
  case Initial
  case Significant(metrics: Seq[String])
  case Checkpoint
  case NotSignificant

object HealthHistory:
  def parseNdjson(text: String): Either[String, Seq[HealthHistoryEntry]] =
    text.linesIterator.zipWithIndex.foldLeft[Either[String, Vector[HealthHistoryEntry]]](Right(Vector.empty)) { case (acc, (line, index)) =>
      acc.flatMap { entries =>
        if line.trim.isEmpty then Right(entries)
        else try
          val entry =
            if "\\\"packages\\\"\\s*:".r.findFirstIn(line).nonEmpty then line.parseJson[HealthHistoryEntry]
            else if "\\\"penalties\\\"\\s*:".r.findFirstIn(line).nonEmpty then line.parseJson[LegacyPenaltySnapshot].normalized
            else line.parseJson[LegacyHealthSnapshot].normalized
          Right(entries :+ entry)
        catch case e: Exception => Left(s"invalid history line ${index + 1}: ${e.getMessage}")
      }
    }

  /** A combined entry is meaningful when either available scope changed. */
  def decision(previous: Option[HealthHistoryEntry], current: HealthHistoryEntry, relativeChange: Double, checkpointDue: Boolean): HealthRecordingDecision =
    previous match
      case None => HealthRecordingDecision.Initial
      case Some(last) =>
        val changed = significantMetrics("packages", last.packages, current.packages, relativeChange) ++ ((last.files, current.files) match
          case (Some(before), Some(after)) => significantMetrics("files", before, after, relativeChange)
          case (None, Some(_)) | (Some(_), None) => Seq("files.availability")
          case (None, None) => Nil)
        if changed.nonEmpty then HealthRecordingDecision.Significant(changed)
        else if checkpointDue then HealthRecordingDecision.Checkpoint
        else HealthRecordingDecision.NotSignificant

  private def significantMetrics(scope: String, before: HealthSnapshot, after: HealthSnapshot, threshold: Double) =
    before.numericMetrics.zip(after.numericMetrics).collect {
      case ((name, earlier), (_, later)) if significant(earlier, later, threshold) => s"$scope.$name"
    }

  private def significant(before: Option[Double], after: Option[Double], threshold: Double): Boolean =
    (before, after) match
      case (None, None) => false
      case (Some(_), None) | (None, Some(_)) => true
      case (Some(a), Some(b)) if a == b => false
      case (Some(0.0), Some(_)) | (Some(_), Some(0.0)) => true
      case (Some(a), Some(b)) => math.abs(b - a) / math.abs(a) > threshold
